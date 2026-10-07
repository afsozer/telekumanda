package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Codex/OpenCode provider oturumlarının giriş, çıkış ve polling yaşam döngüsü. */
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
    private val refreshRunPodStatus: (Boolean) -> Unit,
    private val syncRunPodStatus: () -> Unit,
    private val syncActiveTab: (String, String, String, String) -> Unit,
) {
    private var codexPollJob: Job? = null
    private var opencodePollJob: Job? = null
    private var ompPollJob: Job? = null

    fun cancelCoworkSiblingPolling() {
        codexPollJob?.cancel(); codexPollJob = null
        opencodePollJob?.cancel(); opencodePollJob = null
        ompPollJob?.cancel(); ompPollJob = null
    }

    fun cancelAllPolling() {
        cancelCoworkSiblingPolling()
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
                delay(POLL_MS); val current = state(); val sid = current.codex.sessionId
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

    // Model kataloğunu yükler — enterOpencode dışında model çipinden de (loadBackendInfo).
    fun loadOpencodeAppModels(backendId: String = Backend.OPENCODE2_APP.id): Job = scope.launch {
        runCatching { client.opencodeAppModels(state().settings, backendId) }
            .onSuccess { models -> update { it.withOpencodeFamily(backendId) { f -> f.copy(availableModels = models) } } }
            .onFailure { reportError("OpenCode modelleri yuklenemedi", it) }
    }

    fun enterOpencode(backendId: String = Backend.OPENCODE2_APP.id) = scope.launch {
        // v1 sokuldu: tek etiket kaldi.
        val etiket = "OpenCode"
        val kisa = "OpenCode"
        clearThoughts()
        val warm = prefill.warm(
            "$backendId:$backendId",
            state().pendingBindSessionId.takeIf { state().pendingBindBackend == backendId }.orEmpty(),
        )
        update { it.withOpencodeFamily(backendId) { f -> f.copy(setupPending = true, diskLoading = true) }.copy(
            backend = backendId, wsConnected = false, 
            currentSession = if (warm != null) etiket else "",
            messagesList = warm?.messages ?: emptyList(),
            transcript = warm?.transcript ?: "",
            staleConversation = warm != null,
            running = false, awaitingApproval = false, awaitingFirstOutput = false,
        ) }
        loadOpencodeAppModels(backendId).join()
        refreshRunPodStatus(false); loadWorkerDirs("")
        val current = state()
        // codex/claude ile aynı kural: yalnız tek atımlık pendingBind hedefine bağlan;
        // hedef yoksa oturum seçici (aşağıda disk listesi yüklenir, otomatik devralma yok).
        val pendingSid = current.pendingBindSessionId
            .takeIf { current.pendingBindBackend == backendId }.orEmpty()
        if (pendingSid.isNotBlank()) update { it.copy(pendingBindBackend = "", pendingBindSessionId = "") }
        if (pendingSid.isNotBlank()) {
            val target = runCatching { client.listBackendSessions(current.settings, backendId) }.getOrDefault(emptyList()).firstOrNull { it.id == pendingSid }
            if (target != null) {
                update {
                    val targetModel = target.model.ifBlank { it.opencodeFamily(backendId).model }
                    it.withOpencodeFamily(backendId) { f ->
                        f.panoSifirla().copy(sessionId = target.id, cwd = target.cwd.ifBlank { f.cwd }, model = targetModel, setupPending = false, diskLoading = false)
                    }.copy(
                        backend = backendId,
                        currentSession = etiket, model = "$kisa · ${targetModel.substringAfterLast('/')}",
                    )
                }
                openSocket(backendId, target.id); startOpencodePolling(backendId); refreshConversation(false)
                syncActiveTab(backendId, "", target.id, generateTabTitle(backendId, "", target.cwd, target.model))
                return@launch
            }
        }
        // Hedefe bağlanılamadı: prefill tazelenmeyecek, bayrağı düşür (claude ile aynı).
        update { it.copy(staleConversation = false) }
        scope.launch {
            // Otomatik devralma YOK: en yeni disk oturumu çoğu kez başka bir sekmede
            // açık olan sohbetti — sessizce ona yapışmak sekme tekilleştirmesiyle o
            // sekmeyi öldürüyordu. Liste gösterilir, kullanıcı seçer ya da yeni başlatır.
            runCatching { client.opencodeAppDiskSessions(state().settings, backendId) }
                .onSuccess { list -> update { it.withOpencodeFamily(backendId) { f -> f.copy(diskSessions = list, diskLoading = false, setupPending = false) } } }
                .onFailure { update { it.withOpencodeFamily(backendId) { f -> f.copy(diskLoading = false, setupPending = false) } } }
        }
    }

    fun startOpencodePolling(backendId: String = Backend.OPENCODE2_APP.id) {
        opencodePollJob?.cancel()
        opencodePollJob = scope.launch {
            var tick = 0
            fun aktif(): Boolean {
                val st = state()
                return st.backend == backendId ||
                    (st.backend == Backend.COWORK.id && st.cowork.provider == backendId)
            }
            while (isActive && aktif()) {
                delay(POLL_MS); tick++
                val current = state(); val sid = current.opencodeFamily(backendId).sessionId
                if (sid.isNotBlank()) runCatching { client.opencodeAppConversation(current.settings, sid, backendId) }.onSuccess {
                    if (aktif()) {
                        if (it.messages.isEmpty() && state().messagesList.isNotEmpty()) refreshSession() else applyConversation(it)
                    }
                }
                // RunPod yalniz v1 tarafinda kurulu; v2'de o uc yok.
                // RunPod durumu 6 turda bir: pod denetimi OpenCode sekmesinde.
                if (tick % 6 == 0) syncRunPodStatus()
            }
        }
    }

    fun exitOpencode(backendId: String = Backend.OPENCODE2_APP.id) = scope.launch {
        opencodePollJob?.cancel(); opencodePollJob = null; closeSocket()
        val sid = state().opencodeFamily(backendId).sessionId
        update {
            it.withOpencodeFamily(backendId) { f -> f.panoSifirla().copy(sessionId = "", lastSessionId = sid, cwd = "") }
                .copy(backend = null, messagesList = emptyList(), transcript = "", running = false, awaitingApproval = false, contextTokens = 0, contextWindow = 0)
        }
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
                delay(POLL_MS)
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


    // Codex varsayılan modeli: köprü (config.toml) söyler, uygulama SABİT tutmaz.
    // Henüz gelmediyse boş gider; köprü kendi varsayılanını uygular.
    private fun codexDefaultModel(): String = state().codex.defaultModel

    private companion object {
        const val POLL_MS = 2_500L
    }
}
