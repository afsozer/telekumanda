package com.agent.bridge.ui2.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

// Ekran başlığı: kök ekranlarda büyük başlık, alt ekranlarda geri oku ile.
// subtitle yerine durum satırı gerekiyorsa subtitleContent slotu kullanılır
// (örn. bağlantı durumu: StatusDot + metin).
//
// dense: dikey boşluğu 12dp yerine 4dp yapar. Belge görüntüleyici gibi düşey
// alanın tamamının içeriğe gitmesi gereken TEK SATIRLIK başlıklar için —
// 366dp'lik telefonda başlık+araç çubuğu belgeye kalan yeri yiyordu. Alt
// başlıklı kullanımlarda AÇMA: iki satır 4dp boşlukla üst üste biner gibi durur.
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    dense: Boolean = false,
    subtitleContent: (@Composable RowScopeAlias.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                horizontal = Ui2Tokens.screenPadding,
                vertical = if (dense) Ui2Tokens.s4 else Ui2Tokens.s12,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ui2.colors.ink2)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = Ui2.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
            }
            if (subtitleContent != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
                ) { subtitleContent() }
            }
        }
        if (trailing != null) trailing()
    }
}

// RowScope'u yeniden export etmemek için takma ad — slot imzası sade kalsın.
typealias RowScopeAlias = androidx.compose.foundation.layout.RowScope

// Bölüm etiketi: büyük harf + harf aralığı, sağda opsiyonel "tümü ›" aksiyonu.
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    // Başlığın sağındaki "i": uzun yardım metni ekranı şişirmesin diye
    // bölümün açıklaması buradan bir bilgi kartıyla açılır.
    onInfo: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Ui2Tokens.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Ui2.colors.ink3,
            modifier = Modifier.weight(1f),
        )
        if (onInfo != null) {
            IconButton(onClick = onInfo, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "$text hakkında",
                    tint = Ui2.colors.ink3,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Ui2.colors.accent,
                modifier = Modifier
                    .padding(start = Ui2Tokens.s8)
                    .clickable(onClick = onAction),
            )
        }
    }
}
