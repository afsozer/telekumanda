package com.agent.bridge

import org.junit.Test
import org.junit.Assert.assertEquals

class BridgeBackendFilterTest {
    private val order = listOf("claude-app", "agy", "codex-app", "opencode2-app", "omp", "cowork")
    private val all = order.toSet()
    private fun catalog(vararg ids: String) =
        BackendCatalogInfo(1, ids.associateWith { BackendCapabilities() })

    @Test fun macKoprusuYalnizSunduklariniGosterir() {
        assertEquals(
            listOf("claude-app", "opencode2-app"),
            availableBackendIds(order, all, catalog("claude-app", "opencode2-app")),
        )
    }

    @Test fun katalogYoksaKullaniciAyariGecerli() {
        assertEquals(order, availableBackendIds(order, all, null))
        assertEquals(order, availableBackendIds(order, all, BackendCatalogInfo()))
    }

    @Test fun kullanicininGizledigiKatalogdaOlsaDaGizliKalir() {
        assertEquals(
            listOf("opencode2-app"),
            availableBackendIds(order, all - "claude-app", catalog("claude-app", "opencode2-app")),
        )
    }

    @Test fun siraKorunur() {
        assertEquals(
            listOf("omp", "claude-app"),
            availableBackendIds(listOf("omp", "agy", "claude-app"), all, catalog("claude-app", "omp")),
        )
    }
}
