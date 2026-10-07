package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelsTest {

    @Test
    fun bridgeProfilesJsonRoundTripsAllConnectionFields() {
        val profiles = listOf(
            BridgeProfile("laptop", "Köprü 1", "http://100.64.0.1:8787", "bir"),
            BridgeProfile("desktop", "Masaüstü", "https://bridge.example", "iki"),
        )

        assertEquals(profiles, BridgeProfilesJson.decode(BridgeProfilesJson.encode(profiles)))
    }

    @Test
    fun bridgeProfilesJsonSkipsEntriesWithoutIdentity() {
        val decoded = BridgeProfilesJson.decode(
            """[{"name":"Eksik"},{"id":"ok","name":"Köprü","baseUrl":"http://pc","token":""}]""",
        )

        assertEquals(listOf(BridgeProfile("ok", "Köprü", "http://pc", "")), decoded)
    }

    @Test
    fun settingsProvidersPreserveOrderVisibilityAndCounts() {
        val state = RemoteUiState().copy(
            backendOrder = listOf("codex-app", "claude-app", "agy"),
            visibleBackends = setOf("claude-app", "agy"),
            backendSessionCounts = mapOf("codex-app" to 2),
            backendProcessCounts = mapOf("codex-app" to 1, "agy" to 3),
        )

        val providers = state.settingsProviders()
        assertEquals(listOf("codex-app", "claude-app", "agy"), providers.map { it.id })
        assertFalse(providers[0].visible)
        assertEquals(2, providers[0].sessionCount)
        assertEquals(1, providers[0].processCount)
        assertTrue(providers[1].visible)
        assertEquals(3, providers[2].processCount)
    }

    @Test
    fun settingsSummaryReportsVisibilityCount() {
        val summary = RemoteUiState().copy(
            healthOk = true,
            protocolCompatible = false,
            device = DeviceUiState(paired = true),
            backendOrder = listOf("claude-app", "agy", "codex-app"),
            visibleBackends = setOf("claude-app", "codex-app"),
        ).settingsSummary()

        assertTrue(summary.connectionHealthy)
        assertFalse(summary.protocolCompatible)
        assertTrue(summary.devicePaired)
        assertTrue(summary.notificationsEnabled)
        assertEquals(2, summary.visibleProviderCount)
        assertEquals(3, summary.providerCount)
    }


    @Test
    fun mcpTargetsExposeExplicitGlobalDestinations() {
        assertEquals(listOf("claude", "codex-app", "opencode2-app", "omp", "antigravity"), SETTINGS_MCP_TARGETS.map { it.id })
        assertFalse(SETTINGS_MCP_TARGETS.first { it.id == "claude" }.supportsToggle)
        assertTrue(SETTINGS_MCP_TARGETS.first { it.id == "codex-app" }.supportsToggle)
        assertFalse(SETTINGS_MCP_TARGETS.first { it.id == "omp" }.supportsEdit)
    }
}
