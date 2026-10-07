package com.agent.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSessionsTest {

    @Test
    fun testSessionPrioritySortsPinnedFirstThenNewestWithinEachGroup() {
        data class Item(val id: String, val pinned: Boolean, val mtime: Long)

        val sorted = listOf(
            Item("unpinned-new", pinned = false, mtime = 400L),
            Item("pinned-old", pinned = true, mtime = 100L),
            Item("unpinned-old", pinned = false, mtime = 200L),
            Item("pinned-new", pinned = true, mtime = 300L),
        ).sortedBySessionPriority(
            pinned = { it.pinned },
            mtime = { it.mtime },
        )

        assertEquals(
            listOf("pinned-new", "pinned-old", "unpinned-new", "unpinned-old"),
            sorted.map { it.id },
        )
    }

    @Test
    fun testBackendShortLabelFallsBackForUnknownIds() {
        assertEquals("Claude", backendShortLabel("claude-app"))
        assertEquals("yok-boyle", backendShortLabel("yok-boyle"))
    }

    @Test
    fun testClaudeAppDiskSessionsMapping() {
        val session = ClaudeDiskSession(
            id = "s1",
            cwd = "C:/p",
            title = "başlık",
            lastText = "son",
            turns = 3,
            mtime = 99L,
            pinned = true,
            archived = false
        )
        val state = RemoteUiState().copy(
            claudeAppDiskSessions = listOf(session)
        )
        val list = state.backendDiskSessions("claude-app")
        assertEquals(1, list.size)
        val item = list[0]
        assertEquals("s1", item.id)
        assertEquals("C:/p", item.cwd)
        assertEquals("başlık", item.title)
        assertEquals("son", item.lastText)
        assertEquals(3, item.turns)
        assertEquals(99L, item.mtime)
        assertTrue(item.pinned)
        assertFalse(item.archived)
    }

    @Test
    fun testCoworkSessionsMapping() {
        val session = CoworkSessionRecord(
            provider = "codex-app",
            sessionId = "abcdef123",
            threadId = "",
            cwd = "C:/w",
            title = "Örnek görev başlığı",
            lastText = "Kaynakları incele",
            lastUsedAt = "2026-07-12"
        )
        val state = RemoteUiState().copy(
            cowork = CoworkUiState(sessions = listOf(session))
        )
        val list = state.backendDiskSessions("cowork")
        assertEquals(1, list.size)
        val item = list[0]
        assertEquals("abcdef123", item.id)
        assertEquals("C:/w", item.cwd)
        assertEquals("Örnek görev başlığı", item.title)
        assertEquals("Kaynakları incele", item.lastText)
        assertFalse(item.pinned)
        assertFalse(item.archived)
    }

    @Test
    fun testCoworkUntitledSessionGetsReadableProviderName() {
        val session = CoworkSessionRecord(
            provider = "claude-app",
            sessionId = "abcdef123",
            cwd = "C:/w",
        )
        val item = RemoteUiState().copy(cowork = CoworkUiState(sessions = listOf(session)))
            .backendDiskSessions("cowork")
            .single()
        assertEquals("Yeni Claude oturumu", item.title)
    }

    @Test
    fun testOpencodeDiskSessionsMappingIncludesPin() {
        val session = AppDiskSession(
            id = "ses_open",
            cwd = "C:/open",
            title = "özel başlık",
            lastText = "son",
            turns = 2,
            mtime = 42L,
            pinned = true,
        )
        val item = RemoteUiState().copy(opencodeAppDiskSessions = listOf(session))
            .backendDiskSessions("opencode2-app")
            .single()
        assertEquals("özel başlık", item.title)
        assertTrue(item.pinned)
        assertFalse(item.archived)
    }

    @Test
    fun testOpencodeDiskSessionClientParsesPinnedFromBridge() = runBlocking {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = {
                JSONObject().put(
                    "sessions",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "ses_pinned")
                            .put("cwd", "C:/open")
                            .put("title", "Sabitli")
                            .put("lastText", "son")
                            .put("turns", 2)
                            .put("mtime", 42L)
                            .put("pinned", true)
                    )
                )
            }
        }

        val session = client.opencodeAppDiskSessions(BridgeSettings("http://localhost", "token")).single()
        assertEquals("ses_pinned", session.id)
        assertTrue(session.pinned)
    }

    @Test
    fun testBackendSessionActionCapabilities() {
        assertTrue(backendSessionPinSupported("claude-app"))
        assertTrue(backendSessionPinSupported("codex-app"))
        assertTrue(backendSessionPinSupported("opencode2-app"))
        assertTrue(backendSessionRenameSupported("opencode2-app"))
        assertFalse(backendSessionArchiveSupported("opencode2-app"))
        assertTrue(backendSessionArchiveSupported("claude-app"))
        assertTrue(backendSessionArchiveSupported("codex-app"))
        assertFalse(backendSessionPinSupported("agy"))
        assertFalse(backendSessionRenameSupported("cowork"))
    }

    @Test
    fun testBackendUsesWorkspaces() {
        assertTrue(backendUsesWorkspaces("cowork"))
        assertFalse(backendUsesWorkspaces("claude-app"))
        assertFalse(backendUsesWorkspaces("codex-app"))
        assertFalse(backendUsesWorkspaces("agy"))
    }

    @Test
    fun testOpenTabWarmTargetsIncludeInactiveSupportedTabsAndCoworkProvider() {
        val state = RemoteUiState(
            activeBridgeProfileId = "bridge-a",
            openTabs = listOf(
                AppTab("active", "opencode2-app", sessionId = "h1", bridgeProfileId = "bridge-a"),
                AppTab("inactive", "codex-app", sessionId = "c1", bridgeProfileId = "bridge-a"),
                AppTab("cowork", "cowork", provider = "codex-app", sessionId = "o1", bridgeProfileId = "bridge-a"),
                AppTab("empty-opencode", "opencode2-app", sessionId = "", bridgeProfileId = "bridge-a"),
                AppTab("claude", "claude-app", sessionId = "cl1", bridgeProfileId = "bridge-a"),
                AppTab("other-bridge", "opencode2-app", sessionId = "h2", bridgeProfileId = "bridge-b"),
                AppTab("landing", "", sessionId = "", bridgeProfileId = "bridge-a"),
            ),
            activeTabId = "active",
        )

        assertEquals(
            listOf(
                BackendWarmTarget("opencode2-app", "h1"),
                BackendWarmTarget("codex-app", "c1"),
                // cowork sekmesi saglayicisina (codex-app) esleniyor
                BackendWarmTarget("codex-app", "o1"),
                // sessionId bos olsa da backend hedefi korunur
                BackendWarmTarget("opencode2-app"),
            ),
            openTabWarmTargets(state),
        )
    }

    @Test
    fun testOpenTabWarmTargetsBecomeEmptyAfterLastSupportedTabCloses() {
        val state = RemoteUiState(
            activeBridgeProfileId = "bridge-a",
            openTabs = listOf(
                AppTab("landing", "", sessionId = "", bridgeProfileId = "bridge-a"),
                AppTab("claude", "claude-app", sessionId = "cl1", bridgeProfileId = "bridge-a"),
            ),
        )

        assertTrue(openTabWarmTargets(state).isEmpty())
    }

    @Test
    fun testWarmOpenTabBackendsPostsAllTargetsToBridge() = runBlocking {
        val client = FakeBridgeClient()
        client.warmOpenTabBackends(
            BridgeSettings("http://localhost", "token"),
            listOf(
                BackendWarmTarget("opencode2-app", "h1"),
                BackendWarmTarget("codex-app", "c1", "C:\\work"),
            ),
        )

        val request = client.recordedRequests.single()
        assertEquals("POST", request.method)
        assertEquals("/backends/warm", request.path)
        val targets = request.body!!.getJSONArray("targets")
        assertEquals("opencode2-app", targets.getJSONObject(0).getString("backend"))
        assertEquals("h1", targets.getJSONObject(0).getString("sessionId"))
        assertEquals("codex-app", targets.getJSONObject(1).getString("backend"))
        assertEquals("c1", targets.getJSONObject(1).getString("sessionId"))
        assertEquals("C:\\work", targets.getJSONObject(1).getString("cwd"))
    }

    @Test
    fun testUnknownBackendDiskSessions() {
        val state = RemoteUiState()
        val list = state.backendDiskSessions("unknown-backend")
        assertTrue(list.isEmpty())
    }

    @Test
    fun liteEditionOnlyShowsSessionsInsideItsWorkspaceRoot() {
        val inside = ClaudeDiskSession(id = "inside", cwd = "C:\\Users\\x\\AgentBridge-Lite\\is", title = "eşimin", lastText = "", turns = 0, mtime = 0)
        val outside = ClaudeDiskSession(id = "outside", cwd = "C:\\Users\\x\\projeler", title = "benim", lastText = "", turns = 0, mtime = 0)
        val state = RemoteUiState(
            liteEdition = true,
            liteWorkspaceRoot = "c:/users/x/AgentBridge-Lite",
        ).copy(claudeAppDiskSessions = listOf(inside, outside))

        assertEquals(listOf("inside"), state.backendDiskSessions("claude-app").map { it.id })
    }

    @Test
    fun liteEditionDoesNotExposeSessionsUntilWorkspaceRootIsKnown() {
        val state = RemoteUiState(liteEdition = true)
            .copy(claudeAppDiskSessions = listOf(ClaudeDiskSession(id = "s1", cwd = "C:/any", title = "", lastText = "", turns = 0, mtime = 0)))

        assertTrue(state.backendDiskSessions("claude-app").isEmpty())
    }
}
