package com.agent.bridge.ui2.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

// Standart liste satırı: leading ikon/monogram + başlık + detay + trailing.
// Min 56dp (rahat yoğunluk — anayasa 4.3). Uzun basış menüsü olan listelerde
// onLongClick verilir; tek implementasyon, ekran içinde satır uydurulmaz.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    // Varsayılan tek satır (yol/özet). Açıklama metinleri (skill listesi) için
    // çağıran açıkça büyütür; satır defaultMinSize ile zaten büyüyebilir.
    detailMaxLines: Int = 1,
    // Başlıkla detay ARASINA giren tek satırlık içerik özeti (not kartlarında
    // notun gövdesi). Sığdığı kadarı görünür, gerisi "…" olur.
    secondary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    // Başlığın SOLUNDA küçük bir işaret (ör. sabitli oturum pin ikonu). leading
    // (monogram) ile karışmasın diye başlık satırının içinde durur.
    titleIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val clickMod = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
    } else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Ui2Tokens.rowMinHeight)
            .then(clickMod)
            .padding(horizontal = Ui2Tokens.s4, vertical = Ui2Tokens.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            if (titleIcon != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
                ) {
                    titleIcon()
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Ui2.colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Ui2.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!secondary.isNullOrBlank()) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.ink3,
                    maxLines = detailMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}
