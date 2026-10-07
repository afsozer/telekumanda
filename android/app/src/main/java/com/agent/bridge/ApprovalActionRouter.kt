package com.agent.bridge

class ApprovalActionRouter(
    private val state: RemoteUiState,
    private val actions: RemoteViewModel,
) {
    private val approvalBackend: String
        get() = if (state.backend == Backend.COWORK.id) state.coworkProvider else state.backend.orEmpty()

    // Kartın ÇİZİLDİĞİ durumdaki onayın kimliği. Kullanıcı bu kartı görüp bastı;
    // arada köprüde yeni bir onay belirdiyse köprü bunu reddeder (409) ve yeni
    // isteği kullanıcı görmeden onaylamış olmayız.
    private val requestId: String
        get() = state.bekleyenOnayKimligi

    fun answerQuestions(answers: List<ApprovalAnswer>) {
        when (approvalBackend) {
            Backend.CODEX_APP.id -> actions.codexAppAnswerQuestions(answers, requestId)
            Backend.OPENCODE2_APP.id -> actions.opencodeAppAnswerQuestions(answers, requestId)
            Backend.OMP.id -> actions.ompAnswerQuestions(answers, requestId)
            else -> actions.claudeAppAnswerQuestions(answers, requestId)
        }
    }

    fun approve(allow: Boolean) {
        when (approvalBackend) {
            Backend.CODEX_APP.id -> actions.codexAppApprove(allow, requestId = requestId)
            Backend.OPENCODE2_APP.id -> actions.opencodeAppApprove(allow, requestId)
            Backend.OMP.id -> actions.ompApprove(allow, requestId)
            else -> actions.claudeAppApprove(allow, requestId)
        }
    }

    fun selectOption(option: ApprovalOption) {
        val id = option.id.lowercase()
        val consequence = option.consequence.lowercase()
        val deny = id in setOf("deny", "cancel", "reject") || consequence in setOf("deny", "cancel", "reject")
        val session = id in setOf("allowforsession", "acceptforsession", "session") || consequence == "session"
        when (approvalBackend) {
            Backend.CODEX_APP.id -> when {
                session -> actions.codexAppApproveSession(requestId)
                deny -> actions.codexAppApprove(false, if (id == "cancel" || consequence == "cancel") "cancel" else "deny", requestId = requestId)
                else -> actions.codexAppApprove(true, id.ifBlank { "accept" }, requestId = requestId)
            }
            Backend.OPENCODE2_APP.id -> actions.opencodeAppApprove(!deny, requestId)
            Backend.OMP.id -> actions.ompApprove(!deny, requestId)
            // ACP secenekleri (allow_once/allow_always/reject_once/reject_always)
            // bridge tarafinda allow bayragina indirgeniyor.
            else -> actions.claudeAppApprove(!deny, requestId)
        }
    }

    fun respondUserInput(text: String) {
        if (approvalBackend == Backend.CODEX_APP.id) actions.codexAppRespondUserInput(text)
    }

    fun allowForSession(): (() -> Unit)? =
        if (approvalBackend == Backend.CODEX_APP.id) ({ actions.codexAppApproveSession(requestId) }) else null
}
