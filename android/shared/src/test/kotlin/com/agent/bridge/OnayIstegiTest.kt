package com.agent.bridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// Köprüyle ortak onay sözleşmesi: her /<backend>/approve gövdesi bekleyen onayın
// `requestId`'sini taşır, `allow` her zaman açık true/false gider; köprü
// eşleşmeyen kimliğe 409 + "stale approval" döner ve uygulama bunu çökmeden,
// kısa bir açıklamayla gösterir.
class OnayIstegiTest {
    private val settings = BridgeSettings("http://localhost", "t")

    @Test
    fun bayatOnayYalnizSozlesmedekiCevaptir() {
        assertTrue(bayatOnayHatasi(BridgeHttpException(409, "stale approval")))
        assertTrue(bayatOnayHatasi(BridgeHttpException(409, "Stale approval: requestId mismatch")))
        // Aynı kod başka çakışmalar için de kullanılıyor (cowork kilidi).
        assertFalse(bayatOnayHatasi(BridgeHttpException(409, "cowork session locked")))
        assertFalse(bayatOnayHatasi(BridgeHttpException(500, "stale approval")))
        assertFalse(bayatOnayHatasi(IOException("stale approval")))
    }

    @Test
    fun bildirimYoluKimligiVeAcikAllowGonderir() = runBlocking {
        val client = FakeBridgeClient()
        client.approve(settings, "codex-app", "s1", allow = false, requestId = "r1")
        client.approve(settings, "omp", "s2", allow = true)

        val (ilk, ikinci) = client.recordedRequests
        assertEquals("/codex-app/approve", ilk.path)
        assertEquals("r1", ilk.body?.optString("requestId"))
        assertTrue(ilk.body!!.has("allow"))
        assertFalse(ilk.body!!.getBoolean("allow"))
        // Kimlik bilinmiyorsa alan hiç yazılmaz (boş metin köprüde "eşleşmedi" sayılırdı).
        assertEquals("/omp/approve", ikinci.path)
        assertFalse(ikinci.body!!.has("requestId"))
        assertTrue(ikinci.body!!.getBoolean("allow"))
    }

    @Test
    fun herBackendinOnayGovdesiKimlikTasir() = runBlocking {
        val client = FakeBridgeClient()
        client.claudeAppApprove(settings, "s", true, requestId = "c1")
        client.codexAppApprove(settings, "s", true, "accept", requestId = "x1")
        client.ompApprove(settings, "s", true, requestId = "m1")

        assertEquals(
            listOf(
                "/claude-app/approve" to "c1",
                "/codex-app/approve" to "x1",
                "/omp/approve" to "m1",
            ),
            client.recordedRequests.map { it.path to it.body?.optString("requestId") },
        )
        assertTrue(client.recordedRequests.all { it.body!!.has("allow") })
    }
}
