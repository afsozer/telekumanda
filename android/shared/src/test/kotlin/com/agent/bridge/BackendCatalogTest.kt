package com.agent.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendCatalogTest {
    private fun catalogJson(): JSONObject = JSONObject()
        .put("ok", true)
        .put("contractVersion", 1)
        .put("backends", JSONArray()
            .put(JSONObject().put("id", "codex-app").put("apiBackend", "codex-app").put("label", "Codex")
                .put("capabilities", JSONObject().put("approvals", true).put("userInput", true)
                    .put("permissionModes", true).put("context", true).put("plan", true)
                    .put("outputs", false)))
            .put(JSONObject().put("id", "agy").put("apiBackend", "agy").put("label", "Agy")
                .put("capabilities", JSONObject().put("context", false))))

    @Test
    fun parsesVersionedCatalogFromBridge() = runBlocking {
        val client = FakeBridgeClient()
        client.jsonResponseProvider = { catalogJson() }
        val catalog = client.backendCatalog(BridgeSettings("http://localhost", "token"))
        assertEquals(1, catalog.contractVersion)
        assertTrue(catalog.loaded)
        assertTrue(catalog.entries.getValue("codex-app").plan)
        assertFalse(catalog.entries.getValue("agy").context)
    }

    @Test
    fun resolverPrefersCatalogOverEmbeddedEnum() {
        val catalog = BackendCatalogInfo(1, mapOf(
            "codex-app" to BackendCapabilities(approvals = true, plan = true, context = true),
        ))
        val caps = backendCapabilities("codex-app", catalog = catalog)
        assertTrue(caps.plan)
        assertTrue(caps.approvals)
    }

    @Test
    fun coworkDerivesFromSelectedProviderPlusSharedOutputs() {
        val catalog = BackendCatalogInfo(1, mapOf(
            "codex-app" to BackendCapabilities(plan = true, context = true),
        ))
        val caps = backendCapabilities("cowork", coworkProvider = "codex-app", catalog = catalog)
        assertTrue(caps.plan)      // provider'dan gelir
        assertTrue(caps.outputs)   // cowork ortak teslimat katmanı
    }

    @Test
    fun fallsBackToEmbeddedEnumWhenCatalogAbsent() {
        // Katalog yokken gömülü enum devreye girer: Codex plan yeteneğine sahiptir.
        assertTrue(backendCapabilities("codex-app").plan)
        // Bilinmeyen backend güvenli varsayılana düşer.
        val unknown = backendCapabilities("made-up", catalog = BackendCatalogInfo())
        assertFalse(unknown.context)
    }

    @Test
    fun unloadedCatalogReportsNotLoaded() {
        assertFalse(BackendCatalogInfo().loaded)
        assertNull(RemoteUiState().backendCatalog)
    }

    // Canlı hata: uygulama codex modelini SABİT "gpt-5.6-luna" ile başlatıyordu ve
    // loadCodexAppModels `model.ifBlank { default }` ile doldurduğu için köprünün
    // (config.toml'un) varsayılanı hiç uygulanmıyordu.
    @Test
    fun codexModelStartsEmptySoBridgeDefaultWins() {
        val fresh = CodexUiState()
        assertTrue(fresh.model.isBlank())
        assertTrue(fresh.defaultModel.isBlank())
        val loaded = fresh.copy(defaultModel = "kopru-modeli")
        assertEquals("kopru-modeli", loaded.model.ifBlank { loaded.defaultModel })
    }
}
