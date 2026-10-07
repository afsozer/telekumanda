package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Antigravity (agy) backend oturum yaşam döngüsü — RemoteViewModel'den ayrılan delege
// (madde 11.6). En büyük üç backend delegesinin ilki.
//
// Kapsam: enterAgyMode, cancelAgySetup, showAgySessionPicker, startAgySession,
// setAgyModel, loadAgyDiskSessions, resumeAgyDiskSession, startAgyPolling (private),
// exitAgyMode, agyOpenAntigravity. Agy-only openAgySocket de buraya taşındı (tek
// satırlık streamManager.open sarmalayıcısı).
//
// Davranış değişikliği yok. applyConversation/refreshSession/refreshConversation/
// loadWorkerDirs/refreshAll shared altyapı olduğundan ViewModel'de kalır ve callback
// olarak delegeye verilir (roadmap: sendPrompt ve refreshConversation VM'de kalır).
// agyPollJob delege içinde yönetilir; onCleared cancelPolling() ile yapılır.
// _thoughtDetails (StateFlow) Android'siz — doğrudan geçilir.
//
// Kalıp önceki delegelerle aynı: BridgeClient + CoroutineScope + state okuyucu/updater
// + mesaj callback'i.
class AgyDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val thoughtDetails: MutableStateFlow<Map<Int, String>>,
    private val openSocket: (String) -> Unit,
    private val closeSocket: () -> Unit,
    private val loadWorkerDirs: (String) -> Unit,
    private val refreshConversation: (showErrors: Boolean) -> Unit,
    private val refreshSession: () -> Unit,
    private val applyConversation: (ConversationResult) -> Unit,
    private val refreshAll: () -> Unit,
    private val syncActiveTab: (String, String, String, String) -> Unit,
    private val displayName: String = "Antigravity CLI",
) {
    private var agyPollJob: Job? = null

    fun enterAgyMode() = scope.launch {
        thoughtDetails.value = emptyMap()
        update {
            it.copy(
                backend = "agy",
                wsConnected = false,
                agy = it.agy.copy(setupPending = true, diskLoading = true),
                currentSession = "",
                messagesList = emptyList(),
                contextTokens = 0, contextWindow = 0,
                transcript = "",
                running = false,
                awaitingApproval = false,
                awaitingFirstOutput = false,
            )
        }
        runCatching { client.agyModels(state().settings) }
            .onSuccess { models -> update { it.copy(agy = it.agy.copy(models = models)) } }
            .onFailure { reportError("$displayName modelleri yuklenemedi", it) }
        if (state().backend != "agy") return@launch
        loadWorkerDirs(state().agyCwd.ifBlank { "" })

        // Yalnız TEK ATIMLIK pendingBind hedefine bağlan (sekmeye dokunma yazar);
        // `lastAgySessionId` ARTIK otomatik bağlanma kaynağı DEĞİL. Eskiden burada
        // o okunuyordu ve "Yeni oturum"da Antigravity çipine dokunmak — daha Başlat'a
        // basmadan — en son agy oturumunu açıyordu (kullanıcı bildirdi 25.08.2026).
        // Diğer dört backend bu kurala zaten geçmişti (claude/codex/opencode/omp).
        val pendingState = state()
        val prior = pendingState.pendingBindSessionId
            .takeIf { pendingState.pendingBindBackend == "agy" }.orEmpty()
        if (prior.isNotBlank()) {
            update { it.copy(pendingBindBackend = "", pendingBindSessionId = "") }
            val live = runCatching { client.listBackendSessions(state().settings, "agy") }.getOrDefault(emptyList())
            if (state().backend != "agy") return@launch
            val target = live.firstOrNull { it.id == prior }
            if (target != null) {
                update {
                    it.copy(
                        backend = "agy",
                        agy = it.agy.copy(
                            sessionId = target.id,
                            cwd = target.cwd.ifBlank { it.agy.cwd },
                            model = target.model.ifBlank { it.agy.model },
                            setupPending = false,
                            diskLoading = false,
                        ),
                        currentSession = displayName,
                        model = displayName + if (target.model.isNotBlank()) " · ${target.model}" else "",
                    )
                }
                openSocket(target.id)
                startAgyPolling()
                refreshConversation(false)
                syncActiveTab("agy", "", target.id, generateTabTitle("agy", "", target.cwd, target.model))
                return@launch
            }
        }

        // Otomatik devralma YOK (diğer backend'lerle aynı gerekçe): en yeni disk
        // oturumu çoğu kez başka bir sekmede açık olan sohbetti; sessizce ona
        // yapışmak hem sekme tekilleştirmesiyle o sekmeyi öldürüyor hem de backend
        // seçimini "son oturuma dön"e çeviriyordu. Liste yüklenir, setupPending
        // açık kalır — kullanıcı ya listeden seçer ya Başlat'a basar.
        scope.launch {
            runCatching { client.agyDiskSessions(state().settings) }
                .onSuccess { list -> update { it.copy(agy = it.agy.copy(diskSessions = list, diskLoading = false)) } }
                .onFailure { update { it.copy(agy = it.agy.copy(diskLoading = false)) } }
        }
    }

    fun cancelAgySetup() = update { it.copy(agy = it.agy.copy(setupPending = false)) }

    fun showAgySessionPicker() {
        update { it.copy(agy = it.agy.copy(setupPending = true)) }
        scope.launch {
            runCatching { client.agyModels(state().settings) }
                .onSuccess { models -> update { it.copy(agy = it.agy.copy(models = models)) } }
                .onFailure { reportError("$displayName modelleri yuklenemedi", it) }
            loadWorkerDirs(state().agyCwd.ifBlank { "" })
            loadAgyDiskSessions()
        }
    }

    fun startAgySession(cwd: String, model: String) = scope.launch {
        val cleanCwd = cwd.trim()
        val cleanModel = model.trim()
        if (cleanCwd.isBlank()) {
            emit("Klasor secin")
            return@launch
        }
        runCatching { client.agyNew(state().settings, cleanCwd, cleanModel) }
            .onSuccess { sessionId ->
                thoughtDetails.value = emptyMap()
                update {
                    it.copy(
                        backend = "agy",
                        agy = it.agy.copy(sessionId = sessionId, cwd = cleanCwd, model = cleanModel, setupPending = false),
                        model = displayName + if (cleanModel.isNotBlank()) " · $cleanModel" else "",
                        currentSession = displayName,
                        messagesList = emptyList(),
                        contextTokens = 0, contextWindow = 0,
                        transcript = "",
                        running = false,
                        awaitingApproval = false,
                    )
                }
                openSocket(sessionId)
                startAgyPolling()
                refreshConversation(false)
                syncActiveTab("agy", "", sessionId, generateTabTitle("agy", "", cleanCwd, cleanModel))
            }
            .onFailure { reportError("$displayName baslatilamadi", it) }
    }

    fun setAgyModel(id: String) {
        val clean = id.trim()
        update { it.copy(agy = it.agy.copy(model = clean), model = displayName + if (clean.isNotBlank()) " · $clean" else "") }
    }

    fun loadAgyDiskSessions() = scope.launch {
        update { it.copy(agy = it.agy.copy(diskLoading = true)) }
        runCatching { client.agyDiskSessions(state().settings) }
            .onSuccess { list -> update { it.copy(agy = it.agy.copy(diskSessions = list, diskLoading = false)) } }
            .onFailure { update { it.copy(agy = it.agy.copy(diskLoading = false)) }; reportError("PC oturumlari yuklenemedi", it) }
    }

    fun resumeAgyDiskSession(session: AgyDiskSession) = scope.launch {
        runCatching { client.agyAdopt(state().settings, session.id, session.cwd, session.source, session.title, state().agyModel) }
            .onSuccess { sessionId ->
                thoughtDetails.value = emptyMap()
                update {
                    it.copy(
                        backend = "agy",
                        agy = it.agy.copy(sessionId = sessionId, cwd = session.cwd, setupPending = false),
                        model = displayName + if (it.agy.model.isNotBlank()) " · ${it.agy.model}" else "",
                        currentSession = displayName,
                        messagesList = emptyList(),
                        contextTokens = 0, contextWindow = 0,
                        transcript = "",
                        running = false,
                        awaitingApproval = false,
                        wsConnected = false,
                    )
                }
                openSocket(sessionId)
                startAgyPolling()
                refreshConversation(false)
                syncActiveTab("agy", "", sessionId, generateTabTitle("agy", "", session.cwd, state().agyModel))
            }
            .onFailure { reportError("Oturum devam ettirilemedi", it) }
    }

    private fun startAgyPolling() {
        agyPollJob?.cancel()
        agyPollJob = scope.launch {
            while (isActive && state().backend == "agy") {
                delay(2_500)
                val sid = state().agySessionId
                if (sid.isBlank()) continue
                runCatching { client.agyConversation(state().settings, sid) }
                    .onSuccess {
                        if (state().backend == "agy") {
                            if (it.messages.isEmpty() && state().messagesList.isNotEmpty()) refreshSession()
                            else applyConversation(it)
                        }
                    }
            }
        }
    }

    fun exitAgyMode() = scope.launch {
        agyPollJob?.cancel()
        agyPollJob = null
        closeSocket()
        val sid = state().agySessionId
        update {
            it.copy(
                backend = null,
                agy = it.agy.copy(sessionId = "", cwd = ""),
                lastAgySessionId = sid,
                messagesList = emptyList(),
                contextTokens = 0, contextWindow = 0,
                transcript = "",
                running = false,
                awaitingApproval = false,
            )
        }
        refreshAll()
    }

    fun agyOpenAntigravity() = scope.launch {
        update { it.copy(agy = it.agy.copy(opening = true)) }
        runCatching { client.agyOpenAntigravity(state().settings) }
            .onSuccess { emit("Antigravity IDE açıldı") }
            .onFailure { reportError("Antigravity açılamadı", it) }
        update { it.copy(agy = it.agy.copy(opening = false)) }
    }

    // onCleared'den çağrılır — polling işini ViewModel yok edilirken iptal eder.
    fun cancelPolling() {
        agyPollJob?.cancel()
        agyPollJob = null
    }

    private suspend fun reportError(prefix: String, throwable: Throwable) {
        emit("$prefix: ${throwable.message ?: "unknown error"}")
    }
}
