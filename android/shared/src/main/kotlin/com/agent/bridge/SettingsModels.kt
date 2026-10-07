package com.agent.bridge

data class SettingsProviderItem(
    val id: String,
    val label: String,
    val visible: Boolean,
    val position: Int,
    val sessionCount: Int,
    val processCount: Int,
)

data class SettingsSummary(
    val connectionHealthy: Boolean,
    val protocolCompatible: Boolean,
    val devicePaired: Boolean,
    val notificationsEnabled: Boolean,
    val visibleProviderCount: Int,
    val providerCount: Int,
)

data class SettingsMcpTarget(
    val id: String,
    val label: String,
    val supportsToggle: Boolean,
    val effectNote: String,
    val supportsEdit: Boolean = true,
)

val SETTINGS_MCP_TARGETS = listOf(
    SettingsMcpTarget(MCP_TARGET_CLAUDE, "Claude", false, "Yeni Claude oturumunda etkin olur"),
    SettingsMcpTarget("codex-app", "Codex", true, "Yeni Codex oturumunda etkin olur"),
    SettingsMcpTarget("opencode2-app", "OpenCode", true, "OpenCode sunucusu yeniden başlayınca etkin olur"),
    SettingsMcpTarget("omp", "OMP", false, "Çalışan OMP runtime envanteri", supportsEdit = false),
    SettingsMcpTarget(MCP_TARGET_ANTIGRAVITY, "Antigravity", true, "Canlı yenileme desteklenir"),
)

fun RemoteUiState.settingsProviders(): List<SettingsProviderItem> = backendOrder.mapIndexed { index, id ->
    SettingsProviderItem(
        id = id,
        label = BACKEND_LABELS[id] ?: id,
        visible = id in visibleBackends,
        position = index,
        sessionCount = backendSessionCounts[id] ?: 0,
        processCount = backendProcessCounts[id] ?: 0,
    )
}

fun RemoteUiState.settingsSummary(): SettingsSummary = SettingsSummary(
    connectionHealthy = healthOk,
    protocolCompatible = protocolCompatible,
    devicePaired = devicePaired,
    // Ayar yok: tam sürümde bildirim kanalı hep açık, Lite'ta hiç yok.
    notificationsEnabled = !liteEdition,
    visibleProviderCount = backendOrder.count { it in visibleBackends },
    providerCount = backendOrder.size,
)
