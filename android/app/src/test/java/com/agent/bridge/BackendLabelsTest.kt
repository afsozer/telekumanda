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
        assertEquals("OpenCode", lite.userFacingBackendLabel("opencode2-app"))
        assertEquals("Antigravity", lite.userFacingBackendLabel("agy"))
        assertEquals("A", lite.userFacingProviderMonogram("agy"))
        assertEquals("Claude App", RemoteUiState().userFacingBackendLabel("claude-app"))
    }

    @Test
    fun runpodModelIsASingleId() {
        assertTrue(isRunPodModel("runpod/runpod"))
        // Büyük harfli yazım ve boşluk: köprü de kimliği küçültüp karşılaştırıyor.
        assertTrue(isRunPodModel("  RunPod/RunPod  "))
        assertTrue(isRunPodModel("RUNPOD/RUNPOD"))
        // Sağlayıcı taraması DEĞİL: pod yalnız bu model için açılır.
        assertFalse(isRunPodModel("runpod/baska"))
        assertFalse(isRunPodModel("deepseek/deepseek-flash"))
        assertFalse(isRunPodModel(""))
    }

    @Test
    fun permissionLabelsStayStable() {
        assertEquals("Normal", permissionModeLabel(""))
        assertEquals("Plan", permissionModeLabel("plan"))
        assertEquals("İzinleri atla", permissionModeLabel("bypassPermissions"))
        assertEquals("unknown", permissionModeLabel("unknown"))
    }
}
