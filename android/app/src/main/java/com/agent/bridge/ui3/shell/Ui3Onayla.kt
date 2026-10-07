package com.agent.bridge.ui3.shell

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** İşlem onayları standart Android Material diyaloğunda gösterilir. */
@Composable
internal fun Ui3Onayla(
    baslik: String,
    aciklama: String?,
    onayMetni: String,
    onOnay: () -> Unit,
    onVazgec: () -> Unit,
    yikici: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onVazgec,
        modifier = Modifier.testTag("onayla"),
        title = { Text(baslik) },
        text = aciklama?.takeIf { it.isNotBlank() }?.let { metin ->
            { Text(metin, Modifier.verticalScroll(rememberScrollState())) }
        },
        confirmButton = {
            TextButton(
                onClick = onOnay,
                modifier = Modifier.testTag("onayla_evet"),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (yikici) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                ),
            ) { Text(onayMetni) }
        },
        dismissButton = {
            TextButton(onClick = onVazgec, modifier = Modifier.testTag("onayla_vazgec")) {
                Text("Vazgeç")
            }
        },
    )
}
