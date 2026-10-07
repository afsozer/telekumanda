package com.agent.bridge.ui2.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.agent.bridge.OperationEvent
import com.agent.bridge.OperationItem
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.formatIsoTime
import com.agent.bridge.splitOperationEvents
import com.agent.bridge.ui2.components.EmptyState
import com.agent.bridge.ui2.components.adaptiveColumnCount
import com.agent.bridge.ui2.components.adaptiveItems
import com.agent.bridge.ui2.components.LoadingSkeleton
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SectionHeader
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.StatusDot
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

@Composable
fun HubOperationsScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    // Kök alan olduğu için geri oku YOK (Merkez/Ayarlar gibi); null geçilir.
    onBack: (() -> Unit)? = null,
    onOpenChat: () -> Unit,
) {
    LaunchedEffect(Unit) { actions.loadOperations() }
    LaunchedEffect(uiState.operations.events.firstOrNull()?.id) {
        if (uiState.operations.events.isNotEmpty()) actions.markOperationsSeen()
    }

    val result = uiState.operations
    // Geçmiş bölümü varsayılan KAPALI. Düz remember bilinçli: ekrana her
    // girişte kapalı başlasın, bayat "açık" durumu geri gelmesin.
    var historyExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(
            title = "Operasyonlar",
            subtitle = "Çalışan, bekleyen ve tamamlanan oturumlar",
            onBack = onBack,
            trailing = {
                IconButton(onClick = actions::loadOperations) {
                    Icon(Icons.Default.Refresh, "Yenile", tint = Ui2.colors.ink2)
                }
            },
        )

        if (uiState.operationsLoading && !uiState.operationsLoaded) {
            LoadingSkeleton(
                Modifier.padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s16),
                rows = 5,
            )
        } else if (result.operations.isEmpty() && result.events.isEmpty()) {
            EmptyState(
                title = "Operasyon yok",
                description = "Bir oturum çalışmaya başladığında veya yanıt beklediğinde burada görünür.",
                icon = Icons.Outlined.Terminal,
                actionLabel = "Yenile",
                onAction = actions::loadOperations,
            )
        } else {
            // Tablet/yatayda kartlar iki sütun (Merkez'deki AdaptiveCells ile
            // aynı eşik); telefonda columns=1 olur ve yerleşim eskisiyle aynı.
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
                    item {
                        SurfaceCard {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                OperationCount("Çalışıyor", result.counts.running, StatusKind.Running)
                                OperationCount("Bekliyor", result.counts.waiting, StatusKind.Attention)
                                OperationCount("Hata", result.counts.failed, StatusKind.Danger)
                            }
                        }
                    }

                    if (result.operations.isNotEmpty()) {
                        item { SectionHeader("Şu an") }
                        adaptiveItems(result.operations, columns, key = { it.id }) { operation ->
                            OperationCard(operation) {
                                actions.openOperation(operation)
                                onOpenChat()
                            }
                        }
                    }

                    if (result.events.isNotEmpty()) {
                        // Oturum+tür başına yalnız EN YENİ olay "Son olaylar"da;
                        // aynı oturumun eski başladı/bitti tekrarları varsayılan
                        // kapalı Geçmiş bölümünde (kullanıcı kararı 05.08.2026).
                        val split = splitOperationEvents(result.events.take(60))
                        item { SectionHeader("Son olaylar") }
                        adaptiveItems(split.current, columns, key = { it.id }) { event ->
                            // Oturum kimliği taşıyan olaylar da ilgili oturuma götürür.
                            OperationEventCard(
                                event,
                                onOpen = if (event.sessionId.isNotBlank()) {
                                    { actions.openOperationEvent(event); onOpenChat() }
                                } else null,
                            )
                        }
                        if (split.history.isNotEmpty()) {
                            item {
                                EventHistoryHeader(
                                    count = split.history.size,
                                    expanded = historyExpanded,
                                    onToggle = { historyExpanded = !historyExpanded },
                                )
                            }
                            if (historyExpanded) {
                                adaptiveItems(split.history, columns, key = { it.id }) { event ->
                                    OperationEventCard(
                                        event,
                                        onOpen = if (event.sessionId.isNotBlank()) {
                                            { actions.openOperationEvent(event); onOpenChat() }
                                        } else null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Geçmiş bölümünün aç/kapa başlığı: "Geçmiş (N)" + yön oku. Kart değil düz
// satır — SectionHeader ağırlığında dursun, içerik kartlarıyla karışmasın.
@Composable
private fun EventHistoryHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = Ui2Tokens.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Geçmiş ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = Ui2.colors.ink2,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            if (expanded) "Kapat" else "Aç",
            tint = Ui2.colors.ink3,
        )
    }
}

@Composable
private fun OperationCount(label: String, count: Int, kind: StatusKind) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StatusBadge(count.toString(), kind)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
    }
}

