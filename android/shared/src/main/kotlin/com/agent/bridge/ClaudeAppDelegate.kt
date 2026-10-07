package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

// Claude App (kalıcı çift-yönlü) backend oturum yaşam döngüsü — RemoteViewModel'den
// ayrılan delege (madde 11.6b). AgyDelegate'den sonraki ikinci backend delegesi.
//
// Kapsam: enterClaudeAppMode, cancelClaudeAppSetup, startClaudeAppSession,
// setClaudeAppModel, setClaudeAppPermissionMode, loadClaudeAppEfforts,
// setClaudeAppEffort, loadClaudeAppInfo, loadClaudeAppDiskSessions,
// claudeAppArchive/Unarchive/Pin/Unpin, handleClaudeAppCleared (private),
// resumeClaudeAppDiskSession, startClaudeAppPolling (private),
// exitClaudeAppMode, claudeAppApprove, claudeAppAnswerQuestion(s).
//
// Davranış değişikliği yok. Cowork coupling (claudeAppSessionId + claudeAppModel
// cowork+claude-app sağlayıcıyla paylaşılır) callback'lerle soyutlanır:
// openCoworkSocket, cancelCoworkSiblingPolling
// (exitClaudeAppMode cowork branch'inde codex/opencode poll cancel). normalizeCoworkProvider
// top-level fonksiyon olduğu için delege doğrudan çağırır (callback gerekmez).
// claudeAppPollJob delege içinde; onCleared cancelPolling() ile.
//
// Kalıp AgyDelegate ile aynı.
class ClaudeAppDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val prefill: ConversationPrefiller,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val thoughtDetails: MutableStateFlow<Map<Int, String>>,
    private val openSocket: (String) -> Unit,
    private val closeSocket: () -> Unit,
    private val openCoworkSocket: (provider: String, sessionId: String) -> Unit,
    private val cancelCoworkSiblingPolling: () -> Unit,
    private val loadWorkerDirs: (String) -> Unit,
    private val refreshConversation: (showErrors: Boolean) -> Unit,
    private val refreshSession: () -> Unit,
    private val applyConversation: (ConversationResult) -> Unit,
    private val refreshAll: () -> Unit,
    private val syncActiveTab: (String, String, String, String) -> Unit,
    private val onSessionRenamed: (String, String) -> Unit = { _, _ -> },
) {
    private var claudeAppPollJob: Job? = null

    // Model kataloğunu (liste + default) yükler. enterClaudeAppMode dışında model
    // çipi açılırken de çağrılır (loadBackendInfo): sekme geri yükleme / hub
    // hızlı-başlat gibi enterClaudeAppMode'suz akışlarda liste aksi halde boş
    // kalıp sheet skeleton'da takılıyordu (cowork'teki 7c3ab04 ile aynı desen).
    fun loadClaudeAppModels(): Job = scope.launch {
        runCatching { client.claudeAppModels(state().settings) }
            .onSuccess { result -> update {
                val def = result.defaultModel.ifBlank { it.claudeAppDefaultModel }
                it.copy(
                    claude = it.claude.copy(models = result.models, defaultModel = def, model = it.claude.model.ifBlank { def }),
                )
            } }
            .onFailure { reportError("Claude App modelleri yuklenemedi", it) }
    }

    fun enterClaudeAppMode() = scope.launch {
        thoughtDetails.value = emptyMap()
        // Sekme geçişi hedefi belliyse (pendingBind) son bilinen konuşmayı ANINDA
        // göster; ağdan bağlanma/tazeleme arkada sürer ve bayat görüntüyü değiştirir.
        // Eskiden burada koşulsuz boşaltılıyordu — her geçiş boş ekran + beklemeydi.
        val warmSid = state().pendingBindSessionId
            .takeIf { state().pendingBindBackend == "claude-app" }.orEmpty()
        val warm = prefill.warm("claude-app:claude-app", warmSid)
        update {
            it.copy(
                backend = "claude-app",
                wsConnected = false,
                claude = it.claude.copy(setupPending = true, diskLoading = true),
                currentSession = if (warm != null) "Claude App" else "",
                messagesList = warm?.messages ?: emptyList(),
                transcript = warm?.transcript ?: "",
                staleConversation = warm != null,
                running = false,
                awaitingApproval = false,
                awaitingFirstOutput = false,
            )
        }
        loadClaudeAppModels().join()
        if (state().backend != "claude-app") return@launch
        loadWorkerDirs("")
        // Yalnız TEK ATIMLIK pendingBind hedefine bağlan (sekme/bildirim/fork yazar);
        // oturum köprüde canlı olduğu için disk'ten adopt yerine doğrudan bağlanılır
        // (süren tur / bekleyen onay korunur). Eski lastSessionId bağlanması her
        // girişte mevcut sohbeti kapıyordu — yeni sekmeden ikinci bir Claude oturumu
        // açılamıyordu. Hedef yoksa oturum seçici gösterilir.
        val pendingState = state()
        val prior = pendingState.pendingBindSessionId
            .takeIf { pendingState.pendingBindBackend == "claude-app" }.orEmpty()
        if (prior.isNotBlank()) {
            update { it.copy(pendingBindBackend = "", pendingBindSessionId = "") }
            val live = runCatching { client.listBackendSessions(state().settings, "claude-app") }.getOrDefault(emptyList())
            if (state().backend != "claude-app") return@launch
            val target = live.firstOrNull { it.id == prior }
            if (target != null) {
                update {
                    it.copy(
                        claude = it.claude.copy(sessionId = target.id, cwd = target.cwd.ifBlank { it.claude.cwd }, model = target.model.ifBlank { it.claude.model }, info = null, setupPending = false, diskLoading = false),
                        currentSession = "Claude App",
                        model = "Claude App · ${target.model.ifBlank { it.claudeAppModel }}",
                    )
                }
                openSocket(target.id)
                startClaudeAppPolling()
                refreshConversation(false)
                        scope.launch {
                    runCatching { client.claudeAppDiskSessions(state().settings) }
                        .onSuccess { list -> update { it.copy(claude = it.claude.copy(diskSessions = list)) } }
                }
                syncActiveTab("claude-app", "", target.id, generateTabTitle("claude-app", "", target.cwd, target.model))
                return@launch
            }
        }
        // Otomatik devralma YOK: en yeni disk oturumu çoğu kez başka bir sekmede açık
        // olan sohbetti — sessizce ona yapışmak sekme tekilleştirmesiyle o sekmeyi
        // öldürüyordu. Liste gösterilir; kullanıcı seçer ya da yeni oturum başlatır.
        // Buraya düşüldüyse hedef oturuma bağlanılamadı (ya hedef yoktu ya öldü):
        // prefill edilen görüntü artık tazelenmeyecek, bayrağı düşür ki recordFrom
        // kilitli kalmasın. Mesajlar okunur halde bırakılır; activateTab zaten
        // "Oturum artık canlı değil" uyarısını verir.
        update { it.copy(staleConversation = false) }
        scope.launch {
            runCatching { client.claudeAppDiskSessions(state().settings) }
                .onSuccess { list -> update { it.copy(claude = it.claude.copy(diskSessions = list, diskLoading = false, setupPending = false)) } }
                .onFailure { update { it.copy(claude = it.claude.copy(diskLoading = false, setupPending = false)) } }
        }
    }

    fun cancelClaudeAppSetup() = update { it.copy(claude = it.claude.copy(setupPending = false)) }

    // Yeni oturumlar VARSAYILAN olarak auto açılır (kullanıcı isteği, 11 Ağu 2026;
    // öncesi bypassPermissions'tı). Kullanıcı çipten elle değiştirebilir. Açık bir mod
    // geçilirse (ör. hızlı-başlat sheet'inde seçilen) o mod aynen korunur.
    fun startClaudeAppSession(cwd: String, model: String, permissionMode: String = "auto") = scope.launch {
        val cleanCwd = cwd.trim()
        val cleanModel = model.ifBlank { "sonnet" }
        val cleanPermissionMode = permissionMode.trim()
        if (cleanCwd.isBlank()) {
            emit("Klasor secin")
            return@launch
        }
        runCatching { client.claudeAppNew(state().settings, cleanCwd, cleanModel, cleanPermissionMode) }
            .onSuccess { sessionId ->
                thoughtDetails.value = emptyMap()
                update {
                    it.copy(
                        backend = "claude-app",
                        claude = it.claude.copy(sessionId = sessionId, cwd = cleanCwd, model = cleanModel, permissionMode = cleanPermissionMode, info = null, setupPending = false),
                        model = "Claude App · $cleanModel",
                        currentSession = "Claude App",
                        messagesList = emptyList(),
                        contextTokens = 0, contextWindow = 0,
                        transcript = "",
                        running = false,
                        awaitingApproval = false,
                        approval = null,
                    )
                }
                openSocket(sessionId)
                startClaudeAppPolling()
                refreshConversation(false)
                        syncActiveTab("claude-app", "", sessionId, generateTabTitle("claude-app", "", cleanCwd, cleanModel))
            }
            .onFailure { reportError("Claude App baslatilamadi", it) }
    }

    fun setClaudeAppModel(id: String) = scope.launch {
        val clean = id.ifBlank { "sonnet" }
        update { it.copy(claude = it.claude.copy(model = clean), model = "Claude App · $clean") }
        // Aktif oturum varsa backend'e uygula (tur ortasıysa backend respawn'u erteler).
        val sid = state().claudeAppSessionId
        if (sid.isNotBlank()) {
            runCatching { client.claudeAppSetModel(state().settings, sid, clean) }
                .onFailure { reportError("Claude App modeli değiştirilemedi", it) }
        }
    }

    // mode: "" (default), "plan", "acceptEdits", "bypassPermissions", "auto", "dontAsk"
    fun setClaudeAppPermissionMode(mode: String) = scope.launch {
        val clean = mode.trim()
        val sid = state().claudeAppSessionId
        if (sid.isNotBlank()) {
            // Backend running iken erteler, idle iken respawn ile uygular.
            runCatching { client.claudeAppSetPermissionMode(state().settings, sid, clean) }
                .onFailure { reportError("Claude App mod değiştirilemedi", it) }
        }
        update { it.copy(claude = it.claude.copy(permissionMode = clean)) }
    }

    // Reasoning effort (çaba seviyesi) — claude. --effort flag'ı respawn ile uygulanır;
    // tur aktifse tur bitiminde geçerli olur (backend defer eder).
    fun loadClaudeAppEfforts() = scope.launch {
        runCatching { client.claudeAppEfforts(state().settings) }
            .onSuccess { efforts -> update { it.copy(claude = it.claude.copy(efforts = efforts)) } }
    }

    fun setClaudeAppEffort(effort: String) = scope.launch {
        val clean = effort.trim()
        val sid = state().claudeAppSessionId
        if (sid.isNotBlank()) {
            runCatching { client.claudeAppSetEffort(state().settings, sid, clean) }
                .onSuccess { applied -> emit("Çaba seviyesi: ${applied.ifBlank { "varsayılan" }}") }
                .onFailure { reportError("Çaba seviyesi değiştirilemedi", it) }
        }
        update { it.copy(claude = it.claude.copy(effort = clean)) }
    }

    fun loadClaudeAppInfo() = scope.launch {
        val sid = state().claudeAppSessionId
        if (sid.isBlank()) {
            emit("Claude App session yok")
            return@launch
        }
        runCatching { client.claudeAppInfo(state().settings, sid) }
            .onSuccess { info -> update { it.copy(claude = it.claude.copy(info = info)) } }
            .onFailure {
                if (it.message?.contains("404") == true)
                    emit("Oturum envanteri henüz yok — ilk komutu gönderin (köprü yeniden başladıysa yeni konuşma açın)")
                else reportError("Claude App info alınamadı", it)
            }
    }

    // List Claude App disk sessions from the bridge (seçili profilin havuzu —
    // masaüstü uygulamasının aynı profildeki oturum listesiyle birebir aynı).
    fun loadClaudeAppDiskSessions() = scope.launch {
        update { it.copy(claude = it.claude.copy(diskLoading = true)) }
        runCatching { client.claudeAppDiskSessions(state().settings, state().drawerSearchQuery, state().drawerShowArchived) }
            .onSuccess { list -> update { it.copy(claude = it.claude.copy(diskSessions = list, diskLoading = false)) } }
            .onFailure { update { it.copy(claude = it.claude.copy(diskLoading = false)) }; reportError("PC oturumları yüklenemedi", it) }
    }

    fun claudeAppArchive(id: String) = scope.launch {
        runCatching { client.claudeAppArchive(state().settings, id) }
            .onSuccess { loadClaudeAppDiskSessions() }
    }

    fun claudeAppUnarchive(id: String) = scope.launch {
        runCatching { client.claudeAppUnarchive(state().settings, id) }
            .onSuccess { loadClaudeAppDiskSessions() }
    }

    fun claudeAppPin(id: String) = scope.launch {
        runCatching { client.claudeAppPin(state().settings, id) }
            .onSuccess { loadClaudeAppDiskSessions() }
    }

    fun claudeAppUnpin(id: String) = scope.launch {
        runCatching { client.claudeAppUnpin(state().settings, id) }
            .onSuccess { loadClaudeAppDiskSessions() }
    }

    fun claudeAppRename(id: String, title: String) = scope.launch {
        runCatching { client.claudeAppRename(state().settings, id, title) }
            .onSuccess {
                onSessionRenamed(id, title)
                loadClaudeAppDiskSessions()
            }
    }

    // Oturumu PC'de terminal penceresinde `claude --resume` ile sürdür (drawer
    // 3-nokta menüsü). Masaüstü Claude app köprü oturumlarını listelemediği için
    // PC'de devam etmenin desteklenen yolu budur; bridge çift-yazar olmamak için
    // kendi kalıcı child'ını kapatır, jsonl izleyicisi olarak telefona akıtmayı sürdürür.
    fun claudeAppOpenOnPc(id: String) = scope.launch {
        runCatching { client.claudeAppOpenOnPc(state().settings, id) }
            .onSuccess { emit("PC'de terminal açıldı") }
            .onFailure { reportError("PC'de açılamadı", it) }
    }

    fun handleClaudeAppCleared(json: JSONObject) {
        val newId = json.optString("sessionId", "").takeIf { it.isNotBlank() } ?: return
        thoughtDetails.value = emptyMap()
        update {
            it.copy(
                claude = it.claude.copy(sessionId = newId),
                messagesList = emptyList(),
                contextTokens = 0, contextWindow = 0,
                transcript = "",
                running = false,
                awaitingApproval = false,
                approval = null,
                wsConnected = false,
            )
        }
        openSocket(newId)
        startClaudeAppPolling()
        refreshConversation(false)
    }

    // Adopt an on-disk claude-app session and continue it.
    fun resumeClaudeAppDiskSession(session: ClaudeDiskSession) = scope.launch {
        runCatching { client.claudeAppAdopt(state().settings, session.id, session.cwd) }
            .onSuccess { sessionId ->
                thoughtDetails.value = emptyMap()
                update {
                    it.copy(
                        backend = "claude-app",
                        claude = it.claude.copy(sessionId = sessionId, cwd = session.cwd, model = it.claude.model.ifBlank { "sonnet" }, permissionMode = "auto", info = null, setupPending = false),
                        model = "Claude App · ${it.claudeAppModel.ifBlank { "sonnet" }}",
                        currentSession = "Claude App",
                        messagesList = emptyList(),
                        contextTokens = 0, contextWindow = 0,
                        transcript = "",
                        running = false,
                        awaitingApproval = false,
                        wsConnected = false,
                    )
                }
                openSocket(sessionId)
                startClaudeAppPolling()
                refreshConversation(false)
                        syncActiveTab("claude-app", "", sessionId, generateTabTitle("claude-app", "", session.cwd, state().claudeAppModel))
            }
            .onFailure { reportError("Oturum devam ettirilemedi", it) }
    }

    // Cowork (claude-app sağlayıcısı) da aynı polling'i kullanır — internal görünür.
    fun startClaudeAppPolling() {
        claudeAppPollJob?.cancel()
        claudeAppPollJob = scope.launch {
            while (isActive && (state().backend == "claude-app" || (state().backend == "cowork" && normalizeCoworkProvider(state().coworkProvider) == "claude-app"))) {
                delay(2_500)
                val sid = state().claudeAppSessionId
                if (sid.isBlank()) continue
                val limit = state().messagePageSize.coerceAtLeast(100)
                runCatching { client.claudeAppConversation(state().settings, sid, limit = limit) }
                    .onSuccess {
                        if (state().backend == "claude-app" || (state().backend == "cowork" && normalizeCoworkProvider(state().coworkProvider) == "claude-app")) {
                            if (it.messages.isEmpty() && state().messagesList.isNotEmpty()) refreshSession()
                            else applyConversation(it)
                        }
                    }
            }
        }
    }

    fun exitClaudeAppMode(clearLastCowork: Boolean = false) = scope.launch {
        claudeAppPollJob?.cancel()
        claudeAppPollJob = null
        if (state().backend == "cowork") {
            cancelCoworkSiblingPolling()
        }
        // Backend değiştirmek oturumu SONLANDIRMAZ: süreç köprüde canlı kalır (idle timeout'a
        // kadar) ki geri dönünce kaldığımız yerden devam edelim. Bu yüzden interrupt/stop YOK;
        // sadece hangi oturumdaydık onu hatırlarız (geri dönüşte yeniden bağlanmak için).
        val coworkProv = normalizeCoworkProvider(state().coworkProvider)
        val sid = if (state().backend == "cowork") {
            when (coworkProv) {
                "codex-app" -> state().codexAppSessionId
                "opencode2-app" -> state().opencodeAppSessionId
                "omp" -> state().ompSessionId
                else -> state().claudeAppSessionId
            }
        } else state().claudeAppSessionId
        val wasCowork = state().backend == "cowork"
        closeSocket()
        update {
            it.copy(
                backend = null,
                claude = it.claude.copy(sessionId = "", lastSessionId = if (wasCowork) it.claude.lastSessionId else sid, cwd = "", diskSessions = emptyList(), info = null),
                codex = if (wasCowork) it.codex.copy(sessionId = "", cwd = "") else it.codex,
                opencode = if (wasCowork) it.opencode.copy(sessionId = "", cwd = "") else it.opencode,
                // Cowork ve claude-app son-oturumu ayrı hatırlanır; birbirine bağlanmasın.
                lastCoworkSessionId = if (wasCowork && clearLastCowork) "" else if (wasCowork) sid else it.lastCoworkSessionId,
                lastCoworkProvider = if (wasCowork && clearLastCowork) "" else if (wasCowork) coworkProv else it.lastCoworkProvider,
                cowork = if (wasCowork) it.cowork.copy(activeProjectPath = "") else it.cowork,
                messagesList = emptyList(),
                contextTokens = 0, contextWindow = 0,
                transcript = "",
                running = false,
                awaitingApproval = false,
            )
        }
        refreshAll()
    }

    // ── Onay API'si ─────────────────────────────────────────────────────────
    fun claudeAppApprove(allow: Boolean) = scope.launch {
        val sid = state().claudeAppSessionId
        if (sid.isBlank()) return@launch
        runCatching { client.claudeAppApprove(state().settings, sid, allow) }
            .onSuccess { refreshConversation(false) }
            .onFailure { reportError("Onay gonderilemedi", it) }
    }

    fun claudeAppAnswerQuestion(question: ApprovalQuestion, option: ApprovalOption) = scope.launch {
        claudeAppAnswerQuestions(listOf(ApprovalAnswer(question.id, option.id, option.label)))
    }

    fun claudeAppAnswerQuestions(answers: List<ApprovalAnswer>) = scope.launch {
        val sid = state().claudeAppSessionId
        if (sid.isBlank()) return@launch
        if (answers.isEmpty()) return@launch
        runCatching { client.claudeAppApprove(state().settings, sid, true, answers) }
            .onSuccess { refreshConversation(false) }
            .onFailure { reportError("Cevap gonderilemedi", it) }
    }

    // onCleared'den çağrılır — polling işini ViewModel yok edilirken iptal eder.
    fun cancelPolling() {
        claudeAppPollJob?.cancel()
        claudeAppPollJob = null
    }

    private suspend fun reportError(prefix: String, throwable: Throwable) {
        emit("$prefix: ${throwable.message ?: "unknown error"}")
    }
}
