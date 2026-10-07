package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Olay geçmişi ayrımı (kullanıcı kararı 05.08.2026): aynı oturumun tekrar eden
// başladı/bitti olayları listeyi şişiriyordu; oturum+tür başına yalnız en yeni
// olay güncelde kalır, tekrarlar Geçmiş bölümüne iner.
class OperationEventSplitTest {

    private fun ev(id: String, kind: String, sessionId: String, diskId: String = "") = OperationEvent(
        id = id, kind = kind, status = "", backend = "claude-app", backendLabel = "Claude",
        sessionId = sessionId, cwd = "", model = "", summary = "", at = "", diskId = diskId,
    )

    @Test
    fun ayniOturumunTekrarlariGecmiseIner() {
        // Köprü yeniden→eskiye verir: 10.40 bitti, 10.30 başladı, 10.10 bitti, 10.00 başladı.
        val events = listOf(
            ev("e4", "completed", "x"),
            ev("e3", "started", "x"),
            ev("e2", "completed", "x"),
            ev("e1", "started", "x"),
        )
        val split = splitOperationEvents(events)
        assertEquals(listOf("e4", "e3"), split.current.map { it.id })
        assertEquals(listOf("e2", "e1"), split.history.map { it.id })
    }

    @Test
    fun farkliOturumlarBirbirineKarismaz() {
        val split = splitOperationEvents(listOf(
            ev("a1", "started", "a"),
            ev("b1", "started", "b"),
        ))
        assertEquals(2, split.current.size)
        assertTrue(split.history.isEmpty())
    }

    @Test
    fun oturumsuzOlaylarHepGuncelde() {
        // Kimliksiz olaylar gruplanamaz; ikisi de görünür kalmalı.
        val split = splitOperationEvents(listOf(
            ev("n1", "failed", ""),
            ev("n2", "failed", ""),
        ))
        assertEquals(2, split.current.size)
    }

    @Test
    fun kalisiKimlikOncelikli() {
        // Adopt sonrası köprü kabuğu (sessionId) değişir ama diskId sabittir;
        // aynı diskId'li olaylar aynı oturum sayılmalı.
        val split = splitOperationEvents(listOf(
            ev("e2", "started", "shell-yeni", diskId = "ses_1"),
            ev("e1", "started", "shell-eski", diskId = "ses_1"),
        ))
        assertEquals(listOf("e2"), split.current.map { it.id })
        assertEquals(listOf("e1"), split.history.map { it.id })
    }
}
