package com.agent.bridge

import org.junit.Assert.*
import org.junit.Test

class AlertHostStateTest {
    @Test fun simpleConfirmationsUseToast() {
        val state = AlertHostState()
        listOf(
            "Oturum silindi", "PC'ye kaydedildi: rapor.docx", "Oturum kimliği kopyalandı",
            "3 öğe silindi",
        ).forEach {
            state.show(it)
            assertFalse(it, state.visible)
            assertEquals(it, state.toastText)
        }
    }

    @Test fun errorsAndPartialSuccessKeepDetails() {
        listOf(
            "Silme başarısız", "3 not silindi, 1 tanesi silinemedi",
            "3 öğe silindi, 1 tanesi başarısız",
            "PC'ye kaydedildi ancak yeni yerel değişiklik bekliyor: rapor.docx",
            "Sunucu hatası: Oturum silindi", "Beklenmeyen cevap",
        ).forEach {
            val state = AlertHostState()
            state.show(it)
            assertTrue(it, state.visible)
            assertEquals(it, state.alert.text)
            assertEquals(0L, state.toastRevision)
        }
    }

    @Test fun actionIsNeverLostToToast() {
        val state = AlertHostState()
        val alert = AppAlert("Kaydedildi: rapor.docx", AppAlertAction.OpenFile("rapor.docx", coworkOnly = false), "Aç")
        state.show(alert)
        assertTrue(state.visible)
        assertEquals(alert, state.alert)
        assertEquals(0L, state.toastRevision)
    }

    @Test fun successDoesNotReplaceVisibleError() {
        val state = AlertHostState()
        state.show("Silme başarısız")
        state.show("Oturum silindi")
        assertTrue(state.visible)
        assertEquals("Silme başarısız", state.alert.text)
    }

    @Test fun repeatedMessagesTriggerAgain() {
        val state = AlertHostState()
        repeat(2) { state.show("Oturum silindi") }
        assertEquals(2L, state.toastRevision)
        repeat(2) { state.show("Silme başarısız") }
        assertEquals(2L, state.revision)
        state.dismiss()
        assertFalse(state.visible)
    }
}
