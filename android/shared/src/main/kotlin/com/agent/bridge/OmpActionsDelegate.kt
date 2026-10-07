package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** OMP RPC oturumunun kurulum dışındaki kullanıcı aksiyonları. */
class OmpActionsDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val reportError: suspend (String, Throwable) -> Unit,
    private val clearThoughts: () -> Unit,
    private val openSocket: (String) -> Unit,
    private val startPolling: () -> Unit,
    private val refreshConversation: (Boolean) -> Unit,
    private val syncActiveTab: (String, String, String) -> Unit,
    // Yeniden adlandırmada açık sekmenin başlığını da güncelle (opencode ile aynı).
    private val onSessionRenamed: (String, String) -> Unit = { _, _ -> },
) {
    fun cancelSetup() = update { it.copy(omp = it.omp.copy(setupPending = false)) }

    fun startSession(cwd: String, model: String) = scope.launch {
        val cleanCwd = cwd.trim()
        val cleanModel = model.ifBlank { state().omp.defaultModel.ifBlank { DEFAULT_MODEL } }
        if (cleanCwd.isBlank()) { emit("Klasör seçin"); return@launch }
        runCatching { client.ompNew(state().settings, cleanCwd, cleanModel, state().omp.permissionMode, state().omp.variant) }
            .onSuccess { sessionId ->
                clearThoughts()
                update { it.copy(
                    backend = Backend.OMP.id,
                    omp = it.omp.copy(sessionId = sessionId, cwd = cleanCwd, model = cleanModel, setupPending = false),
                    model = "OMP · ${cleanModel.substringAfterLast('/')}", currentSession = "OMP",
                    messagesList = emptyList(), transcript = "", running = false, awaitingApproval = false,
                    contextTokens = 0, contextWindow = 0,
                ) }
                openSocket(sessionId); startPolling(); refreshConversation(false); syncActiveTab(sessionId, cleanCwd, cleanModel)
            }
            .onFailure { reportError("OMP başlatılamadı", it) }
    }

    fun setModel(id: String) = scope.launch {
        val clean = id.ifBlank { state().omp.defaultModel.ifBlank { DEFAULT_MODEL } }
        update { it.copy(omp = it.omp.copy(model = clean), model = "OMP · ${clean.substringAfterLast('/')}") }
        state().omp.sessionId.takeIf { it.isNotBlank() }?.let { sid ->
            runCatching { client.ompSetModel(state().settings, sid, clean) }.onFailure { reportError("OMP modeli değiştirilemedi", it) }
        }
    }

    fun loadDiskSessions() = scope.launch {
        update { it.copy(omp = it.omp.copy(diskLoading = true)) }
        // Çekmecenin arama metni ve Arşiv sekmesi isteğe taşınır (claude/codex ile
        // aynı); yoksa Arşiv sekmesi OMP'de hep boş görünürdü.
        runCatching { client.ompDiskSessions(state().settings, state().drawerSearchQuery, state().drawerShowArchived) }
            .onSuccess { list -> update { it.copy(omp = it.omp.copy(diskSessions = list, diskLoading = false)) } }
            .onFailure { update { it.copy(omp = it.omp.copy(diskLoading = false)) }; reportError("OMP oturumları yüklenemedi", it) }
    }

    /**
     * Tur sürerken mesaj gönderir. [interrupt]=true → steer (süren turu keser),
     * false → follow_up (tur bitince ajan kendisi işler).
     *
     * Kullanıcı satırını KÖPRÜ ekliyor ve delta ile geliyor; burada state'e
     * elle mesaj yazma, yoksa satır iki kez görünür.
     */
    fun sendDuringTurn(text: String, interrupt: Boolean) = scope.launch {
        val clean = text.trim()
        val sid = state().omp.sessionId
        if (clean.isBlank() || sid.isBlank()) return@launch
        runCatching {
            if (interrupt) client.ompSteer(state().settings, sid, clean)
            else client.ompFollowUp(state().settings, sid, clean)
        }
            .onSuccess { emit(if (interrupt) "Yönlendirme gönderildi" else "Ajana bırakıldı: tur bitince işleyecek") }
            .onFailure { reportError(if (interrupt) "Yönlendirilemedi" else "Sıraya alınamadı", it) }
    }

    // Sekme işlemleri. Köprü kimliği kalıcı OMP oturum kimliğine kendisi çeviriyor,
    // yani canlı kabuk uuid'si gönderilmesi sorun değil.
    fun pin(id: String) = scope.launch {
        runCatching { client.ompPin(state().settings, id) }
            .onSuccess { loadDiskSessions() }
            .onFailure { reportError("Oturum sabitlenemedi", it) }
    }

    fun unpin(id: String) = scope.launch {
        runCatching { client.ompUnpin(state().settings, id) }
            .onSuccess { loadDiskSessions() }
            .onFailure { reportError("Oturum sabitlemesi kaldırılamadı", it) }
    }

    fun rename(id: String, title: String) = scope.launch {
        runCatching { client.ompRename(state().settings, id, title) }
            .onSuccess { onSessionRenamed(id, title); loadDiskSessions() }
            .onFailure { reportError("Oturum yeniden adlandırılamadı", it) }
    }

    fun archive(id: String) = scope.launch {
        runCatching { client.ompArchive(state().settings, id) }
            .onSuccess { loadDiskSessions() }
            .onFailure { reportError("Oturum arşivlenemedi", it) }
    }

    fun unarchive(id: String) = scope.launch {
        runCatching { client.ompUnarchive(state().settings, id) }
            .onSuccess { loadDiskSessions() }
            .onFailure { reportError("Oturum arşivden çıkarılamadı", it) }
    }

    fun resumeDiskSession(session: AppDiskSession) = scope.launch {
        runCatching { client.ompAdopt(state().settings, session.id, session.cwd) }
            .onSuccess { sessionId ->
                clearThoughts()
                val model = state().omp.model.ifBlank { state().omp.defaultModel.ifBlank { DEFAULT_MODEL } }
                update { it.copy(
                    backend = Backend.OMP.id,
                    omp = it.omp.copy(sessionId = sessionId, cwd = session.cwd, model = model, setupPending = false),
                    model = "OMP · ${model.substringAfterLast('/')}", currentSession = "OMP",
                    messagesList = emptyList(), transcript = "", running = false, awaitingApproval = false, wsConnected = false,
                    contextTokens = 0, contextWindow = 0,
                ) }
                openSocket(sessionId); startPolling(); refreshConversation(false); syncActiveTab(sessionId, session.cwd, model)
            }
            .onFailure { reportError("OMP oturumu devam ettirilemedi", it) }
    }

    fun approve(allow: Boolean, answers: List<ApprovalAnswer> = emptyList()) = scope.launch {
        val sid = state().omp.sessionId
        if (sid.isBlank()) return@launch
        runCatching { client.ompApprove(state().settings, sid, allow, answers) }
            .onSuccess { refreshConversation(false) }
            .onFailure { reportError("OMP yanıtı gönderilemedi", it) }
    }

    fun setPermissionMode(mode: String) = scope.launch {
        val clean = mode.trim().ifBlank { "yolo" }
        state().omp.sessionId.takeIf { it.isNotBlank() }?.let { sid ->
            runCatching { client.ompSetPermissionMode(state().settings, sid, clean) }.onFailure { reportError("OMP izin modu değiştirilemedi", it) }
        }
        update { it.copy(omp = it.omp.copy(permissionMode = clean)) }
    }

    fun loadPermissionModes() = scope.launch {
        runCatching { client.ompPermissionModes(state().settings) }
            .onSuccess { modes -> update { it.copy(omp = it.omp.copy(permissionModes = modes)) } }
            .onFailure { reportError("OMP izin modları yüklenemedi", it) }
    }

    fun loadInfo() = scope.launch {
        runCatching { client.ompInfo(state().settings) }
            .onSuccess { info -> update { it.copy(omp = it.omp.copy(info = info)) } }
            .onFailure { reportError("OMP envanteri alınamadı", it) }
    }

    fun setEffort(effort: String) = scope.launch {
        val clean = effort.trim()
        state().omp.sessionId.takeIf { it.isNotBlank() }?.let { sid ->
            runCatching { client.ompSetEffort(state().settings, sid, clean) }.onFailure { reportError("OMP düşünme seviyesi değiştirilemedi", it) }
        }
        update { it.copy(omp = it.omp.copy(variant = clean)) }
    }

    private companion object { const val DEFAULT_MODEL = "deepseek/deepseek-flash" }
}
