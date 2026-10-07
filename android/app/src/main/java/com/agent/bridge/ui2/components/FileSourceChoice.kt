package com.agent.bridge.ui2.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

/**
 * Dosya eklerken kaynak sorusu: bilgisayardan mı telefondan mı?
 *
 * Ayrım teknik olarak da anlamlı, sadece kolaylık değil:
 * - **Telefon** yolu dosyayı OKUR ve köprüye YÜKLER (bayt transferi).
 * - **Bilgisayar** yolu hiçbir şey aktarmaz — dosya zaten köprünün diskinde,
 *   modele yalnız yolu verilir. Büyük dosyalarda fark saniyeler değil dakikalar.
 */
@Composable
fun FileSourceChoiceDialog(
    onPc: () -> Unit,
    onPhone: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Dosya nereden?",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4)) {
                SourceChoiceRow(
                    icon = Icons.Default.Computer,
                    label = "Bilgisayardan",
                    detail = "Köprüdeki dosya gezgini açılır, aktarım yapılmaz",
                    onClick = onPc,
                )
                SourceChoiceRow(
                    icon = Icons.Default.Smartphone,
                    label = "Telefondan",
                    detail = "Cihazın kendi dosya seçicisi açılır",
                    onClick = onPhone,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun SourceChoiceRow(
    icon: ImageVector,
    label: String,
    detail: String,
    onClick: () -> Unit,
) {
    SurfaceCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        ListRow(
            title = label,
            detail = detail,
            detailMaxLines = 2,
            leading = { Icon(icon, null, tint = Ui2.colors.accent) },
        )
    }
}
