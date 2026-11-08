package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Codex/OMP/Antigravity provider oturumlarının giriş, çıkış ve polling yaşam döngüsü. */
class ProviderLifecycleDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val prefill: ConversationPrefiller,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val reportError: suspend (String, Throwable) -> Unit,
    private val clearThoughts: () -> Unit,
    private val openSocket: (String, String) -> Unit,
    private val closeSocket: () -> Unit,
    private val loadWorkerDirs: (String) -> Unit,
    private val refreshConversation: (Boolean) -> Unit,
    private val refreshSession: () -> Unit,
    private val applyConversation: (ConversationResult) -> Unit,
    private val refreshAll: () -> Unit,
    private val syncActiveTab: (String, String, String, String) -> Unit,
) {
    private var codexPollJob: Job? = null
    private var ompPollJob: Job? = null
    private var agyPollJob: Job? = null

    fun cancelCoworkSiblingPolling() {
        codexPollJob?.cancel(); codexPollJob = null
        ompPollJob?.cancel(); ompPollJob = null
    }

    fun cancelAllPolling() {
        cancelCoworkSiblingPolling()
        agyPollJob?.cancel(); agyPollJob = null
    }

    // Model kataloğunu yükler. enterCodex dışında model çipi açılırken de çağrılır
    // (loadBackendInfo): enterCodex'siz akışlarda liste boş kalıp sheet skeleton'da
    // takılıyordu (claude-app/cowork ile aynı desen).
    fun loadCodexAppModels(): Job = scope.launch {
        runCatching { client.codexAppModels(state().settings) }
            .onSuccess { result -> update {
                val default = result.defaultModel.ifBlank { it.codex.defaultModel }
                it.copy(codex = it.codex.copy(models = result.models, defaultModel = default, model = it.codex.model.ifBlank { default }))
            } }
            .onFailure { reportError("Codex App modelleri yuklenemedi", it) }
    }

    fun enterCodex() = scope.launch {
        clearThoughts()
        // Sekme geçişinde son bilinen konuşma anında gösterilir (claude ile aynı
        // desen); bağlanma + tazeleme arkada sürüp bayat görüntüyü değiştirir.
        val warm = prefill.warm(
            "${Backend.CODEX_APP.id}:${Backend.CODEX_APP.id}",
            state().pendingBindSessionId.takeIf { state().pendingBindBackend == Backend.CODEX_APP.id }.orEmpty(),
        )
        update {
            it.copy(
                backend = Backend.CODEX_APP.id, wsConnected = false,
                codex = it.codex.copy(setupPending = true, diskLoading = true, plan = emptyList(), diff = null, diffLoading = false, commands = emptyList(), steerMode = false, contextPercent = 0),
                currentSession = if (warm != null) "Codex App" else "",
                messagesList = warm?.messages ?: emptyList(),
                transcript = warm?.transcript ?: "",
                staleConversation = warm != null,
                running = false,
                awaitingApproval = false, awaitingFirstOutput = false,
            )
        }
        loadCodexAppModels().join()
        if (state().backend != Backend.CODEX_APP.id) return@launch
        loadWorkerDirs("")
        val current = state()
        // Yalnız TEK ATIMLIK pendingBind hedefine bağlan (sekme/bildirim/fork yazdı).
        // Eski lastSessionId + "koşan herhangi biri" fallback'i her girişte mevcut
        // sohbeti kapıyordu — yeni sekmeden ikinci bir Codex oturumu açılamıyordu
        // (tekilleştirme eski sekmeyi de siliyordu). Hedef yoksa kurulum ekranı.
        val pendingSid = current.pendingBindSessionId
            .takeIf { current.pendingBindBackend == Backend.CODEX_APP.id }.orEmpty()
        if (pendingSid.isNotBlank()) update { it.copy(pendingBindBackend = "", pendingBindSessionId = "") }
        val target = if (pendingSid.isBlank()) null else {
            runCatching { client.listBackendSessions(current.settings, Backend.CODEX_APP.id) }
                .getOrDefault(emptyList()).firstOrNull { it.id == pendingSid }
        }
        if (state().backend != Backend.CODEX_APP.id) return@launch
        if (target != null) {
            update {
                val targetModel = target.model.ifBlank { it.codex.model }
                it.copy(
                    codex = it.codex.copy(sessionId = target.id, cwd = target.cwd.ifBlank { it.codex.cwd }, model = targetModel, setupPending = false, diskLoading = false),
                    currentSession = "Codex App", model = "Codex App · $targetModel",
                )
            }
            openSocket(Backend.CODEX_APP.id, target.id)
            startCodexPolling()
            refreshConversation(false)
            syncActiveTab(Backend.CODEX_APP.id, "", target.id, generateTabTitle(Backend.CODEX_APP.id, "", target.cwd, target.model))
            scope.launch { runCatching { client.codexAppDiskSessions(state().settings) }.onSuccess { list -> update { it.copy(codex = it.codex.copy(diskSessions = list)) } } }
            return@launch
        }
        // Hedefe bağlanılamadı: prefill tazelenmeyecek, bayrağı düşür (claude ile aynı).
        update { it.copy(staleConversation = false) }
        scope.launch {
            runCatching { client.codexAppDiskSessions(state().settings) }
                .onSuccess { list -> update { it.copy(codex = it.codex.copy(diskSessions = list, diskLoading = false, setupPending = false)) } }
                .onFailure { update { it.copy(codex = it.codex.copy(diskLoading = false, setupPending = false)) } }
        }
    }

    fun cancelCodexSetup() = update { it.copy(codex = it.codex.copy(setupPending = false)) }

    fun startCodex(cwd: String, model: String) = scope.launch {
        val cleanCwd = cwd.trim(); val cleanModel = model.ifBlank { codexDefaultModel() }
        if (cleanCwd.isBlank()) { emit("Klasor secin"); return@launch }
        runCatching { client.codexAppNew(state().settings, cleanCwd, cleanModel, state().codex.permissionMode) }
            .onSuccess { sessionId ->
                clearThoughts()
                update { it.copy(
                    backend = Backend.CODEX_APP.id,
                    codex = it.codex.copy(sessionId = sessionId, cwd = cleanCwd, model = cleanModel, setupPending = false, plan = emptyList(), diff = null, diffLoading = false, commands = emptyList(), steerMode = false, contextPercent = 0),
                    model = "Codex App · $cleanModel", currentSession = "Codex App", messagesList = emptyList(), transcript = "", running = false, awaitingApproval = false,
                    contextTokens = 0, contextWindow = 0,
                ) }
                openSocket(Backend.CODEX_APP.id, sessionId); startCodexPolling(); refreshConversation(false)
                syncActiveTab(Backend.CODEX_APP.id, "", sessionId, generateTabTitle(Backend.CODEX_APP.id, "", cleanCwd, cleanModel))
            }
            .onFailure { reportError("Codex App baslatilamadi", it) }
    }

    fun setCodexModel(id: String) {
        val clean = id.ifBlank { codexDefaultModel() }
        update { it.copy(codex = it.codex.copy(model = clean), model = "Codex App · $clean") }
    }

    fun loadCodexDiskSessions() = scope.launch {
        update { it.copy(codex = it.codex.copy(diskLoading = true)) }
        runCatching { client.codexAppDiskSessions(state().settings, state().drawerSearchQuery, state().drawerShowArchived) }
            .onSuccess { list -> update { it.copy(codex = it.codex.copy(diskSessions = list, diskLoading = false)) } }
            .onFailure { update { it.copy(codex = it.codex.copy(diskLoading = false)) }; reportError("PC oturumlari yuklenemedi", it) }
    }

    fun resumeCodex(session: CodexDiskSession) = scope.launch {
        runCatching { client.codexAppAdopt(state().settings, session.id, session.cwd) }
            .onSuccess { sessionId ->
                clearThoughts()
                update {
                    val activeModel = it.codex.model.ifBlank { it.codex.defaultModel }
                    it.copy(
                        backend = Backend.CODEX_APP.id,
                        codex = it.codex.copy(sessionId = sessionId, cwd = session.cwd, model = activeModel, setupPending = false, plan = emptyList(), diff = null, diffLoading = false, commands = emptyList(), steerMode = false, contextPercent = 0),
                        model = "Codex App · $activeModel", currentSession = "Codex App", messagesList = emptyList(), transcript = "",
                        contextTokens = 0, contextWindow = 0,
                        running = false, awaitingApproval = false, wsConnected = false,
                    )
                }
                openSocket(Backend.CODEX_APP.id, sessionId); startCodexPolling(); refreshConversation(false)
                syncActiveTab(Backend.CODEX_APP.id, "", sessionId, generateTabTitle(Backend.CODEX_APP.id, "", session.cwd, state().codex.model))
            }
            .onFailure { reportError("Oturum devam ettirilemedi", it) }
    }

    fun startCodexPolling() {
        codexPollJob?.cancel()
        codexPollJob = scope.launch {
            while (isActive && (state().backend == Backend.CODEX_APP.id || (state().backend == Backend.COWORK.id && state().cowork.provider == Backend.CODEX_APP.id))) {
                if (!SohbetYoklamasi.siradakiTur { state().wsConnected }) continue
                val current = state(); val sid = current.codex.sessionId
                if (sid.isBlank()) continue
                runCatching { client.codexAppConversation(current.settings, sid, limit = current.messagePageSize.coerceAtLeast(100)) }.onSuccess {
                    if (state().backend == Backend.CODEX_APP.id || (state().backend == Backend.COWORK.id && state().cowork.provider == Backend.CODEX_APP.id)) {
                        if (it.messages.isEmpty() && state().messagesList.isNotEmpty()) refreshSession() else applyConversation(it)
                    }
                }
            }
        }
    }

    fun exitCodex() = scope.launch {
        codexPollJob?.cancel(); codexPollJob = null; closeSocket()
        val sid = state().codex.sessionId
        update { it.copy(
            backend = null,
            codex = it.codex.copy(sessionId = "", lastSessionId = sid, cwd = "", plan = emptyList(), diff = null, diffLoading = false, commands = emptyList(), steerMode = false, contextPercent = 0),
            messagesList = emptyList(), transcript = "", running = false, awaitingApproval = false,
            contextTokens = 0, contextWindow = 0,
        ) }
        refreshAll()
    }

    fun loadOmpModels(): Job = scope.launch {
        runCatching { client.ompModels(state().settings) }
            .onSuccess { result -> update {
                val default = result.defaultModel.ifBlank { it.omp.defaultModel.ifBlank { "deepseek/deepseek-flash" } }
                it.copy(omp = it.omp.copy(availableModels = result.models, defaultModel = default, model = it.omp.model.ifBlank { default }))
            } }
            .onFailure { reportError("OMP modelleri yüklenemedi", it) }
    }

    fun enterOmp() = scope.launch {
        clearThoughts()
        val warm = prefill.warm(
            "${Backend.OMP.id}:${Backend.OMP.id}",
            state().pendingBindSessionId.takeIf { state().pendingBindBackend == Backend.OMP.id }.orEmpty(),
        )
        update { it.copy(
            backend = Backend.OMP.id, wsConnected = false,
            omp = it.omp.copy(setupPending = true, diskLoading = true),
            currentSession = if (warm != null) "OMP" else "",
            messagesList = warm?.messages ?: emptyList(), transcript = warm?.transcript ?: "",
            staleConversation = warm != null, running = false, awaitingApproval = false, awaitingFirstOutput = false,
        ) }
        loadOmpModels().join(); loadWorkerDirs("")
        val current = state()
        val pendingSid = current.pendingBindSessionId.takeIf { current.pendingBindBackend == Backend.OMP.id }.orEmpty()
        if (pendingSid.isNotBlank()) update { it.copy(pendingBindBackend = "", pendingBindSessionId = "") }
        if (pendingSid.isNotBlank()) {
            val target = runCatching { client.listBackendSessions(current.settings, Backend.OMP.id) }.getOrDefault(emptyList()).firstOrNull { it.id == pendingSid }
            if (target != null) {
                update { it.copy(
                    backend = Backend.OMP.id,
                    omp = it.omp.copy(sessionId = target.id, cwd = target.cwd.ifBlank { it.omp.cwd }, model = target.model.ifBlank { it.omp.model }, setupPending = false, diskLoading = false),
                    currentSession = "OMP", model = "OMP · ${target.model.substringAfterLast('/')}",
                ) }
                openSocket(Backend.OMP.id, target.id); startOmpPolling(); refreshConversation(false)
                syncActiveTab(Backend.OMP.id, "", target.id, generateTabTitle(Backend.OMP.id, "", target.cwd, target.model))
                return@launch
            }
        }
        update { it.copy(staleConversation = false) }
        scope.launch {
            runCatching { client.ompDiskSessions(state().settings) }
                .onSuccess { list -> update { it.copy(omp = it.omp.copy(diskSessions = list, diskLoading = false, setupPending = false)) } }
                .onFailure { update { it.copy(omp = it.omp.copy(diskLoading = false, setupPending = false)) } }
        }
    }

    fun startOmpPolling() {
        ompPollJob?.cancel()
        ompPollJob = scope.launch {
            while (isActive && state().backend == Backend.OMP.id) {
                if (!SohbetYoklamasi.siradakiTur { state().wsConnected }) continue
                val current = state(); val sid = current.omp.sessionId
                if (sid.isBlank()) continue
                runCatching { client.ompConversation(current.settings, sid) }.onSuccess {
                    if (state().backend == Backend.OMP.id) {
                        if (it.messages.isEmpty() && state().messagesList.isNotEmpty()) refreshSession() else applyConversation(it)
                    }
                }
            }
        }
    }

    fun exitOmp() = scope.launch {
        ompPollJob?.cancel(); ompPollJob = null; closeSocket()
        val sid = state().omp.sessionId
        update { it.copy(backend = null, omp = it.omp.copy(sessionId = "", lastSessionId = sid, cwd = ""), messagesList = emptyList(), transcript = "", running = false, awaitingApproval = false, contextTokens = 0, contextWindow = 0) }
        refreshAll()
    }


    // ── Antigravity (agy) — ACP backend'i ───────────────────────────────────
    // Ayri bir AgyDelegate YOK: eski PTY'li kurulumda 252 satirlik kendi delegesi
    // vardi, cunku disk oturumlari, trust promptu ve kendi akisi ozeldi. ACP'de
    // agy diger provider'larla ayni iskelete oturuyor (oturum ac / listele /
    // devral / yokla / cik), bu yuzden burada yasiyor.

    fun loadAgyModels(): Job = scope.launch {
        runCatching { client.agyModels(state().settings) }
            .onSuccess { result -> update {
                val default = result.defaultModel.ifBlank { it.agy.defaultModel }
                it.copy(agy = it.agy.copy(availableModels = result.models, defaultModel = default, model = it.agy.model.ifBlank { default }))
            } }
            .onFailure { reportError("Antigravity modelleri yuklenemedi", it) }
    }

    fun loadAgyPermissionModes(): Job = scope.launch {
        runCatching { client.agyPermissionModes(state().settings) }
            .onSuccess { modes -> update { it.copy(agy = it.agy.copy(permissionModes = modes)) } }
            .onFailure { /* kip listesi kritik degil: secici gizli kalir */ }
    }

    fun enterAgy() = scope.launch {
        clearThoughts()
        val warm = prefill.warm(
            "${Backend.AGY.id}:${Backend.AGY.id}",
            state().pendingBindSessionId.takeIf { state().pendingBindBackend == Backend.AGY.id }.orEmpty(),
        )
        update { it.copy(
            backend = Backend.AGY.id, wsConnected = false,
            agy = it.agy.copy(setupPending = true, diskLoading = true),
            currentSession = if (warm != null) "Antigravity" else "",
            messagesList = warm?.messages ?: emptyList(), transcript = warm?.transcript ?: "",
            staleConversation = warm != null, running = false, awaitingApproval = false, awaitingFirstOutput = false,
        ) }
        loadAgyModels().join(); loadAgyPermissionModes(); loadWorkerDirs("")
        val current = state()
        val pendingSid = current.pendingBindSessionId.takeIf { current.pendingBindBackend == Backend.AGY.id }.orEmpty()
        if (pendingSid.isNotBlank()) update { it.copy(pendingBindBackend = "", pendingBindSessionId = "") }
        if (pendingSid.isNotBlank()) {
            val target = runCatching { client.listBackendSessions(current.settings, Backend.AGY.id) }.getOrDefault(emptyList()).firstOrNull { it.id == pendingSid }
            if (target != null) {
                update { it.copy(
                    backend = Backend.AGY.id,
                    agy = it.agy.copy(sessionId = target.id, cwd = target.cwd.ifBlank { it.agy.cwd }, model = target.model.ifBlank { it.agy.model }, setupPending = false, diskLoading = false),
                    currentSession = "Antigravity", model = "Antigravity · ${target.model}",
                ) }
                openSocket(Backend.AGY.id, target.id); startAgyPolling(); refreshConversation(false)
                syncActiveTab(Backend.AGY.id, "", target.id, generateTabTitle(Backend.AGY.id, "", target.cwd, target.model))
                return@launch
            }
        }
        update { it.copy(staleConversation = false) }
        scope.launch {
            runCatching { client.agyDiskSessions(state().settings) }
                .onSuccess { list -> update { it.copy(agy = it.agy.copy(diskSessions = list, diskLoading = false, setupPending = false)) } }
                .onFailure { update { it.copy(agy = it.agy.copy(diskLoading = false, setupPending = false)) } }
        }
    }

    fun cancelAgySetup() = update { it.copy(agy = it.agy.copy(setupPending = false)) }

    fun loadAgyDiskSessions() = scope.launch {
        update { it.copy(agy = it.agy.copy(diskLoading = true)) }
        runCatching { client.agyDiskSessions(state().settings) }
            .onSuccess { list -> update { it.copy(agy = it.agy.copy(diskSessions = list, diskLoading = false)) } }
            .onFailure { update { it.copy(agy = it.agy.copy(diskLoading = false)) } }
    }

    fun startAgy(cwd: String, model: String) = scope.launch {
        val current = state()
        val secilen = model.ifBlank { current.agy.model.ifBlank { current.agy.defaultModel } }
        runCatching { client.agyNew(current.settings, cwd, secilen, current.agy.permissionMode) }
            .onSuccess { sid ->
                update { it.copy(
                    agy = it.agy.copy(sessionId = sid, cwd = cwd, model = secilen, setupPending = false),
                    currentSession = "Antigravity", model = "Antigravity · $secilen",
                    messagesList = emptyList(), transcript = "", staleConversation = false,
                ) }
                openSocket(Backend.AGY.id, sid); startAgyPolling(); refreshConversation(false)
                syncActiveTab(Backend.AGY.id, "", sid, generateTabTitle(Backend.AGY.id, "", cwd, secilen))
                refreshSession()
            }
            .onFailure { reportError("Antigravity oturumu acilamadi", it) }
    }

    fun resumeAgy(session: AppDiskSession) = scope.launch {
        runCatching { client.agyAdopt(state().settings, session.id, session.cwd) }
            .onSuccess { sid ->
                update { it.copy(
                    backend = Backend.AGY.id,
                    agy = it.agy.copy(sessionId = sid, cwd = session.cwd, setupPending = false, diskLoading = false),
                    currentSession = "Antigravity", messagesList = emptyList(), transcript = "", staleConversation = false,
                ) }
                openSocket(Backend.AGY.id, sid); startAgyPolling(); refreshConversation(false)
                syncActiveTab(Backend.AGY.id, "", sid, generateTabTitle(Backend.AGY.id, "", session.cwd, state().agy.model))
                refreshSession()
            }
            .onFailure { reportError("Oturum devralinamadi", it) }
    }

    fun setAgyModel(id: String) {
        val sid = state().agy.sessionId
        update { it.copy(agy = it.agy.copy(model = id)) }
        if (sid.isBlank()) return
        scope.launch {
            runCatching { client.agySetModel(state().settings, sid, id) }
                .onFailure { reportError("Model degistirilemedi", it) }
        }
    }

    fun setAgyPermissionMode(mode: String) {
        val sid = state().agy.sessionId
        update { it.copy(agy = it.agy.copy(permissionMode = mode)) }
        if (sid.isBlank()) return
        scope.launch {
            runCatching { client.agySetPermissionMode(state().settings, sid, mode) }
                .onFailure { reportError("Izin kipi degistirilemedi", it) }
        }
    }

    fun startAgyPolling() {
        agyPollJob?.cancel()
        agyPollJob = scope.launch {
            while (isActive && state().backend == Backend.AGY.id) {
                if (!SohbetYoklamasi.siradakiTur { state().wsConnected }) continue
                val current = state(); val sid = current.agy.sessionId
                if (sid.isBlank()) continue
                runCatching { client.agyConversation(current.settings, sid) }.onSuccess {
                    if (state().backend == Backend.AGY.id) {
                        if (it.messages.isEmpty() && state().messagesList.isNotEmpty()) refreshSession() else applyConversation(it)
                    }
                }
            }
        }
    }

    fun exitAgy() = scope.launch {
        agyPollJob?.cancel(); agyPollJob = null; closeSocket()
        val sid = state().agy.sessionId
        update { it.copy(backend = null, agy = it.agy.copy(sessionId = "", lastSessionId = sid, cwd = ""), messagesList = emptyList(), transcript = "", running = false, awaitingApproval = false, contextTokens = 0, contextWindow = 0) }
        refreshAll()
    }

    // Codex varsayılan modeli: köprü (config.toml) söyler, uygulama SABİT tutmaz.
    // Henüz gelmediyse boş gider; köprü kendi varsayılanını uygular.
    private fun codexDefaultModel(): String = state().codex.defaultModel

}
