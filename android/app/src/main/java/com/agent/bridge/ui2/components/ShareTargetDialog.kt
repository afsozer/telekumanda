package com.agent.bridge.ui2.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.CoworkWorkspace
import com.agent.bridge.SharedFile
import com.agent.bridge.shareDialogTitle
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

/**
 * Paylaş menüsünden gelen dosyanın hedefi: sohbet eki mi, çalışma alanı mı?
 *
 * İki adımlı bilinçli: önce "nereye", çalışma alanı seçilirse ardından "hangisi".
 * Tek ekranda bütün alanları listelemek, tipik durumu (sohbete ekle) uzun bir
 * listenin dibine gömerdi.
 */
@Composable
fun ShareTargetDialog(
    files: List<SharedFile>,
    workspaces: List<CoworkWorkspace>,
    onAttachToChat: () -> Unit,
    onSaveToWorkspace: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickingWorkspace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui2.colors.surface2,
        title = {
            Text(
                if (pickingWorkspace) "Hangi çalışma alanı?" else shareDialogTitle(files),
                style = MaterialTheme.typography.titleMedium,
                color = Ui2.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            if (pickingWorkspace) {
                LazyColumn(
                    Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
                ) {
                    items(workspaces, key = { it.path }) { workspace ->
                        ShareChoiceRow(
                            icon = Icons.Default.Folder,
                            label = workspace.name,
                            detail = workspace.matter.ifBlank { workspace.path },
                            onClick = { onSaveToWorkspace(workspace.path) },
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4)) {
                    ShareChoiceRow(
                        icon = Icons.Default.Chat,
                        label = "Sohbete ekle",
                        detail = "Dosya ek olarak yüklenir, mesajla birlikte gider",
                        onClick = onAttachToChat,
                    )
                    ShareChoiceRow(
                        icon = Icons.Default.Folder,
                        label = "Çalışma alanına kaydet",
                        detail = "Bilgisayardaki dosya klasörüne kalıcı olarak yazılır",
                        onClick = { pickingWorkspace = true },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pickingWorkspace) pickingWorkspace = false else onDismiss() }) {
                Text(if (pickingWorkspace) "Geri" else "Vazgeç", color = Ui2.colors.ink2)
            }
        },
    )
}

@Composable
private fun ShareChoiceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Ui2Tokens.s8),
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Ui2.colors.accent)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
