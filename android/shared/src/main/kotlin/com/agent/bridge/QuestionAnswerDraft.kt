package com.agent.bridge

/**
 * Bir AskUserQuestion/question isteğinin cevaplarını kartlar arasında biriktirir.
 * Backend'e yalnız bütün sorular cevaplandıktan sonra gönderilir.
 *
 * Soru başına cevap bir LİSTE: `multiple` işaretli sorularda ajan birden fazla
 * seçenek bekler (opencode'un reply şeması soru başına etiket dizisi taşır).
 * Tek seçimli soruda liste hep tek elemanlıdır.
 */
data class QuestionAnswerDraft(
    private val answersByQuestion: Map<String, List<ApprovalAnswer>> = emptyMap(),
) {
    /**
     * Tek seçimlide yeni seçim öncekinin yerine geçer; çok seçimlide seçenek
     * listeye eklenir, aynı seçeneğe ikinci dokunuş onu çıkarır. Liste boşalınca
     * soru "cevapsız"a döner — yoksa boş cevap gönderilir.
     */
    fun select(question: ApprovalQuestion, option: ApprovalOption): QuestionAnswerDraft {
        val current = answersByQuestion[question.id].orEmpty()
        val next = when {
            !question.multiple -> listOf(ApprovalAnswer(question.id, option.id, option.label))
            current.any { it.optionId == option.id } -> current.filterNot { it.optionId == option.id }
            else -> current + ApprovalAnswer(question.id, option.id, option.label)
        }
        return copy(
            answersByQuestion =
                if (next.isEmpty()) answersByQuestion - question.id
                else answersByQuestion + (question.id to next),
        )
    }

    fun selectedOptionId(questionId: String): String? =
        answersByQuestion[questionId]?.firstOrNull()?.optionId

    fun selectedOptionIds(questionId: String): Set<String> =
        answersByQuestion[questionId].orEmpty().mapTo(mutableSetOf()) { it.optionId }

    fun answeredCount(questions: List<ApprovalQuestion>): Int =
        questions.count { !answersByQuestion[it.id].isNullOrEmpty() }

    fun isComplete(questions: List<ApprovalQuestion>): Boolean =
        questions.isNotEmpty() && questions.all { !answersByQuestion[it.id].isNullOrEmpty() }

    fun orderedAnswers(questions: List<ApprovalQuestion>): List<ApprovalAnswer> =
        questions.flatMap { answersByQuestion[it.id].orEmpty() }
}
