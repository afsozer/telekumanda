package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendLabelsTest {
    @Test
    fun liteYeniOturumEtiketleriKisaVeKullaniciyaYoneliktir() {
        val lite = RemoteUiState(liteEdition = true)

        assertEquals("Claude", lite.userFacingBackendLabel("claude-app"))
        assertEquals("Codex", lite.userFacingBackendLabel("codex-app"))
        assertEquals("Claude App", RemoteUiState().userFacingBackendLabel("claude-app"))
    }

    @Test
    fun permissionLabelsStayStable() {
        assertEquals("Normal", permissionModeLabel(""))
        assertEquals("Plan", permissionModeLabel("plan"))
        assertEquals("İzinleri atla", permissionModeLabel("bypassPermissions"))
        assertEquals("unknown", permissionModeLabel("unknown"))
    }
}
