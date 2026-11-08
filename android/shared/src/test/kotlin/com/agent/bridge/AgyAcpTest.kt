package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Antigravity (agy) ACP backend'inin İSTEMCİ tarafı sözleşmesi.
 *
 * Kapsam bilinçli: ağ çağrısı değil, backend id'si etrafındaki jenerik
 * tabloların agy'yi gerçekten tanıyıp tanımadığı. Söküm turunda bu tabloların
 * her birinden tek tek dal silinmişti; biri geri eklenmeyi unutulursa ekran
 * sessizce boş kalır (model seçici boş, oturum çekmecesi boş, sekme etiketi id).
 */
class AgyAcpTest {

    @Test
    fun katalogdaAgyVarVeYetenekleriACPyiYansitir() {
        val caps = Backend.AGY.capabilities
        // Onay ve izin kipi ACP'de protokol düzeyinde var.
        assertTrue("onaylar protokolden geliyor", caps.approvals)
        assertTrue("default/accept-edits/plan", caps.permissionModes)
        // context=false: ACP usage toplamı veriyor ama pencere boyutu bildirmiyor,
        // doluluk çubuğu çizilemez.
        assertFalse("baglam penceresi bilinmiyor", caps.context)
        // Tur içi gönderim uçları (steer/follow-up) agy'de kayıtlı değil.
        assertFalse(caps.userInputSteer)
        assertFalse(caps.userInputQueue)
        assertEquals("agy", Backend.AGY.id)
        assertEquals("agy", Backend.AGY.apiBackend)
    }

    @Test
    fun etiketMonogramVeSekmeAdiDoludur() {
        assertEquals("Antigravity", BACKEND_LABELS["agy"])
        assertEquals("Antigravity", backendShortLabel("agy"))
        assertEquals("A", providerMonogram("agy"))
        // Sekme başlığı id'ye düşmemeli.
        assertTrue(generateTabTitle("agy", "", "C:/is/proje", "gemini-3.8-flash-low").isNotBlank())
    }

    @Test
    fun mcpHedefiAntigravityConfigine_gider() {
        assertEquals(MCP_TARGET_ANTIGRAVITY, mcpTargetFor("agy", ""))
        assertEquals("Antigravity", mcpTargetLabel(MCP_TARGET_ANTIGRAVITY))
    }

    @Test
    fun oturumDurumuAgyKutusundanOkunur() {
        val state = RemoteUiState(agy = AgyUiState(
            sessionId = "s1", lastSessionId = "s0", cwd = "C:/is", model = "gemini-3.8-flash-low",
            defaultModel = "gemini-3.8-flash-high", permissionMode = "plan", setupPending = true, diskLoading = true,
        ))
        val oturum = state.backendSession("agy")
        assertEquals("s1", oturum.sessionId)
        assertEquals("s0", oturum.lastSessionId)
        assertEquals("C:/is", oturum.cwd)
        assertEquals("gemini-3.8-flash-low", oturum.model)
        assertEquals("gemini-3.8-flash-high", oturum.defaultModel)
        assertEquals("plan", oturum.permissionMode)
        assertTrue(oturum.setupPending)
        assertTrue(oturum.diskLoading)
    }

    @Test
    fun modelVeIzinKipiSecicileriProtokolListesindenBeslenir() {
        val state = RemoteUiState(agy = AgyUiState(
            availableModels = listOf(
                BackendModel("Gemini 3.8 Flash (Low)", "gemini-3.8-flash-low"),
                BackendModel("Claude Opus 4.6", "claude-opus-4-6-thinking"),
            ),
            permissionModes = listOf(
                PermissionMode("default", "Default", "Onay ister"),
                PermissionMode("plan", "Plan", "Once plan"),
            ),
        ))
        assertEquals(listOf("gemini-3.8-flash-low", "claude-opus-4-6-thinking"), state.backendModelOptions("agy").map { it.id })
        assertEquals(listOf("default", "plan"), state.backendPermissionModeOptions("agy").map { it.id })
        assertEquals(listOf("Default", "Plan"), state.backendPermissionModeOptions("agy").map { it.label })
        // Desteklenmeyen kontroller çizilmemeli: çaba ve diff agy'de yok.
        assertTrue(state.backendEffortOptions("agy").isEmpty())
        assertFalse(state.backendDiffSupported("agy"))
    }

    @Test
    fun diskOturumlariCekmeceyeDonusur() {
        val state = RemoteUiState(agy = AgyUiState(diskSessions = listOf(
            AppDiskSession("c1", "C:/is", "Eski sohbet", "son satir", 3, 1700L),
        )))
        val liste = state.backendDiskSessions("agy")
        assertEquals(1, liste.size)
        assertEquals("c1", liste[0].id)
        assertEquals("Eski sohbet", liste[0].title)
        assertEquals(3, liste[0].turns)
    }

    @Test
    fun cevrimdisiKonusmaAnahtariAgyOturumunuBulur() {
        val state = RemoteUiState(backend = "agy", agy = AgyUiState(sessionId = "s9"))
        assertEquals("s9", offlineConversationKey(state)?.sessionId)
    }

    @Test
    fun koprudegisimindeDiskListesiBosalir() {
        val state = RemoteUiState(agy = AgyUiState(
            sessionId = "s1", diskSessions = listOf(AppDiskSession("c1", "C:/is", "t", "l", 1, 1L)), diskLoading = true,
        ))
        val sifirlanan = state.clearedForBridgeSwitch()
        assertTrue(sifirlanan.agy.diskSessions.isEmpty())
        assertFalse(sifirlanan.agy.diskLoading)
    }

    @Test
    fun agyVarsayilanOlarakGorunurVeSiraliDir() {
        val state = RemoteUiState()
        assertTrue("agy" in state.visibleBackends)
        assertTrue("agy" in state.backendOrder)
        assertTrue("agy" in state.availableBackendIds())
        // Claude'dan hemen sonra gelir (kullanıcı sırası).
        assertEquals(1, state.backendOrder.indexOf("agy"))
    }
}
