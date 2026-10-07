package com.agent.bridge.ui2.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.agent.bridge.McpServer
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.SETTINGS_MCP_TARGETS
import com.agent.bridge.SettingsMcpTarget
import com.agent.bridge.ui2.components.ConfirmDialog
import com.agent.bridge.ui2.components.EmptyState
import com.agent.bridge.ui2.components.LoadingSkeleton
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SectionHeader
import com.agent.bridge.ui2.components.SegmentedTabs
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

@Composable
fun SettingsMcpScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var selectedIndex by remember { mutableStateOf(0) }
    val target = SETTINGS_MCP_TARGETS[selectedIndex]
    var removeTarget by remember(target.id) { mutableStateOf<McpServer?>(null) }

    LaunchedEffect(target.id) { actions.loadSettingsMcpServers(target.id) }

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(
            title = "MCP sunucuları",
            subtitle = "Her hedef kendi global CLI yapılandırmasını yönetir",
            onBack = onBack,
            trailing = {
                Row {
                    IconButton(onClick = { actions.refreshSettingsMcp(target.id) }) {
                        Icon(Icons.Default.Refresh, "MCP yenile", tint = Ui2.colors.ink2)
                    }
                    if (target.supportsEdit) {
                        IconButton(onClick = { onAdd(target.id) }) {
                            Icon(Icons.Default.Add, "MCP ekle", tint = Ui2.colors.accent)
                        }
                    }
                }
            },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Ui2Tokens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
        ) {
            SegmentedTabs(
                options = SETTINGS_MCP_TARGETS.map { it.label },
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it },
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
            SurfaceCard {
                Text(
                    "${target.label} global config · ${target.effectNote}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.attention,
                )
            }

            when {
                uiState.mcpLoading -> LoadingSkeleton(rows = 4)
                uiState.mcpServers.isEmpty() -> EmptyState(
                    title = "MCP sunucusu yok",
                    description = if (target.supportsEdit) "${target.label} hedefi için yeni bir MCP sunucusu ekleyebilirsin." else "Çalışan runtime'da MCP sunucusu bulunamadı.",
                    icon = Icons.Outlined.DeveloperMode,
                    actionLabel = if (target.supportsEdit) "MCP ekle" else null,
                    onAction = if (target.supportsEdit) ({ onAdd(target.id) }) else null,
                )
                else -> uiState.mcpServers.forEach { server ->
                    McpServerCard(
                        server = server,
                        target = target,
                        onToggle = { actions.toggleSettingsMcpServer(target.id, server.name, it) },
                        onRemove = { removeTarget = server },
                    )
                }
            }
        }
    }

    removeTarget?.let { server ->
        ConfirmDialog(
            title = "MCP sunucusu silinsin mi?",
            text = "${server.name}, ${target.label} global yapılandırmasından kaldırılacak. ${target.effectNote}.",
            confirmLabel = "Sunucuyu sil",
            onConfirm = {
                removeTarget = null
                actions.removeSettingsMcpServer(target.id, server.name)
            },
            onDismiss = { removeTarget = null },
        )
    }
}

@Composable
private fun McpServerCard(
    server: McpServer,
    target: SettingsMcpTarget,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    SurfaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
                Text(
                    when {
                        server.url.isNotBlank() -> server.url
                        server.command.isNotBlank() -> server.command + if (server.args.isNotEmpty()) " ${server.args.joinToString(" ")}" else ""
                        else -> server.type
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.ink2,
                    maxLines = 2,
                )
            }
            if (server.managed) StatusBadge("Yönetilen")
            else if (target.supportsToggle) {
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Ui2.colors.accent,
                        checkedThumbColor = Ui2.colors.onAccent,
                    ),
                )
            } else {
                StatusBadge(if (server.enabled) "Etkin" else "Kapalı", if (server.enabled) StatusKind.Done else null)
            }
        }
        if (server.status.isNotBlank()) {
            Text(server.status, style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
        }
        if (!server.managed) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, "MCP sunucusunu sil", tint = Ui2.colors.danger)
            }
        }
    }
}

@Composable
fun SettingsMcpAddScreen(
    targetId: String,
    actions: RemoteViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val target = SETTINGS_MCP_TARGETS.firstOrNull { it.id == targetId } ?: SETTINGS_MCP_TARGETS.first()
    var typeIndex by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    val type = if (typeIndex == 0) "command" else "remote"

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(title = "${target.label} MCP ekle", subtitle = target.effectNote, onBack = onBack)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s16),
        ) {
            SurfaceCard {
                Text(
                    "Bu işlem ${target.label} global CLI yapılandırmasını değiştirir; proje-yerel değildir.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ui2.colors.attention,
                )
            }
            SectionHeader("Tür")
            SegmentedTabs(listOf("Komut", "Uzak URL"), typeIndex, { typeIndex = it })
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Sunucu adı") },
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (type == "remote") "URL" else "Komut") },
            )
            Button(
                onClick = {
                    actions.saveSettingsMcpServer(
                        target = target.id,
                        name = name.trim(),
                        type = type,
                        url = if (type == "remote") endpoint.trim() else "",
                        command = if (type == "command") endpoint.trim() else "",
                    )
                    onDone()
                },
                enabled = name.isNotBlank() && endpoint.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Ui2.colors.accent, contentColor = Ui2.colors.onAccent),
            ) { Text("Global config'e ekle") }
        }
    }
}
