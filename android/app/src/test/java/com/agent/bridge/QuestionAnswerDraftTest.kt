package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionAnswerDraftTest {
    private val questions = (1..3).map { index ->
        ApprovalQuestion(
            id = "q$index",
            header = "S$index",
            question = "$index. soru",
            options = listOf(
                ApprovalOption("a$index", "A$index"),
                ApprovalOption("b$index", "B$index"),
            ),
        )
    }

    @Test
    fun cevaplarTumSorularTamamlananaKadarBirikiyor() {
        var draft = QuestionAnswerDraft()
        draft = draft.select(questions[0], questions[0].options[1])

        assertEquals(1, draft.answeredCount(questions))
        assertFalse(draft.isComplete(questions))
        assertEquals("b1", draft.selectedOptionId("q1"))

        draft = draft.select(questions[1], questions[1].options[0])
        draft = draft.select(questions[2], questions[2].options[1])

        assertTrue(draft.isComplete(questions))
        assertEquals(listOf("q1", "q2", "q3"), draft.orderedAnswers(questions).map { it.questionId })
        assertEquals(listOf("B1", "A2", "B3"), draft.orderedAnswers(questions).map { it.label })
    }

    private val multiQuestion = ApprovalQuestion(
        id = "m1",
        header = "Çoklu",
        question = "Hangileri?",
        options = listOf(
            ApprovalOption("a", "A"),
            ApprovalOption("b", "B"),
            ApprovalOption("c", "C"),
        ),
        multiple = true,
    )

    @Test
    fun cokSecimliSoruSecimleriBiriktiriyor() {
        var draft = QuestionAnswerDraft().select(multiQuestion, multiQuestion.options[0])
        draft = draft.select(multiQuestion, multiQuestion.options[2])

        assertEquals(setOf("a", "c"), draft.selectedOptionIds("m1"))
        // Cevaplanan SORU sayısı; seçenek sayısı değil.
        assertEquals(1, draft.answeredCount(listOf(multiQuestion)))
        assertTrue(draft.isComplete(listOf(multiQuestion)))
        assertEquals(listOf("A", "C"), draft.orderedAnswers(listOf(multiQuestion)).map { it.label })
    }

    @Test
    fun cokSecimliSoruSeciliyeTekrarDokununcaCikariyor() {
        var draft = QuestionAnswerDraft()
            .select(multiQuestion, multiQuestion.options[0])
            .select(multiQuestion, multiQuestion.options[1])
        draft = draft.select(multiQuestion, multiQuestion.options[0])

        assertEquals(setOf("b"), draft.selectedOptionIds("m1"))

        // Son seçim de kalkarsa soru cevapsıza döner — boş cevap gönderilmemeli.
        draft = draft.select(multiQuestion, multiQuestion.options[1])
        assertEquals(0, draft.answeredCount(listOf(multiQuestion)))
        assertFalse(draft.isComplete(listOf(multiQuestion)))
        assertTrue(draft.orderedAnswers(listOf(multiQuestion)).isEmpty())
    }

    @Test
    fun cokluVeTekSecimliSorularAyniTaslaktaKaristirilabiliyor() {
        val all = listOf(multiQuestion, questions[0])
        var draft = QuestionAnswerDraft()
            .select(multiQuestion, multiQuestion.options[0])
            .select(multiQuestion, multiQuestion.options[1])
            .select(questions[0], questions[0].options[1])

        assertTrue(draft.isComplete(all))
        assertEquals(listOf("m1", "m1", "q1"), draft.orderedAnswers(all).map { it.questionId })
        assertEquals(listOf("A", "B", "B1"), draft.orderedAnswers(all).map { it.label })
    }

    @Test
    fun ayniSorudaYeniSecimOncekiniDegistiriyor() {
        var draft = QuestionAnswerDraft().select(questions[0], questions[0].options[0])
        draft = draft.select(questions[0], questions[0].options[1])

        assertEquals(1, draft.answeredCount(questions))
        assertEquals("b1", draft.selectedOptionId("q1"))
        assertEquals("B1", draft.orderedAnswers(questions).single().label)
    }
}
