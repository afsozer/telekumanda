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
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.CircularProgressIndicator
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
    var purgeConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        actions.loadAllBackendSessionCounts()
        // Onbellekli durum: ekran her acilista 300 MB taramaz, kullanici
        // "Tara" derse tazelenir.
        actions.loadPurgeStatus()
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
            SessionPurgeCard(uiState, actions) { purgeConfirm = true }
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

    if (purgeConfirm) {
        ConfirmDialog(
            title = "Silinmiş oturum kalıntıları imha edilsin mi?",
            text = "Masaüstündeki opencode süreçleri durdurulacak (açık oturumlar düşer), " +
                "veritabanı sıkıştırılacak, loglar boşaltılacak ve 4096 servisi geri kaldırılacak. " +
                "İşlem birkaç dakika sürebilir ve geri alınamaz.",
            confirmLabel = "İmha et",
            onConfirm = {
                purgeConfirm = false
                actions.runSessionPurge()
            },
            onDismiss = { purgeConfirm = false },
        )
    }
}

/**
 * OpenCode'da oturum silmek icerigi yok etmiyor: SQLite freelist sayfalari ve
 * checkpoint edilmemis -wal cerceveleri mesaj metnini aynen tutuyor
 * (30.09.2026'da silinmis bir oturum birebir geri getirildi). Bu kart o
 * kalintilari sayar ve masaustundeki oturum-imha.ps1'i tetikler.
 *
 * Geri donusu olmayan bayraklar (-EskiDb: 24 Eyl yedegini de siler,
 * -Snapshot) bilerek TELEFONDA YOK; onlar masaustunde elle verilir.
 */
@Composable
private fun SessionPurgeCard(uiState: RemoteUiState, actions: RemoteViewModel, onPurge: () -> Unit) {
    val purge = uiState.opencode.purge
    SurfaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.DeleteForever, null, tint = Ui2.colors.ink2)
            Column(Modifier.weight(1f).padding(horizontal = Ui2Tokens.s12)) {
                Text("Silinmiş oturum kalıntıları", style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
                Text(
                    purge.message.ifBlank { "Durum için tara" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.ink2,
                )
                if (purge.deadArchived > 0) {
                    // Bu sayi varsayilan imhaya GIRMIYOR; kart bunu ayri
                    // satirda soyluyor, yoksa "temiz" yaniltici olur.
                    Text(
                        "Eski/kopya veritabanlarında ${purge.deadArchived} sohbet duruyor — masaüstünden -EskiDb ile gider",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ui2.colors.ink3,
                    )
                }
            }
            when {
                purge.running -> CircularProgressIndicator(Modifier.size(18.dp), color = Ui2.colors.ink2)
                purge.deadActive > 0 -> StatusBadge("${purge.deadActive} iz", StatusKind.Attention)
                purge.clean == true -> StatusBadge("Temiz", StatusKind.Done)
                else -> Unit
            }
        }
        if (purge.running && purge.lines.isNotEmpty()) {
            Text(
                purge.lines.last(),
                style = MaterialTheme.typography.bodySmall,
                color = Ui2.colors.ink3,
                modifier = Modifier.padding(top = Ui2Tokens.s8),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.loadPurgeStatus(refresh = true) }, enabled = !purge.running) {
                Icon(Icons.Default.Refresh, null, tint = Ui2.colors.ink2)
                Text("Tara", color = Ui2.colors.ink2)
            }
            TextButton(onClick = onPurge, enabled = purge.enabled && !purge.running) {
                Icon(Icons.Outlined.DeleteForever, null, tint = Ui2.colors.danger)
                Text("İmha et", color = Ui2.colors.danger)
            }
        }
    }
}
