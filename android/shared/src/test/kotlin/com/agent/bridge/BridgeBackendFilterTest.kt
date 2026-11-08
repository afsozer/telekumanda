package com.agent.bridge

import org.junit.Test
import org.junit.Assert.assertEquals

class BridgeBackendFilterTest {
    private val order = listOf("claude-app", "codex-app", "omp", "cowork")
    private val all = order.toSet()
    private fun catalog(vararg ids: String) =
        BackendCatalogInfo(1, ids.associateWith { BackendCapabilities() })

    @Test fun macKoprusuYalnizSunduklariniGosterir() {
        assertEquals(
            listOf("claude-app", "omp"),
            availableBackendIds(order, all, catalog("claude-app", "omp")),
        )
    }

    @Test fun katalogYoksaKullaniciAyariGecerli() {
        assertEquals(order, availableBackendIds(order, all, null))
        assertEquals(order, availableBackendIds(order, all, BackendCatalogInfo()))
    }

    @Test fun kullanicininGizledigiKatalogdaOlsaDaGizliKalir() {
        assertEquals(
            listOf("omp"),
            availableBackendIds(order, all - "claude-app", catalog("claude-app", "omp")),
        )
    }

    @Test fun siraKorunur() {
        assertEquals(
            listOf("omp", "claude-app"),
            availableBackendIds(listOf("omp", "claude-app"), all, catalog("claude-app", "omp")),
        )
    }
}
