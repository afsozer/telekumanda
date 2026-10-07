package com.agent.bridge

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Terminal

// Backend enum, BackendCapabilities, BackendCatalogInfo, backendCapabilities,
// MCP sabitleri (MCP_TARGET_*, mcpTargetFor, mcpTargetLabel, mcpEffectNote) shared
// modülündedir (BackendCatalog.kt). Burada yalnız Compose ikon içeren UI kataloğu var.

internal data class BackendInfo(val id: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
internal val BACKEND_CATALOG: List<BackendInfo> = listOf(
    BackendInfo("claude-app", "Claude App", Icons.Default.Terminal),
    BackendInfo("agy", "Antigravity CLI", Icons.Default.Terminal),
    BackendInfo("codex-app", "Codex App", Icons.Default.Code),
    BackendInfo("opencode2-app", "OpenCode", Icons.Default.Code),
    BackendInfo("omp", "OMP", Icons.Default.Terminal),
    BackendInfo("cowork", "Cowork", Icons.Default.Description),
)
// BACKEND_LABELS shared modülünde (BackendCatalog.kt); burada yalnız Compose ikon haritası.
internal val BACKEND_ICONS: Map<String, androidx.compose.ui.graphics.vector.ImageVector> = BACKEND_CATALOG.associate { it.id to it.icon }

internal fun orderedVisibleBackends(uiState: RemoteUiState): List<BackendInfo> =
    uiState.availableBackendIds().mapNotNull { id ->
        BACKEND_CATALOG.find { it.id == id }?.let { info ->
            if (uiState.liteEdition) info.copy(label = backendShortLabel(id)) else info
        }
    }

internal fun RemoteUiState.userFacingBackendLabel(id: String, short: Boolean = false): String =
    if (liteEdition) backendShortLabel(id)
    else if (short) backendShortLabel(id) else BACKEND_LABELS[id] ?: id

internal fun RemoteUiState.userFacingProviderMonogram(id: String): String =
    providerMonogram(id)
