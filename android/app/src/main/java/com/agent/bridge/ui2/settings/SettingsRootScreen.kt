package com.agent.bridge.ui2.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.bridge.RemoteUiState
import com.agent.bridge.settingsSummary
import com.agent.bridge.ui2.components.AdaptiveCells
import com.agent.bridge.ui2.components.ContentWidth
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SectionHeader
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

@Composable
fun SettingsRootScreen(
    uiState: RemoteUiState,
    onOpenConnection: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val summary = uiState.settingsSummary()
    ContentWidth(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
      Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "Ayarlar", subtitle = "Bağlantı, sağlayıcılar ve uygulama yönetimi")
        Column(
            Modifier.padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s16),
        ) {
            SectionHeader("Bağlantı ve güvenlik")
            SurfaceCard {
                SettingsLinkRow(
                    title = "Bağlantı ve cihaz eşleme",
                    detail = when {
                        !summary.protocolCompatible -> "Bridge protokolü uyumsuz"
                        summary.connectionHealthy && summary.devicePaired -> "Bağlı · cihaz anahtarı etkin"
                        summary.connectionHealthy -> "Bağlı · ortak token"
                        else -> "Bağlantı yok"
                    },
                    icon = Icons.Outlined.Link,
                    badge = if (summary.connectionHealthy && summary.protocolCompatible) "Bağlı" else "Kontrol et",
                    badgeKind = if (summary.connectionHealthy && summary.protocolCompatible) StatusKind.Done else StatusKind.Danger,
                    onClick = onOpenConnection,
                )
            }

            SectionHeader("Sağlayıcılar")
            SurfaceCard {
                // Tablet/yatayda satirlar iki sutun — Merkez'deki AdaptiveCells
                // deseniyle ayni; telefonda tek sutun, eski yerlesim.
                AdaptiveCells(listOf(
                {
                SettingsLinkRow(
                    title = "Görünürlük ve sıra",
                    detail = "${summary.visibleProviderCount}/${summary.providerCount} sağlayıcı görünür",
                    icon = Icons.Outlined.Hub,
                    onClick = onOpenProviders,
                )
                }, {
                }, {
                SettingsLinkRow(
                    title = "MCP sunucuları",
                    detail = "Claude, Codex, OpenCode, OMP ve Antigravity",
                    icon = Icons.Outlined.DeveloperMode,
                    onClick = onOpenMcp,
                )
                },
                ))
            }

            SectionHeader("Uygulama")
            SurfaceCard {
                AdaptiveCells(listOf(
                {
                SettingsLinkRow(
                    title = "Bildirimler",
                    detail = if (summary.notificationsEnabled) "Onay ve tur bildirimleri açık" else "Kapalı",
                    icon = Icons.Outlined.Notifications,
                    onClick = onOpenNotifications,
                )
                }, {
                SettingsLinkRow(
                    title = "Güncelleme",
                    detail = "OTA sürümünü kontrol et ve yükle",
                    icon = Icons.Outlined.SystemUpdate,
                    onClick = onOpenUpdate,
                )
                }, {
                SettingsLinkRow(
                    title = "Gelişmiş",
                    detail = "Oturumlar, süreçler ve toplu sonlandırma",
                    icon = Icons.Outlined.Security,
                    onClick = onOpenAdvanced,
                )
                },
                ))
            }

        }
      }
    }
}

@Composable
private fun SettingsLinkRow(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badge: String? = null,
    badgeKind: StatusKind? = null,
    onClick: () -> Unit,
) {
    ListRow(
        title = title,
        detail = detail,
        leading = { Icon(icon, null, tint = Ui2.colors.ink2, modifier = Modifier.size(22.dp)) },
        trailing = {
            if (badge != null) StatusBadge(badge, badgeKind)
            Icon(Icons.Default.ChevronRight, null, tint = Ui2.colors.ink3, modifier = Modifier.size(18.dp))
        },
        onClick = onClick,
    )
}