@Composable
private fun OperationCard(operation: OperationItem, onOpen: () -> Unit) {
    val kind = operationStatusKind(operation.status, operation.needsAttention)
    // Kartın tamamı tıklanabilir; "Aç" düğmesi görsel ipucu olarak kalır.
    SurfaceCard(onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(kind)
            Column(Modifier.weight(1f).padding(horizontal = Ui2Tokens.s12)) {
                // Başlık oturumun kendi başlığı; backend adı alt meta satırına
                // indi. Eskiden her kart "Codex App" diyordu ve aynı backend'in
                // iki oturumu ayırt edilemiyordu (kullanıcı şikayeti 02.08.2026).
                Text(
                    operation.title.ifBlank { operation.backendLabel },
                    style = MaterialTheme.typography.titleSmall,
                    color = Ui2.colors.ink,
                    maxLines = 2,
                )
                val detail = operation.summary.ifBlank {
                    operation.cwd.substringAfterLast('/').substringAfterLast('\\')
                }
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2, maxLines = 2)
                }
                Text(
                    operationMeta(operation),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ui2.colors.ink3,
                )
            }
            TextButton(onClick = onOpen) { Text("Aç", color = Ui2.colors.accent) }
        }
    }
}

// "Codex App · gpt-5.6-luna · 02.08.2026 20:38" — hangi kartın hangi oturum
// olduğunu tek bakışta ayırır; ham ISO damgası yerel saate çevrilir.
private fun operationMeta(operation: OperationItem): String = listOf(
    operation.backendLabel,
    operation.model,
    formatIsoTime(operation.updatedAt),
).filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun OperationEventCard(event: OperationEvent, onOpen: (() -> Unit)? = null) {
    val kind = operationStatusKind(event.kind.ifBlank { event.status }, event.kind == "attention")
    SurfaceCard(onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.History, null, tint = Ui2.colors.ink3)
            Column(Modifier.weight(1f).padding(horizontal = Ui2Tokens.s12)) {
                // Başlık OTURUMUN başlığı — "Şu an" kartlarıyla aynı kural
                // (02.08.2026 kararı olay kartlarına hiç uygulanmamıştı; her kart
                // "Claude App · Tamamlandı" diyordu ve oturumlar ayırt edilemiyordu).
                // Backend adı ve olay türü alt meta satırına indi.
                Text(
                    event.title.ifBlank { "${event.backendLabel} · ${operationEventLabel(event.kind)}" },
                    style = MaterialTheme.typography.titleSmall,
                    color = Ui2.colors.ink,
                    maxLines = 2,
                )
                if (event.summary.isNotBlank()) {
                    Text(event.summary, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2, maxLines = 2)
                }
                val meta = listOf(
                    if (event.title.isNotBlank()) event.backendLabel else "",
                    if (event.title.isNotBlank()) operationEventLabel(event.kind) else "",
                    formatIsoTime(event.at),
                ).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                }
            }
            StatusDot(kind)
        }
    }
}

private fun operationStatusKind(status: String, needsAttention: Boolean): StatusKind = when {
    needsAttention || status == "waiting" || status == "attention" -> StatusKind.Attention
    status == "failed" || status == "error" -> StatusKind.Danger
    status == "completed" || status == "done" -> StatusKind.Done
    else -> StatusKind.Running
}

private fun operationEventLabel(kind: String): String = when (kind) {
    "completed" -> "Tamamlandı"
    "attention" -> "Yanıt bekliyor"
    "failed" -> "Başarısız"
    else -> "Başladı"
}
