package com.agent.bridge.ui2.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.SettingsProviderItem
import com.agent.bridge.settingsProviders
import com.agent.bridge.ui2.components.ConfirmDialog
import com.agent.bridge.ui2.components.ProviderMark
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.providerMonogram
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

@Composable
fun SettingsProvidersScreen(uiState: RemoteUiState, actions: RemoteViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(title = "Sağlayıcılar", subtitle = "Görünürlük ve yeni oturum sırası", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
        ) {
            Text(
                "Bu sıra yeni oturum akışında ve sağlayıcı listelerinde kullanılır; uygulama kendiliğinden sağlayıcı seçmez.",
                style = MaterialTheme.typography.bodySmall,
                color = Ui2.colors.ink3,
            )
            SurfaceCard {
                uiState.settingsProviders().forEach { provider ->
                    ProviderSettingsRow(provider, uiState.backendOrder.size, actions)
                }
            }
        }
    }
}

@Composable
private fun ProviderSettingsRow(provider: SettingsProviderItem, total: Int, actions: RemoteViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Ui2Tokens.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
    ) {
        Column {
            IconButton(
                onClick = { actions.moveBackend(provider.position, provider.position - 1) },
                enabled = provider.position > 0,
                modifier = Modifier.size(28.dp),
            ) { Icon(Icons.Default.ExpandLess, "Yukarı", tint = Ui2.colors.ink2, modifier = Modifier.size(18.dp)) }
            IconButton(
                onClick = { actions.moveBackend(provider.position, provider.position + 1) },
                enabled = provider.position < total - 1,
                modifier = Modifier.size(28.dp),
            ) { Icon(Icons.Default.ExpandMore, "Aşağı", tint = Ui2.colors.ink2, modifier = Modifier.size(18.dp)) }
        }
        ProviderMark(providerMonogram(provider.id))
        Column(Modifier.weight(1f)) {
            Text(provider.label, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
            Text(provider.id, style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
        }
        Switch(
            checked = provider.visible,
            onCheckedChange = { actions.toggleBackendVisibility(provider.id) },
            colors = SwitchDefaults.colors(
                checkedTrackColor = Ui2.colors.accent,
                checkedThumbColor = Ui2.colors.onAccent,
            ),
        )
    }
}

@Composable
fun SettingsAdvancedScreen(uiState: RemoteUiState, actions: RemoteViewModel, onBack: () -> Unit) {
    var killTarget by remember { mutableStateOf<SettingsProviderItem?>(null) }
    LaunchedEffect(Unit) {
        actions.loadAllBackendSessionCounts()
    }

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(
            title = "Gelişmiş",
            subtitle = "Canlı oturum ve masaüstü süreç yönetimi",
            onBack = onBack,
            trailing = {
                IconButton(onClick = actions::loadAllBackendSessionCounts) {
                    Icon(Icons.Default.Refresh, "Yenile", tint = Ui2.colors.ink2)
                }
            },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
        ) {
            Text(
                "Toplu sonlandırma, seçilen sağlayıcının canlı süreçlerini kapatır ve bellekteki oturum kayıtlarını temizler. Disk transcriptleri silinmez.",
                style = MaterialTheme.typography.bodySmall,
                color = Ui2.colors.ink3,
            )
            uiState.settingsProviders().forEach { provider ->
                SurfaceCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Memory, null, tint = Ui2.colors.ink2)
                        Column(Modifier.weight(1f).padding(horizontal = Ui2Tokens.s12)) {
                            Text(provider.label, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
                            Text(
                                "${provider.sessionCount} aktif oturum · ${provider.processCount} süreç",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ui2.colors.ink2,
                            )
                        }
                        if (provider.sessionCount > 0 || provider.processCount > 0) {
                            StatusBadge("Aktif", StatusKind.Running)
                        }
                    }
                    TextButton(
                        onClick = { killTarget = provider },
                        enabled = provider.sessionCount > 0 || provider.processCount > 0,
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, null, tint = Ui2.colors.danger)
                        Text("Tümünü sonlandır", color = Ui2.colors.danger)
                    }
                }
            }
        }
    }

    killTarget?.let { target ->
        ConfirmDialog(
            title = "${target.label} oturumları sonlandırılsın mı?",
            text = "${target.sessionCount} oturum kaydı ve ${target.processCount} masaüstü süreci sonlandırılacak.",
            confirmLabel = "Tümünü sonlandır",
            onConfirm = {
                killTarget = null
                actions.killAllSessions(target.id)
            },
            onDismiss = { killTarget = null },
        )
    }
}
