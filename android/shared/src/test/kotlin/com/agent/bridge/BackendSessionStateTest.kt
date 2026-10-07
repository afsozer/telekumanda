package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSessionStateTest {

    @Test
    fun testClaudeAppBackendSessionMapping() {
        val state = RemoteUiState().copy(
            claudeAppSessionId = "s1",
            lastClaudeAppSessionId = "last_s1",
            claudeAppCwd = "/cwd/claude",
            claudeAppModel = "m",
            claudeAppDefaultModel = "dm",
            claudeAppPermissionMode = "plan",
            claudeAppEffort = "high",
            claudeAppSetupPending = true,
            claudeAppDiskLoading = true
        )
        val session = state.backendSession("claude-app")
        assertEquals("s1", session.sessionId)
        assertEquals("last_s1", session.lastSessionId)
        assertEquals("/cwd/claude", session.cwd)
        assertEquals("m", session.model)
        assertEquals("dm", session.defaultModel)
        assertEquals("plan", session.permissionMode)
        assertEquals("high", session.effort)
        assertTrue(session.setupPending)
        assertTrue(session.diskLoading)
    }

    @Test
    fun testCodexAppBackendSessionMapping() {
        val state = RemoteUiState().copy(
            codexAppSessionId = "cs1",
            lastCodexAppSessionId = "last_cs1",
            codexAppCwd = "/cwd/codex",
            codexAppModel = "cm",
            codexAppDefaultModel = "cdm",
            codexAppPermissionMode = "yolo",
            codexAppEffort = "low",
            codexAppSetupPending = false,
            codexAppDiskLoading = false
        )
        val session = state.backendSession("codex-app")
        assertEquals("cs1", session.sessionId)
        assertEquals("last_cs1", session.lastSessionId)
        assertEquals("/cwd/codex", session.cwd)
        assertEquals("cm", session.model)
        assertEquals("cdm", session.defaultModel)
        assertEquals("yolo", session.permissionMode)
        assertEquals("low", session.effort)
        assertFalse(session.setupPending)
        assertFalse(session.diskLoading)
    }

    @Test
    fun testCoworkAskModeWithSpecificProjectPath() {
        val state = RemoteUiState().copy(
            cowork = CoworkUiState(provider = "codex-app", activeProjectPath = "C:/p", yoloMode = false, sessionsLoading = false),
            codexAppSessionId = "cs",
            lastCoworkSessionId = "last_cowork_id",
        )
        val session = state.backendSession("cowork")
        assertEquals("cs", session.sessionId)
        assertEquals("last_cowork_id", session.lastSessionId)
        assertEquals("C:/p", session.cwd)
        assertEquals("onay", session.permissionMode)
        assertFalse(session.diskLoading)
    }

    @Test
    fun testCoworkYoloModeWithFallbackCwd() {
        val state = RemoteUiState().copy(
            cowork = CoworkUiState(provider = "codex-app", yoloMode = true, sessionsLoading = true),
            codexAppSessionId = "cs",
            codexAppCwd = "C:/fallback_codex",
            lastCoworkSessionId = "last_cowork_id",
        )
        val session = state.backendSession("cowork")
        assertEquals("cs", session.sessionId)
        assertEquals("C:/fallback_codex", session.cwd)
        assertEquals("yolo", session.permissionMode)
        assertTrue(session.diskLoading)
    }

    @Test
    fun testUnknownBackendSessionMapping() {
        val state = RemoteUiState().copy(
            claudeAppSessionId = "s1"
        )
        val session = state.backendSession("yok-boyle-backend")
        assertEquals(BackendSessionState(), session)
    }
}
