package com.agent.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Cihaz güvenliği: köprüdeki cihaz listesi ve iptal (POST /devices/revoke).
class CihazYonetimiTest {
    private val settings = BridgeSettings("http://localhost", "abk_x")

    @Test
    fun listeIptalEdilenleriDeTasir() = runBlocking {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = {
                JSONObject().put("ok", true).put(
                    "devices",
                    JSONArray()
                        .put(JSONObject().put("id", "d1").put("name", "Android Pixel").put("createdAt", "2026-10-01")
                            .put("lastSeenAt", "2026-10-07").put("revokedAt", JSONObject.NULL).put("rotatedAt", JSONObject.NULL))
                        .put(JSONObject().put("id", "d2").put("name", "Eski tablet").put("revokedAt", "2026-10-05"))
                        .put(JSONObject().put("name", "kimliksiz")),
                )
            }
        }
        val devices = client.listDevices(settings)

        assertEquals("/devices", client.recordedRequests.single().path)
        assertEquals(listOf("d1", "d2"), devices.map { it.id })
        assertFalse(devices[0].revoked)
        assertEquals("", devices[0].revokedAt)
        assertTrue(devices[1].revoked)
    }

    @Test
    fun iptalGovdesiCihazKimligiTasir() = runBlocking {
        val client = FakeBridgeClient()
        client.revokeDevice(settings, "d2")
        val istek = client.recordedRequests.single()
        assertEquals("/devices/revoke", istek.path)
        assertEquals("d2", istek.body?.optString("deviceId"))
    }

    @Test(expected = java.io.IOException::class)
    fun basarisizIptalHataVerir(): Unit = runBlocking {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { JSONObject().put("ok", false).put("error", "unknown device") }
        }
        client.revokeDevice(settings, "yok")
    }

    // Lite APK köprü kimliği taşımıyor: kimlik yoksa ilk ekran eşleştirme.
    @Test
    fun liteKimliksizseEslestirmeIster() {
        assertTrue(RemoteUiState(liteEdition = true, settings = BridgeSettings("http://pc:8787", "")).liteEslestirmeGerekli)
        assertFalse(RemoteUiState(liteEdition = true, settings = BridgeSettings("http://pc:8787", "abk_1")).liteEslestirmeGerekli)
        assertFalse(RemoteUiState(liteEdition = false, settings = BridgeSettings("http://pc:8787", "")).liteEslestirmeGerekli)
    }
}
