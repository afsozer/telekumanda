package com.agent.bridge.ui2.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.bridge.TaskNotificationUi
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Mono

@Composable
fun TaskNotificationCard(
    notification: TaskNotificationUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalizedStatus = notification.status.trim().lowercase()
    val hasDetails = notification.note.isNotBlank() ||
        notification.outputFile.isNotBlank() ||
        notification.taskId.isNotBlank() ||
        notification.toolUseId.isNotBlank()
    val statusKind = when (normalizedStatus) {
        "completed", "complete", "done", "succeeded", "success" -> StatusKind.Done
        "failed", "error" -> StatusKind.Danger
        "cancelled", "canceled" -> StatusKind.Attention
        "running", "in_progress", "in-progress" -> StatusKind.Running
        else -> StatusKind.Attention
    }
    ToolCallCard(
        kind = "Arka plan",
        summary = notification.compactSummary(),
        modifier = modifier,
        resultText = notification.statusLabel(),
        resultKind = statusKind,
        expanded = expanded,
        onToggle = if (hasDetails) onToggle else null,
        body = if (hasDetails) ({
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (notification.note.isNotBlank()) {
                        Text(
                            notification.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ui2.colors.ink2,
                        )
                    }
                    if (notification.outputFile.isNotBlank()) {
                        DetailLine("Çıktı", notification.outputFile)
                    }
                    if (notification.taskId.isNotBlank()) {
                        DetailLine("Görev", notification.taskId)
                    }
                    if (notification.toolUseId.isNotBlank()) {
                        DetailLine("Araç", notification.toolUseId)
                    }
                }
            }
        }) else null,
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Text(
        "$label · $value",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = Ui2Mono),
        color = Ui2.colors.ink3,
    )
}
