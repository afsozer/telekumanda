package com.agent.bridge

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

class LiteProvidersTest {
    @Test
    fun staleVisibilityAndCatalogCannotBringRemovedProvidersBack() {
        val oldOrder = listOf("claude-app", "codex-app", "opencode-app", "opencode2-app", "agy", "omp", "cowork")
        val state = RemoteUiState(liteEdition = true, backendOrder = oldOrder, visibleBackends = oldOrder.toSet())
        assertEquals(LITE_BACKEND_ORDER, state.availableBackendIds())
        assertEquals(oldOrder, state.copy(liteEdition = false).availableBackendIds())
    }

    @Test
    fun liteHidesOldOpenCodeTabsAndDiskSessionsButKeepsAllowedProviders() {
        val state = RemoteUiState(liteEdition = true, activeBridgeProfileId = "", liteWorkspaceRoot = "C:/Lite", openTabs = listOf(
            AppTab(id = "removed", backend = "opencode2-app"),
            AppTab(id = "claude", backend = "claude-app"),
            AppTab(id = "empty", backend = ""),
        ), opencode = OpencodeUiState(diskSessions = listOf(
            AppDiskSession(id = "oc", cwd = "C:/Lite", title = "OpenCode", lastText = "", turns = 0, mtime = 0L)
        )))
        assertEquals(listOf("claude", "empty"), state.visibleTabs.map { it.id })
        assertTrue(state.backendDiskSessions("opencode2-app").isEmpty())
        assertEquals(3, state.copy(liteEdition = false).visibleTabs.size)
        assertEquals(1, state.copy(liteEdition = false).backendDiskSessions("opencode2-app").size)
    }

    @Test
    fun restoringLiteRemovesOldOpenCodeTabFromPreferences() {
        var stored = """{"active":"oc","tabs":[{"id":"oc","backend":"opencode2-app"},{"id":"claude","backend":"claude-app"}]}"""
        val prefs = object : KeyValueStore {
            override fun getString(key: String): String = stored
            override fun putString(key: String, value: String) { stored = value }
        }
        var state = RemoteUiState(liteEdition = true)
        val delegate = TabsDelegate(
            prefs = prefs, scope = CoroutineScope(Dispatchers.Unconfined), state = { state },
            update = { state = it(state) }, emit = {}, client = BridgeClient(), goToLanding = {},
            enterAgyMode = {}, enterClaudeAppMode = {}, enterCodexAppMode = {},
            enterOpencodeAppMode = {}, enterOpencode2AppMode = {}, enterCoworkMode = {}, singleSession = true,
        )
        delegate.restoreTabs()
        assertEquals("claude", state.activeTabId)
        assertEquals(listOf("claude"), state.openTabs.map { it.id })
        val saved = JSONObject(stored).getJSONArray("tabs")
        assertEquals(1, saved.length())
        assertEquals("claude-app", saved.getJSONObject(0).getString("backend"))
    }
}
