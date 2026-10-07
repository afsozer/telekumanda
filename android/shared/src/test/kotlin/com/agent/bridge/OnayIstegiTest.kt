package com.agent.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
    fun bildirimTusuKimlikVeSurumeGoreSecilir() {
        assertEquals(OnayEylemKipi.DOGRUDAN, onayEylemKipi(31, "r1"))
        assertEquals(OnayEylemKipi.DOGRUDAN, onayEylemKipi(36, "r1"))
        // API 31 altında eyleme kimlik doğrulaması bağlanamıyor: uygulama açılır.
        assertEquals(OnayEylemKipi.UYGULAMADA_AC, onayEylemKipi(30, "r1"))
        // Kimliği bilinmeyen onay doğrudan verilemez: bayat bildirim yeni isteği onaylardı.
        assertEquals(OnayEylemKipi.UYGULAMADA_AC, onayEylemKipi(36, ""))
    }

    @Test
    fun kilitEkraniMetniIcerikTasimaz() {
        assertEquals("Onay bekleniyor", kilitEkraniMetni("attention"))
        assertEquals("Yeni bildirim var", kilitEkraniMetni("bilinmeyen"))
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
        client.opencodeAppApprove(settings, "s", false, requestId = "o1")
        client.ompApprove(settings, "s", true, requestId = "m1")

        assertEquals(
            listOf(
                "/claude-app/approve" to "c1",
                "/codex-app/approve" to "x1",
                "/opencode2-app/approve" to "o1",
                "/omp/approve" to "m1",
            ),
            client.recordedRequests.map { it.path to it.body?.optString("requestId") },
        )
        assertTrue(client.recordedRequests.all { it.body!!.has("allow") })
    }

    @Test
    fun delegeEkrandakiKartinKimliginiKullanir() {
        val client = FakeBridgeClient()
        val state = RemoteUiState(
            opencode = OpencodeUiState(sessionId = "oc1"),
            approval = ApprovalInfo(requestId = "r-ekran"),
        )
        val delegate = opencodeDelegate(client, { state })

        delegate.approve(true)
        delegate.approve(false, requestId = "r-kart")

        assertEquals(
            listOf("r-ekran", "r-kart"),
            client.recordedRequests.map { it.body?.optString("requestId") },
        )
    }

    @Test
    fun bayatOnayKisaMesajlaGosterilirVeKonusmaTazelenir() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { throw BridgeHttpException(409, "stale approval") }
        }
        val mesajlar = mutableListOf<String>()
        val hatalar = mutableListOf<Throwable>()
        var tazeleme = 0
        val state = RemoteUiState(
            opencode = OpencodeUiState(sessionId = "oc1"),
            approval = ApprovalInfo(requestId = "r-eski"),
        )
        val delegate = opencodeDelegate(
            client, { state },
            emit = { mesajlar += it },
            reportError = { _, e -> hatalar += e },
            refreshConversation = { tazeleme++ },
        )

        delegate.approve(true)
        delegate.answerQuestions(listOf(ApprovalAnswer("q", "o", "Evet")))

        assertEquals(listOf(BAYAT_ONAY_MESAJI, BAYAT_ONAY_MESAJI), mesajlar)
        assertTrue(hatalar.isEmpty())
        assertEquals(2, tazeleme)
    }

    @Test
    fun digerHatalarEskisiGibiRaporlanir() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { throw BridgeHttpException(500, "boom") }
        }
        val mesajlar = mutableListOf<String>()
        val hatalar = mutableListOf<String>()
        val delegate = opencodeDelegate(
            client, { RemoteUiState(opencode = OpencodeUiState(sessionId = "oc1")) },
            emit = { mesajlar += it },
            reportError = { onek, _ -> hatalar += onek },
        )

        delegate.approve(true)

        assertTrue(mesajlar.isEmpty())
        assertEquals(listOf("Onay gonderilemedi"), hatalar)
    }

    @Test
    fun pushOlayiVeEskiYoklamaKimligiTasir() = runBlocking {
        val olay = parsePushEvents(
            JSONArray("""[{"deliveryId":"d1","kind":"attention","backend":"omp","sessionId":"s1","requestId":"r9"}]"""),
        ).single()
        assertEquals("r9", olay.requestId)

        val client = FakeBridgeClient().apply {
            jsonResponseProvider = {
                JSONObject().put("pending", true).put("sessionId", "s1")
                    .put("approval", JSONObject().put("requestId", "r7"))
            }
        }
        assertEquals("r7", client.pollNotifications(settings, false, "", "").requestId)
    }

    private fun opencodeDelegate(
        client: FakeBridgeClient,
        state: () -> RemoteUiState,
        emit: suspend (String) -> Unit = {},
        reportError: suspend (String, Throwable) -> Unit = { _, e -> throw e },
        refreshConversation: (Boolean) -> Unit = {},
    ) = OpencodeAppActionsDelegate(
        client = client,
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        state = state,
        update = {},
        emit = emit,
        reportError = reportError,
        clearThoughts = {},
        openSocket = {},
        startPolling = {},
        refreshConversation = refreshConversation,
        syncActiveTab = { _, _, _ -> },
    )
}
