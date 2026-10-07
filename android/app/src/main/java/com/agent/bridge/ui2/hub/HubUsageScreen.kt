package com.agent.bridge.ui2.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.agent.bridge.RunPodStatus
import com.agent.bridge.RUNPOD_USAGE_CARD_KEY
import com.agent.bridge.UsageBucket
import com.agent.bridge.creditAbundant
import com.agent.bridge.formatIsoTime
import com.agent.bridge.UsageGroup
import com.agent.bridge.hiddenUsageCardCount
import com.agent.bridge.isUsageCardVisible
import com.agent.bridge.usageCardToggles
import com.agent.bridge.visibleUsageGroups
import com.agent.bridge.ui2.components.EmptyState
import com.agent.bridge.ui2.components.adaptiveColumnCount
import com.agent.bridge.ui2.components.adaptiveItems
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.chat.runPodPillLabel
import com.agent.bridge.ui2.chat.runPodShouldStop
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens
import kotlin.math.roundToInt
import java.util.Locale

@Composable
fun HubUsageScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) {
        actions.loadUsage()
        actions.refreshRunPodStatus(showErrors = false)
    }
    val usage = uiState.usage
    val runpodEnabled = uiState.opencode.runpod.enabled
    var editingCards by remember { mutableStateOf(false) }
    // Gizleme, sıralamadan ÖNCE uygulanır: Claude bloğuna göre bölen mantık
    // gizlenmiş kartları saymasın, yoksa RunPod kartı yanlış yere düşer.
    val shownGroups = visibleUsageGroups(usage.groups, uiState.hiddenUsageCards)
    val (throughClaude, afterClaude) = splitUsageGroupsAfterClaude(shownGroups)
    val showRunpod = runpodEnabled && isUsageCardVisible(RUNPOD_USAGE_CARD_KEY, uiState.hiddenUsageCards)
    val hiddenCount = hiddenUsageCardCount(usage.groups, runpodEnabled, uiState.hiddenUsageCards)

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(
            title = "Kullanım",
            subtitle = usage.note.ifBlank { "Sağlayıcı limitleri ve yenilenme pencereleri" },
            onBack = onBack,
            trailing = {
                // Kart listesi boşken düzenlenecek bir şey de yok; tuş yalnız
                // gerçekten kart varken çizilir.
                if (usage.groups.isNotEmpty() || runpodEnabled) {
                    IconButton(onClick = { editingCards = true }) {
                        Icon(
                            Icons.Default.Tune,
                            if (hiddenCount > 0) "Kartları düzenle ($hiddenCount gizli)" else "Kartları düzenle",
                            tint = if (hiddenCount > 0) Ui2.colors.accent else Ui2.colors.ink2,
                        )
                    }
                }
                // force=true: köprüdeki 5 dk'lık limit önbelleğini de atla, yoksa
                // tuş o süre boyunca aynı sayıları geri getiriyordu.
                IconButton(onClick = {
                    actions.loadUsage(force = true)
                    actions.refreshRunPodStatus(showErrors = false)
                }, enabled = !uiState.usageLoading) {
                    if (uiState.usageLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Ui2.colors.ink2, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, "Yenile", tint = Ui2.colors.ink2)
                    }
                }
            },
        )
        if (usage.groups.isEmpty() && !runpodEnabled) {
            EmptyState(
                title = "Kullanım bilgisi alınamadı",
                description = "Köprü bağlıysa yeniden yüklemeyi deneyebilirsin.",
                icon = Icons.Outlined.Assessment,
                actionLabel = "Yenile",
                onAction = {
                    actions.loadUsage(force = true)
                    actions.refreshRunPodStatus(showErrors = false)
                },
            )
        } else if (throughClaude.isEmpty() && afterClaude.isEmpty() && !showRunpod) {
            // Kart VAR ama hepsi gizli. "Alınamadı" demek yanıltıcı olurdu ve
            // kullanıcıyı çıkışsız bırakırdı; doğrudan geri getirme yolu sun.
            EmptyState(
                title = "Tüm kartlar gizli",
                description = "Kullanım kartlarının hepsini gizlemişsin. Hepsini geri getirebilir ya da tek tek seçebilirsin.",
                icon = Icons.Outlined.Assessment,
                actionLabel = "Hepsini göster",
                onAction = { actions.showAllUsageCards() },
            )
        } else {
            // Tablet/yatayda saglayici kartlari iki sutun (Merkez ile ayni esik).
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = adaptiveColumnCount(maxWidth)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Ui2Tokens.screenPadding,
                        end = Ui2Tokens.screenPadding,
                        top = Ui2Tokens.s8,
                        bottom = Ui2Tokens.s28,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
                ) {
                    adaptiveItems(throughClaude, columns, key = { "${it.source}:${it.name}" }) { group ->
                        UsageGroupCard(group)
                    }
                    if (showRunpod) {
                        item(key = "runpod") {
                            RunPodUsageCard(
                                status = uiState.opencode.runpod,
                                onStart = actions::startRunPod,
                                onStop = actions::stopRunPod,
                            )
                        }
                    }
                    adaptiveItems(afterClaude, columns, key = { "${it.source}:${it.name}" }) { group ->
                        UsageGroupCard(group)
                    }
                }
            }
        }
    }

    if (editingCards) {
        UsageCardVisibilitySheet(
            rows = usageCardToggles(usage.groups, runpodEnabled, uiState.hiddenUsageCards),
            onToggle = actions::setUsageCardVisible,
            onShowAll = actions::showAllUsageCards,
            onDismiss = { editingCards = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageCardVisibilitySheet(
    rows: List<com.agent.bridge.UsageCardToggle>,
    onToggle: (String, Boolean) -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ui2.colors.surface,
        shape = RoundedCornerShape(topStart = Ui2Tokens.cornerSheet, topEnd = Ui2Tokens.cornerSheet),
    ) {
        Column(
            Modifier
                .padding(horizontal = Ui2Tokens.screenPadding)
                .padding(bottom = Ui2Tokens.sheetBottom),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
        ) {
            Text("Kullanım kartları", style = MaterialTheme.typography.titleMedium, color = Ui2.colors.ink)
            UsageCardVisibilityList(rows = rows, onToggle = onToggle, onShowAll = onShowAll)
        }
    }
}

/**
 * Göster/gizle satırları — sheet'ten BAĞIMSIZ. Merkez > Kullanım bunu bir
 * ModalBottomSheet içinde, sohbetteki "Kalan kullanım" pill'i ise ZATEN açık
 * olan sheet'in içinde satır içi açar: sheet içinde sheet açmak Compose'da
 * kötü davranıyor (üstteki kapanınca alttaki de kapanıyor).
 */
@Composable
internal fun UsageCardVisibilityList(
    rows: List<com.agent.bridge.UsageCardToggle>,
    onToggle: (String, Boolean) -> Unit,
    onShowAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4)) {
        Text(
            "Kapattığın kart listeden kalkar; veri yine köprüden gelmeye devam eder.",
            style = MaterialTheme.typography.bodySmall,
            color = Ui2.colors.ink2,
        )
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = Ui2Tokens.s4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(row.label, style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink)
                Switch(
                    checked = row.visible,
                    onCheckedChange = { onToggle(row.key, it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Ui2.colors.accent),
                )
            }
        }
        if (rows.any { !it.visible }) {
            TextButton(onClick = onShowAll) { Text("Hepsini göster", color = Ui2.colors.accent) }
        }
    }
}

