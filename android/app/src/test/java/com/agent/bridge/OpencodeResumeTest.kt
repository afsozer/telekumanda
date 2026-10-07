package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Başka cihazda açık ve TURU SÜREN bir opencode oturumunu uzaktan açmak.
 *
 * Canlıda görülen hata (22.08.2026): "Oturum devam ettirilemedi: cannot change
 * permission mode while running". Sebep opencode değil, köprünün kendi bekçisi
 * — `setPermissionMode` tur sürerken reddediyor. İstek resume'un runCatching'i
 * içinde olduğu için adopt başarılı olsa bile bütün akış onFailure'a düşüyor,
 * oturum hiç açılmıyordu.
 */
class OpencodeResumeTest {
    private fun oturum() = AppDiskSession(
        id = "disk-1",
        cwd = "/proje",
        title = "Test",
        lastText = "",
        turns = 3,
        mtime = 0L,
    )

    @Test
    fun runningSessionStillOpensWhenPermissionModeIsRejected() = runBlocking {
        val fake = FakeBridgeClient()
        fake.jsonResponseProvider = { req ->
            when {
                req.path.startsWith("/opencode2-app/adopt") ->
                    JSONObject().put("ok", true).put("sessionId", "sess-1")
                // Köprünün turu sürerken verdiği gerçek cevap.
                req.path.startsWith("/opencode2-app/permission-mode") ->
                    JSONObject().put("ok", false)
                        .put("error", "cannot change permission mode while running")
                else -> JSONObject().put("ok", true)
            }
        }

        var uiState = RemoteUiState(backend = "opencode2-app")
        val hatalar = mutableListOf<String>()
        val acilanSoketler = mutableListOf<String>()

        val delegate = OpencodeAppActionsDelegate(
            client = fake,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { guncelle -> uiState = guncelle(uiState) },
            emit = { },
            reportError = { baslik, hata -> hatalar.add("$baslik: ${hata.message}") },
            clearThoughts = { },
            openSocket = { sid -> acilanSoketler.add(sid) },
            startPolling = { },
            refreshConversation = { },
            syncActiveTab = { _, _, _ -> },
        )

        delegate.resumeDiskSession(oturum()).join()

        assertTrue("Hata bildirildi: $hatalar", hatalar.isEmpty())
        assertEquals("sess-1", uiState.opencode.sessionId)
        assertEquals("opencode2-app", uiState.backend)
        assertEquals(listOf("sess-1"), acilanSoketler)
        // Kip denenmiş olmalı — sessizce atlanmıyor, yalnız başarısızlığı
        // oturuma girmeyi engellemiyor.
        assertTrue(
            "permission-mode isteği hiç gitmemiş",
            fake.recordedRequests.any { it.path.startsWith("/opencode2-app/permission-mode") },
        )
    }

    // Adopt'un kendisi başarısızsa resume GERÇEKTEN başarısızdır; o hata
    // yutulmamalı, yoksa kullanıcı boş sohbete bakar.
    @Test
    fun failedAdoptStillReportsError() = runBlocking {
        val fake = FakeBridgeClient()
        fake.jsonResponseProvider = { req ->
            if (req.path.startsWith("/opencode2-app/adopt")) {
                JSONObject().put("ok", false).put("error", "session not found")
            } else {
                JSONObject().put("ok", true)
            }
        }

        var uiState = RemoteUiState(backend = "opencode2-app")
        val hatalar = mutableListOf<String>()

        val delegate = OpencodeAppActionsDelegate(
            client = fake,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { guncelle -> uiState = guncelle(uiState) },
            emit = { },
            reportError = { baslik, hata -> hatalar.add("$baslik: ${hata.message}") },
            clearThoughts = { },
            openSocket = { },
            startPolling = { },
            refreshConversation = { },
            syncActiveTab = { _, _, _ -> },
        )

        delegate.resumeDiskSession(oturum()).join()

        assertEquals(1, hatalar.size)
        assertTrue(hatalar.first().contains("session not found"))
        assertEquals("", uiState.opencode.sessionId)
    }
}
