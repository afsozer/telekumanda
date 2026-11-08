package com.agent.bridge

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

class LiteProvidersTest {
    // 24.10.2026: Codex lite'tan çıkarıldı (kullanıcı isteği), 08.11.2026: agy
    // backend'i tamamen söküldü. Lite yalnız Claude sunar.
    @Test
    fun liteYalnizClaudeSunar() {
        assertEquals(listOf("claude-app"), LITE_BACKEND_ORDER)
        val lite = RemoteUiState(liteEdition = true)
        assertFalse(lite.supportsEditionBackend("codex-app"))
        assertFalse(lite.supportsEditionBackend("omp"))
        assertTrue(lite.supportsEditionBackend("claude-app"))
    }

    @Test
    fun staleVisibilityAndCatalogCannotBringLiteProvidersBack() {
        val oldOrder = listOf("claude-app", "codex-app", "omp", "cowork")
        val state = RemoteUiState(liteEdition = true, backendOrder = oldOrder, visibleBackends = oldOrder.toSet())
        assertEquals(LITE_BACKEND_ORDER, state.availableBackendIds())
        // Tam sürümde kullanıcı ayarı aynen geçerlidir.
        assertEquals(oldOrder, state.copy(liteEdition = false).availableBackendIds())
    }

    @Test
    fun liteteCodexSekmesiGizlenir() {
        val state = RemoteUiState(liteEdition = true, activeBridgeProfileId = "", liteWorkspaceRoot = "C:/Lite", openTabs = listOf(
            AppTab(id = "codex", backend = "codex-app"),
            AppTab(id = "claude", backend = "claude-app"),
            AppTab(id = "empty", backend = ""),
        ))
        assertEquals(listOf("claude", "empty"), state.visibleTabs.map { it.id })
        // Tam sürümde Codex sekmesi kalır.
        assertEquals(listOf("codex", "claude", "empty"), state.copy(liteEdition = false).visibleTabs.map { it.id })
    }

    @Test
    fun restoringLiteRemovesCodexTabsFromPreferences() {
        var stored = """{"active":"cx","tabs":[{"id":"cx","backend":"codex-app"},{"id":"claude","backend":"claude-app"}]}"""
        val prefs = object : KeyValueStore {
            override fun getString(key: String): String = stored
            override fun putString(key: String, value: String) { stored = value }
        }
        var state = RemoteUiState(liteEdition = true)
        val delegate = TabsDelegate(
            prefs = prefs, scope = CoroutineScope(Dispatchers.Unconfined), state = { state },
            update = { state = it(state) }, emit = {}, client = BridgeClient(), goToLanding = {},
            enterClaudeAppMode = {}, enterCodexAppMode = {},
            enterCoworkMode = {}, singleSession = true,
        )
        delegate.restoreTabs()
        assertEquals("claude", state.activeTabId)
        assertEquals(listOf("claude"), state.openTabs.map { it.id })
        val saved = JSONObject(stored).getJSONArray("tabs")
        assertEquals(1, saved.length())
        assertEquals("claude-app", saved.getJSONObject(0).getString("backend"))
    }
}