internal fun splitUsageGroupsAfterClaude(groups: List<UsageGroup>): Pair<List<UsageGroup>, List<UsageGroup>> {
    if (groups.isEmpty()) return emptyList<UsageGroup>() to emptyList()
    val lastClaude = groups.indexOfLast {
        it.source.equals("claude", ignoreCase = true) || it.name.startsWith("Claude", ignoreCase = true)
    }
    val splitAt = if (lastClaude >= 0) lastClaude + 1 else 0
    return groups.take(splitAt) to groups.drop(splitAt)
}

internal fun formatRunPodUsd(value: Double?): String =
    value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "$%.2f", it) } ?: "—"

internal fun runPodRuntimeEstimate(balance: Double?, spendPerHour: Double?): String? {
    if (balance == null || spendPerHour == null || !balance.isFinite() || !spendPerHour.isFinite() || spendPerHour <= 0.0) return null
    val totalMinutes = ((balance / spendPerHour) * 60.0).toLong().coerceAtLeast(1L)
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "~${days} gün ${hours} sa"
        totalMinutes >= 60 -> "~${totalMinutes / 60} sa ${minutes} dk"
        else -> "~${totalMinutes} dk"
    }
}

@Composable
fun RunPodUsageCard(
    status: RunPodStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val active = runPodShouldStop(status)
    val busy = status.operationActive || status.phase == "starting" || status.phase == "stopping"
    val error = status.phase == "error" || !status.ok
    val tint = when {
        active -> Ui2.colors.running
        error -> Ui2.colors.danger
        else -> Ui2.colors.line
    }
    val background = when {
        active -> Ui2.colors.running.copy(alpha = 0.14f)
        error -> Ui2.colors.danger.copy(alpha = 0.07f)
        else -> Ui2.colors.surface
    }
    val statusText = when {
        status.phase == "starting" -> "Başlatılıyor"
        status.phase == "stopping" -> "Durduruluyor"
        status.ready -> "Çalışıyor"
        status.phase == "running" -> "Pod çalışıyor"
        error -> "Hata"
        else -> "Kapalı"
    }
    val statusKind = when {
        busy -> StatusKind.Attention
        error -> StatusKind.Danger
        active -> StatusKind.Running
        else -> StatusKind.Done
    }
    val estimate = runPodRuntimeEstimate(status.clientBalance, status.currentSpendPerHr)

    SurfaceCard(
        verticalGap = Ui2Tokens.s12,
        containerColor = background,
        borderColor = tint.copy(alpha = if (active || error) 0.7f else 1f),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RunPod", style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
                Text(
                    status.message.ifBlank { "RunPod GPU ve hesap durumu" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.ink2,
                )
            }
            StatusBadge(statusText, statusKind)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Kalan kredi", style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                Text(
                    if (status.billingAvailable) formatRunPodUsd(status.clientBalance) else "Alınamadı",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Ui2.colors.ink,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Anlık harcama", style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                Text(
                    if (status.billingAvailable) "${formatRunPodUsd(status.currentSpendPerHr)}/saat" else "—",
                    style = MaterialTheme.typography.titleSmall,
                    color = Ui2.colors.ink2,
                )
            }
        }

        if (estimate != null) {
            Text("Mevcut harcama değişmezse $estimate kullanım.", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
        }
        status.spendLimit?.takeIf { it.isFinite() }?.let {
            Text("Hesap harcama sınırı: ${formatRunPodUsd(it)}/saat", style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    busy -> runPodPillLabel(status)
                    active -> "RunPod açık"
                    else -> "RunPod kapalı"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = if (active) Ui2.colors.running else Ui2.colors.ink2,
            )
            Switch(
                checked = active,
                onCheckedChange = { checked -> if (checked) onStart() else onStop() },
                enabled = status.enabled && !busy,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Ui2.colors.surface,
                    checkedTrackColor = Ui2.colors.running,
                    uncheckedThumbColor = Ui2.colors.ink3,
                    uncheckedTrackColor = Ui2.colors.surface2,
                    uncheckedBorderColor = Ui2.colors.lineStrong,
                ),
            )
        }
    }
}

