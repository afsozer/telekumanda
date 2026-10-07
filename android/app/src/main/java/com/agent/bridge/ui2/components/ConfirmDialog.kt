package com.agent.bridge.ui2.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.agent.bridge.ui2.theme.Ui2

// Dialog desen kuralı (anayasa 2): yıkıcı/geri alınamaz işlem onayı.
// En fazla başlık + açıklama + 2 aksiyon; liste/karmaşık form OLMAZ.
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui2.colors.surface2,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, color = Ui2.colors.ink) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink2) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = Ui2.colors.danger,
                        contentColor = Color(0xFF33090B),
                    )
                } else ButtonDefaults.buttonColors(
                    containerColor = Ui2.colors.accent,
                    contentColor = Ui2.colors.onAccent,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Vazgeç", color = Ui2.colors.ink2) }
        },
    )
}
