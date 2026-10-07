package com.agent.bridge

class ApprovalActionRouter(
    private val state: RemoteUiState,
    private val actions: RemoteViewModel,
) {
    private val approvalBackend: String
        get() = if (state.backend == Backend.COWORK.id) state.coworkProvider else state.backend.orEmpty()

    fun answerQuestions(answers: List<ApprovalAnswer>) {
        when (approvalBackend) {
            Backend.CODEX_APP.id -> actions.codexAppAnswerQuestions(answers)
            Backend.OPENCODE2_APP.id -> actions.opencodeAppAnswerQuestions(answers)
            Backend.OMP.id -> actions.ompAnswerQuestions(answers)
            else -> actions.claudeAppAnswerQuestions(answers)
        }
    }

    fun approve(allow: Boolean) {
        when (approvalBackend) {
            Backend.CODEX_APP.id -> actions.codexAppApprove(allow)
            Backend.OPENCODE2_APP.id -> actions.opencodeAppApprove(allow)
            Backend.OMP.id -> actions.ompApprove(allow)
            else -> actions.claudeAppApprove(allow)
        }
    }

    fun selectOption(option: ApprovalOption) {
        val id = option.id.lowercase()
        val consequence = option.consequence.lowercase()
        val deny = id in setOf("deny", "cancel", "reject") || consequence in setOf("deny", "cancel", "reject")
        val session = id in setOf("allowforsession", "acceptforsession", "session") || consequence == "session"
        when (approvalBackend) {
            Backend.CODEX_APP.id -> when {
                session -> actions.codexAppApproveSession()
                deny -> actions.codexAppApprove(false, if (id == "cancel" || consequence == "cancel") "cancel" else "deny")
                else -> actions.codexAppApprove(true, id.ifBlank { "accept" })
            }
            Backend.OPENCODE2_APP.id -> actions.opencodeAppApprove(!deny)
            Backend.OMP.id -> actions.ompApprove(!deny)
            // ACP secenekleri (allow_once/allow_always/reject_once/reject_always)
            // bridge tarafinda allow bayragina indirgeniyor.
            else -> actions.claudeAppApprove(!deny)
        }
    }

    fun respondUserInput(text: String) {
        if (approvalBackend == Backend.CODEX_APP.id) actions.codexAppRespondUserInput(text)
    }

    fun allowForSession(): (() -> Unit)? =
        if (approvalBackend == Backend.CODEX_APP.id) ({ actions.codexAppApproveSession() }) else null
}