// Public: sohbetteki "Kalan kullanım" sheet'i AYNI bileşeni kullanır
// (anayasa 2: bir işlev iki yüzeyden erişilir ama TEK implementasyonu olur).
@Composable
fun UsageGroupCard(group: UsageGroup) {
    SurfaceCard(verticalGap = Ui2Tokens.s12) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
                if (group.description.isNotBlank()) {
                    Text(group.description, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
                }
            }
            if (group.stale) StatusBadge("Önbellek", StatusKind.Attention)
        }
        group.buckets.forEach { bucket -> UsageBucketRow(bucket) }
        if (group.buckets.isEmpty()) {
            Text("Bu sağlayıcı için ölçüm kovası yok.", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink3)
        }
    }
}

@Composable
private fun UsageBucketRow(bucket: UsageBucket) {
    val remaining = bucket.remainingFraction.coerceIn(0.0, 1.0)
    val kind = when {
        remaining >= 0.5 -> StatusKind.Done
        remaining >= 0.15 -> StatusKind.Attention
        else -> StatusKind.Danger
    }
    // Bakiye kartlarında 10 $ = dolu çubuk; 10 $ üstü bakiye eşiklerle değil
    // marka vurgusuyla çizilir — "bol var" bilgisi yeşilden ayrışsın.
    val color = when {
        bucket.creditAbundant() -> Ui2.colors.accent
        kind == StatusKind.Done -> Ui2.colors.done
        kind == StatusKind.Attention -> Ui2.colors.attention
        kind == StatusKind.Danger -> Ui2.colors.danger
        else -> Ui2.colors.running
    }

    Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                bucket.label.ifBlank { bucket.window },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Ui2.colors.ink,
            )
            Text(
                bucket.value.ifBlank { "%${(remaining * 100).roundToInt()}" },
                style = MaterialTheme.typography.labelMedium,
                color = if (bucket.metered) color else Ui2.colors.ink2,
            )
        }
        if (bucket.metered) {
            LinearProgressIndicator(
                progress = { remaining.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = color,
                trackColor = Ui2.colors.surface2,
            )
        }
        if (bucket.description.isNotBlank()) {
            Text(bucket.description, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink3)
        }
        if (bucket.resetTime.isNotBlank()) {
            // Ham ISO damgası ("2026-07-24T04:59:59.613312+00:00") okunmuyordu;
            // yerel saate çevrilip kısa biçimde basılır.
            Text("Yenilenme: ${formatIsoTime(bucket.resetTime)}", style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
        }
    }
}
