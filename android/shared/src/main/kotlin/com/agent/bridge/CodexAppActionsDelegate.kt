package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Codex App'in oturum ömrü dışındaki UI aksiyonları. */
class CodexAppActionsDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val reportError: suspend (String, Throwable) -> Unit,
    private val clearThoughts: () -> Unit,
    private val closeSocket: () -> Unit,
    private val openSocket: (String) -> Unit,
    private val startPolling: () -> Unit,
    private val refreshConversation: (Boolean) -> Unit,
    private val loadDiskSessions: () -> Unit,
    private val onSessionRenamed: (String, String) -> Unit = { _, _ -> },
) {
    fun approve(allow: Boolean, decision: String = "", scopeName: String = "") = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank()) return@launch
        val effectiveDecision = decision.ifBlank { if (allow) "accept" else "deny" }
        runCatching { client.codexAppApprove(state().settings, sid, allow, effectiveDecision, scopeName) }
            .onSuccess { refreshConversation(false) }
            .onFailure { reportError("Onay gonderilemedi", it) }
    }

    fun answerQuestions(answers: List<ApprovalAnswer>) = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank() || answers.isEmpty()) return@launch
        runCatching { client.codexAppApprove(state().settings, sid, true, "accept", "", answers) }
            .onSuccess { refreshConversation(false) }
            .onFailure { reportError("Cevap gonderilemedi", it) }
    }

    // compact() buradan kaldirildi: hicbir yerden cagrilmiyordu (ne dugme ne menu),
    // kullanicinin tek yolu sohbete "/compact" yazmakti ve o metin duz prompt olarak
    // gidiyordu. Tek gercek yol ConversationDelegate.send() icindeki yakalama.

    fun loadInfo() = scope.launch {
        runCatching { client.codexAppInfo(state().settings) }
            .onSuccess { info -> update { it.copy(codex = it.codex.copy(info = info)) } }
            .onFailure { reportError("Codex App bilgisi alinamadi", it) }
    }

    fun forkCurrent() = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank()) return@launch
        runCatching { client.codexAppFork(state().settings, sid) }
            .onSuccess { fork ->
                clearThoughts()
                closeSocket()
                update {
                    it.copy(
                        backend = "codex-app",
                        // Diff de oturuma ait: dallanan oturumun kimliği başka,
                        // taşınırsa "Değişiklikler" ÖNCEKİ oturumun dosyalarını
                        // yeni oturumunkiler diye gösterirdi ve onu yalanlayacak
                        // hiçbir şey yok (liste kendiliğinden tazelenmiyor).
                        codex = it.codex.copy(sessionId = fork.sessionId, cwd = fork.cwd, model = fork.model, plan = emptyList(), diff = null, diffLoading = false),
                        messagesList = emptyList(), transcript = "",
                        contextTokens = 0, contextWindow = 0,
                        running = false, awaitingApproval = false, wsConnected = false,
                    )
                }
                openSocket(fork.sessionId)
                startPolling()
                refreshConversation(false)
                emit("Oturum dallandirildi: ${fork.sessionId.take(8)}...")
            }
            .onFailure { reportError("Fork basarisiz", it) }
    }

    fun setSteerMode(enabled: Boolean) = update { it.copy(codex = it.codex.copy(steerMode = enabled)) }

    fun steer(text: String) = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank() || text.isBlank()) return@launch
        runCatching { client.codexAppSteer(state().settings, sid, text) }
            .onSuccess {
                update { it.copy(input = "", codex = it.codex.copy(steerMode = false)) }
                refreshConversation(false)
            }
            .onFailure { reportError("Steer basarisiz", it) }
    }

    /**
     * "Değişiklikler" listesini çeker — uzun otonom koşu bitince telefondan
     * "bu oturum neyi değiştirdi" incelemesi.
     *
     * OpencodeAppActionsDelegate.loadDiff ile AYNI sözleşme: yoklamaya binmez
     * (kullanıcı görünümü açınca çekilir), hata bildirilir ama ÖNCEKİ LİSTE
     * KORUNUR — köprü bir an cevap veremedi diye ekranı boşaltmak, "bu oturum
     * dosya değiştirmedi" yalanına dönüşürdü.
     */
    fun loadDiff() = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank()) return@launch
        update { it.copy(codex = it.codex.copy(diffLoading = true)) }
        runCatching { client.backendSessionDiff(state().settings, "codex-app", sid) }
            .onSuccess { diff -> update { it.copy(codex = it.codex.copy(diff = diff, diffLoading = false)) } }
            .onFailure {
                update { it.copy(codex = it.codex.copy(diffLoading = false)) }
                reportError("Değişiklikler alınamadı", it)
            }
    }

    fun loadCommands() = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank()) return@launch
        runCatching { client.codexAppCommands(state().settings, sid) }
            .onSuccess { commands -> update { it.copy(codex = it.codex.copy(commands = commands)) } }
            .onFailure { reportError("Komutlar yuklenemedi", it) }
    }

    fun refreshContextPercent() = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank()) return@launch
        runCatching { client.codexAppContextPercent(state().settings, sid) }
            .onSuccess { percent -> update { it.copy(codex = it.codex.copy(contextPercent = percent)) } }
    }

    fun loadPermissionModes() = scope.launch {
        runCatching { client.codexAppPermissionModes(state().settings) }
            .onSuccess { modes -> update { it.copy(codex = it.codex.copy(permissionModes = modes)) } }
    }

    fun setPermissionMode(mode: String) = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank() || mode.isBlank()) return@launch
        runCatching { client.codexAppSetPermissionMode(state().settings, sid, mode) }
            .onSuccess {
                emit("Izin modu: $mode")
                update { it.copy(codex = it.codex.copy(permissionMode = mode)) }
            }
            .onFailure { reportError("Izin modu degistirilemedi", it) }
    }

    fun loadEfforts() = scope.launch {
        runCatching { client.codexAppEfforts(state().settings) }
            .onSuccess { info -> update {
                it.copy(codex = it.codex.copy(
                    efforts = info.levels,
                    defaultEffort = info.defaultEffort,
                    effortsByModel = info.effortsByModel,
                    modelDefaultEfforts = info.modelDefaultEfforts,
                ))
            } }
    }

    fun setEffort(effort: String) = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank()) return@launch
        runCatching { client.codexAppSetEffort(state().settings, sid, effort) }
            .onSuccess { applied ->
                emit("Çaba seviyesi: ${applied.ifBlank { "varsayılan" }} (sonraki turda)")
                update { it.copy(codex = it.codex.copy(effort = applied)) }
            }
            .onFailure { reportError("Çaba seviyesi değiştirilemedi", it) }
    }

    fun archive(id: String) = scope.launch {
        runCatching { client.codexAppArchive(state().settings, id) }.onSuccess { loadDiskSessions() }
    }
    fun unarchive(id: String) = scope.launch {
        runCatching { client.codexAppUnarchive(state().settings, id) }.onSuccess { loadDiskSessions() }
    }
    fun pin(id: String) = scope.launch {
        runCatching { client.codexAppPin(state().settings, id) }.onSuccess { loadDiskSessions() }
    }
    fun unpin(id: String) = scope.launch {
        runCatching { client.codexAppUnpin(state().settings, id) }.onSuccess { loadDiskSessions() }
    }
    fun rename(id: String, title: String) = scope.launch {
        runCatching { client.codexAppRename(state().settings, id, title) }.onSuccess {
            onSessionRenamed(id, title)
            loadDiskSessions()
        }
    }

    fun loadPlanItems() = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank()) return@launch
        runCatching { client.codexAppPlanItems(state().settings, sid) }
            .onSuccess { items -> update { it.copy(codex = it.codex.copy(plan = items)) } }
    }

    // --- Oturum hedefi (/goal) ---
    // Hedefi kur ve BASLAT: set status:"active" ile app-server'da hemen bir tur baslar,
    // bu yuzden bildirim "calismaya basliyor" der (gizli tur degil, kullaniciya belli).
    fun setGoal(objective: String) = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank() || objective.isBlank()) return@launch
        runCatching { client.codexGoalSet(state().settings, sid, objective) }
            .onSuccess { goal ->
                // send() ile ayni iyimserlik: set status:active bir tur BASLATIR, ama bu
                // yol conversationDelegate.send()'ten gecmedigi icin running kendiliginden
                // isaretlenmiyordu — uygulama turu "kosuyor" saymayinca takip zinciri
                // devreye girmiyordu (canli sikayet 02.08.2026: goal turu ekranda hic
                // gorunmedi). Kopru snapshot'i zaten dogrusunu getirir; bu sadece aradaki
                // bosluk icin.
                update { it.copy(running = true, codex = it.codex.copy(goal = goal)) }
                emit("Hedef kuruldu — Codex çalışmaya başlıyor")
                refreshConversation(false)
            }
            .onFailure { reportError("Hedef kurulamadı", it) }
    }

    fun clearGoal() = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank()) return@launch
        runCatching { client.codexGoalClear(state().settings, sid) }
            .onSuccess {
                update { it.copy(codex = it.codex.copy(goal = null)) }
                emit("Hedef temizlendi")
            }
            .onFailure { reportError("Hedef temizlenemedi", it) }
    }

    /**
     * Biten hedefin bildirimi okundu: pill'i dusur. Hedef app-server'da da temizlenir —
     * yalnizca yerelde gizlemek ise yaramaz, bir sonraki snapshot hedefi geri getirir.
     * Sessiz temizleme (transcript'e "/goal temizle" satiri yazilmaz): bu bir komut
     * degil, bildirimi kapatma. Bilerek yalniz BITMIS hedefte cagrilir; calisan bir
     * hedefi kapatmakla silmek ayni sey olmamali.
     */
    fun dismissGoal() = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank()) return@launch
        // Iyimser: parmagin altinda pill hemen kaybolsun, ag beklemesin.
        update { it.copy(codex = it.codex.copy(goal = null)) }
        runCatching { client.codexGoalClear(state().settings, sid, silent = true) }
            .onFailure { reportError("Hedef kapatılamadı", it); refreshGoal() }
    }

    // Sessiz tazeleme: pill/arka plan icin state'i gunceller, bildirim atmaz.
    fun refreshGoal() = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank()) return@launch
        runCatching { client.codexGoal(state().settings, sid) }
            .onSuccess { goal -> update { it.copy(codex = it.codex.copy(goal = goal)) } }
            .onFailure { reportError("Hedef alınamadı", it) }
    }

    // "/goal" tek basina: tazele + ozeti bildirim olarak goster.
    fun showGoal() = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank()) return@launch
        runCatching { client.codexGoal(state().settings, sid) }
            .onSuccess { goal ->
                update { it.copy(codex = it.codex.copy(goal = goal)) }
                emit(goal.summaryLine())
            }
            .onFailure { reportError("Hedef alınamadı", it) }
    }

    fun respondUserInput(text: String) = scope.launch {
        val sid = state().codexAppSessionId
        if (sid.isBlank() || text.isBlank()) return@launch
        runCatching { client.codexAppRespondUserInput(state().settings, sid, text) }
            .onSuccess { refreshConversation(false) }
            .onFailure { reportError("Kullanici girdisi gonderilemedi", it) }
    }
}
