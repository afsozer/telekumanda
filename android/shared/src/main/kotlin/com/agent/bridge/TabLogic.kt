package com.agent.bridge

// TabsDelegate.kt'nin saf (prefs-bağımsız) top-level üyeleri. TabsDelegate
// sınıfının kendisi Faz 1.4'te KeyValueStore arayüzüne çekilip taşınacak; şimdilik
// Android app modülünde kalıyor.

// Tam sekme listesi kalıcılıkta korunur. UI ve sekme kaynaklı ağ işleri yalnız
// etkin köprünün dilimini kullanır; böylece başka köprüye ait sessionId yeni
// köprüde hiçbir zaman yoklanmaz.
val RemoteUiState.visibleTabs: List<AppTab>
    get() = openTabs.filter {
        it.bridgeProfileId == activeBridgeProfileId &&
            (it.backend.isBlank() || supportsEditionBackend(it.backend))
    }

@Suppress("UNUSED_PARAMETER")
fun generateTabTitle(backend: String, provider: String, cwd: String, model: String): String {
    val folder = cwd.substringAfterLast('/').substringAfterLast('\\')
    val shortFolder = if (folder.isNotBlank()) folder else {
        when (backend) {
            "claude-app" -> "Claude"
            "codex-app" -> "Codex"
            "cowork" -> "Cowork"
            "opencode2-app" -> "OpenCode"
            "omp" -> "OMP"
            "agy" -> "Antigravity"
            else -> backend
        }
    }
    // Model adı (opus/sonnet/...) gösterilmez — başlık yalnızca repo adıdır.
    // Model adı header ve çip satırındaki model çipinde zaten görünür.
    val raw = shortFolder
    return if (raw.length > 20) raw.take(19) + "…" else raw
}

fun computeTabStatuses(
    tabs: List<AppTab>,
    activeTabId: String,
    liveByBackend: Map<String, List<LiveSession>?>,
    prev: Map<String, TabStatus>,
): Map<String, TabStatus> {
    val result = mutableMapOf<String, TabStatus>()
    for (tab in tabs) {
        if (tab.sessionId.isEmpty()) continue

        val backendKey = if (tab.backend == "cowork") tab.provider else tab.backend
        // liveTitle kilidi sessionId'ye bağlıdır: prev'in başlığını YALNIZ oturum
        // aynıysa devral. Aksi halde (sekme başka oturuma geçmiş) eski — hatta
        // silinmiş — oturumun başlığı, yeni oturum bir tick geç canlı gelene
        // kadar taşınıp sonra kilitlenirdi ("proje · başlık" vakası).
        fun carriedTitle(prevStatus: TabStatus): String =
            if (prevStatus.sessionId == tab.sessionId && tab.sessionId.isNotEmpty()) prevStatus.liveTitle else ""
        fun carriedThreadId(prevStatus: TabStatus): String =
            if (prevStatus.sessionId == tab.sessionId && tab.sessionId.isNotEmpty()) prevStatus.threadId else ""

        if (!liveByBackend.containsKey(backendKey)) {
            val prevStatus = prev[tab.id]
            if (prevStatus != null) {
                result[tab.id] = prevStatus.copy(
                    sessionId = tab.sessionId,
                    liveTitle = carriedTitle(prevStatus),
                    threadId = carriedThreadId(prevStatus),
                )
            }
            continue
        }

        val sessions = liveByBackend[backendKey]
        if (sessions == null) {
            val prevStatus = prev[tab.id]
            if (prevStatus != null) {
                result[tab.id] = prevStatus.copy(
                    sessionId = tab.sessionId,
                    liveTitle = carriedTitle(prevStatus),
                    threadId = carriedThreadId(prevStatus),
                )
            }
            continue
        }

        val matchingSession = sessions.find { it.id == tab.sessionId }
        val prevStatus = prev[tab.id] ?: TabStatus(live = false)

        if (matchingSession == null) {
            result[tab.id] = TabStatus(
                live = false,
                finishedUnseen = if (tab.id == activeTabId) false else prevStatus.finishedUnseen,
                cwd = prevStatus.cwd,
                lastText = prevStatus.lastText,
                liveTitle = carriedTitle(prevStatus),
                threadId = carriedThreadId(prevStatus),
                sessionId = tab.sessionId
            )
        } else {
            val isRunning = matchingSession.status == "running"
            val isAwaitingApproval = matchingSession.awaitingApproval

            val isFinishedUnseen = if (tab.id == activeTabId) {
                false
            } else {
                val transitionedToFinished = (prevStatus.running || prevStatus.awaitingApproval) && (!isRunning && !isAwaitingApproval)
                transitionedToFinished || prevStatus.finishedUnseen
            }

            // Canlı oturum başlığı "ilk prompt sonrası bir kez" kuralıyla kilitlenir,
            // ANCAK kilit yalnızca AYNI oturum içinde geçerlidir. syncActiveTab tab.id'yi
            // koruyup sessionId'yi değiştirebildiği için (çekmeceden başka oturum açınca)
            // prev.sessionId != tab.sessionId ise kilit sıfırlanır — yeni oturumun başlığı
            // yazılır. Aksi halde ilk oturumun başlığı sonsuza dek ekranda kalırdı.
            val sameSession = prevStatus.sessionId == tab.sessionId && tab.sessionId.isNotEmpty()
            val liveTitle = if (sameSession && prevStatus.liveTitle.isNotBlank()) prevStatus.liveTitle
                else matchingSession.title

            result[tab.id] = TabStatus(
                live = true,
                running = isRunning,
                awaitingApproval = isAwaitingApproval,
                finishedUnseen = isFinishedUnseen,
                cwd = matchingSession.cwd,
                lastText = matchingSession.lastText,
                liveTitle = liveTitle,
                threadId = matchingSession.threadId,
                sessionId = tab.sessionId
            )
        }
    }
    return result
}

// Sohbet kökünde geri jestinin ne yapacağı. Sekmeler tek tek tüketilir:
// boş sekme sessizce kapanır (yan sekmeye geçilir), dolu sekme çarpı tuşuyla
// aynı onayı ister, elde yalnız tek boş sekme kaldığında sistem devralır
// (uygulama kapanır).
enum class ChatBackAction { CLOSE_EMPTY_TAB, CONFIRM_CLOSE_TAB, SYSTEM_BACK }

// hasActiveBackend: sekmede sessionId henüz yazılmamış olsa bile aktif bir
// backend varsa sekme DOLU sayılır — yeni başlamış oturum syncActiveTab'den
// önce onaysız kapanmasın.
fun chatBackAction(tabs: List<AppTab>, activeTabId: String, hasActiveBackend: Boolean): ChatBackAction {
    val active = tabs.find { it.id == activeTabId } ?: return ChatBackAction.SYSTEM_BACK
    return when {
        active.sessionId.isNotEmpty() || hasActiveBackend -> ChatBackAction.CONFIRM_CLOSE_TAB
        tabs.size > 1 -> ChatBackAction.CLOSE_EMPTY_TAB
        else -> ChatBackAction.SYSTEM_BACK
    }
}

enum class TabDotState {
    APPROVAL, RUNNING, FINISHED, DEAD, NORMAL
}

fun tabDotState(status: TabStatus?): TabDotState {
    if (status == null) return TabDotState.NORMAL
    return when {
        status.awaitingApproval -> TabDotState.APPROVAL
        status.running -> TabDotState.RUNNING
        status.finishedUnseen -> TabDotState.FINISHED
        !status.live -> TabDotState.DEAD
        else -> TabDotState.NORMAL
    }
}
