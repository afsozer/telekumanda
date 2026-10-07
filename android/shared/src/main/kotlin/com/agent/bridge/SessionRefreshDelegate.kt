package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Aktif oturumu yeniden adopt etme ve kök ekran verilerini yenileme koordinasyonu. */
class SessionRefreshDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val reportError: suspend (String, Throwable) -> Unit,
    private val clearThoughts: () -> Unit,
    private val closeSocket: () -> Unit,
    private val openSocket: (String, String, String) -> Unit,
    private val coworkProjectPath: () -> String,
    private val refreshConversation: (Boolean) -> Unit,
    private val loadUsage: () -> Unit,
    private val loadOperations: () -> Unit,
    private val loadProjects: () -> Unit,
    private val loadBackendCatalog: () -> Unit,
) {
    fun refreshSession(): Job = scope.launch {
        val current = state()
        suspend fun adopt(backend: String, provider: String, sid: String, cwd: String) {
            val result = runCatching {
                when (backend) {
                    Backend.AGY.id -> client.agyAdopt(current.settings, sid, cwd)
                    Backend.CLAUDE_APP.id -> client.claudeAppAdopt(current.settings, sid, cwd, cowork = provider.isNotBlank())
                    Backend.CODEX_APP.id -> client.codexAppAdopt(current.settings, sid, cwd)
                    Backend.OPENCODE2_APP.id -> client.opencodeAppAdopt(current.settings, sid, cwd, Backend.OPENCODE2_APP.id)
                    Backend.OMP.id -> client.ompAdopt(current.settings, sid, cwd)
                    else -> sid
                }
            }
            result.onSuccess { newSid ->
                update {
                    val reset = it.copy(messagesList = emptyList(), transcript = "", truncateAfterIndex = null)
                    when (backend) {
                        Backend.AGY.id -> reset.copy(agy = reset.agy.copy(sessionId = newSid))
                        Backend.CLAUDE_APP.id -> reset.copy(claude = reset.claude.copy(sessionId = newSid))
                        Backend.CODEX_APP.id -> reset.copy(codex = reset.codex.copy(sessionId = newSid))
                        Backend.OPENCODE2_APP.id -> reset.copy(opencode = reset.opencode.copy(sessionId = newSid))
                        Backend.OMP.id -> reset.copy(omp = reset.omp.copy(sessionId = newSid))
                        else -> reset
                    }
                }
                clearThoughts(); closeSocket(); openSocket(backend, provider, newSid); refreshConversation(false)
            }.onFailure { reportError("Oturum yenilenemedi", it) }
        }
        when (current.backend) {
            Backend.AGY.id -> if (current.agy.sessionId.isNotBlank()) { adopt(Backend.AGY.id, "", current.agy.sessionId, current.agy.cwd); return@launch }
            Backend.CLAUDE_APP.id -> if (current.claude.sessionId.isNotBlank()) { adopt(Backend.CLAUDE_APP.id, "", current.claude.sessionId, current.claude.cwd); return@launch }
            Backend.COWORK.id -> {
                val provider = normalizeCoworkProvider(current.cowork.provider)
                val sid = when (provider) {
                    Backend.CODEX_APP.id -> current.codex.sessionId
                    Backend.OPENCODE2_APP.id -> current.opencode.sessionId
                    Backend.OMP.id -> current.omp.sessionId
                    else -> current.claude.sessionId
                }
                if (sid.isNotBlank()) { adopt(provider, provider, sid, coworkProjectPath()); return@launch }
            }
            Backend.CODEX_APP.id -> if (current.codex.sessionId.isNotBlank()) { adopt(Backend.CODEX_APP.id, "", current.codex.sessionId, current.codex.cwd); return@launch }
            Backend.OPENCODE2_APP.id -> if (current.opencode.sessionId.isNotBlank()) { adopt(Backend.OPENCODE2_APP.id, "", current.opencode.sessionId, current.opencode.cwd); return@launch }
            Backend.OMP.id -> if (current.omp.sessionId.isNotBlank()) { adopt(Backend.OMP.id, "", current.omp.sessionId, current.omp.cwd); return@launch }
        }
        refreshAll()
    }

    fun refreshAll(): Job = scope.launch {
        if (state().backend != null) {
            runCatching { client.health(state().settings) }
                .onSuccess { health -> update { it.copy(healthOk = health.ok && health.compatible, protocolCompatible = health.compatible, bridgeConnected = false) } }
                .onFailure { update { it.copy(healthOk = false, bridgeConnected = false) } }
            refreshConversation(false); return@launch
        }
        runCatching { client.health(state().settings) }
            .onSuccess { health -> update { it.copy(healthOk = health.ok && health.compatible, protocolCompatible = health.compatible) } }
            .onFailure { update { it.copy(healthOk = false) } }
        loadUsage(); loadOperations(); loadProjects(); loadBackendCatalog()
        update { it.copy(slashCommands = emptyList()) }
        refreshConversation(false)
    }
}
