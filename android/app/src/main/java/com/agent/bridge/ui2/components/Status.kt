package com.agent.bridge.ui2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

// Durum dili (anayasa 4.2 + 6): uygulama genelinde AYNI dört anlam.
// Renk yalnız durum anlatır; sağlayıcı kimliği ProviderMark ile gösterilir.
enum class StatusKind { Running, Attention, Done, Danger }

@Composable
@ReadOnlyComposable
fun statusColor(kind: StatusKind): Color = when (kind) {
    StatusKind.Running -> Ui2.colors.running
    StatusKind.Attention -> Ui2.colors.attention
    StatusKind.Done -> Ui2.colors.done
    StatusKind.Danger -> Ui2.colors.danger
}

// Küçük durum noktası: sekme çipi, composer pill'i, satır sonu göstergesi.
@Composable
fun StatusDot(
    kind: StatusKind,
    modifier: Modifier = Modifier,
    size: Dp = Ui2Tokens.statusDot,
) {
    Box(
        modifier
            .size(size)
            .background(statusColor(kind), CircleShape)
    )
}

// Pill rozet: durum renkli yumuşak zemin + renkli metin; kind=null nötr rozet.
@Composable
fun StatusBadge(
    text: String,
    kind: StatusKind? = null,
    modifier: Modifier = Modifier,
    // Sohbet üzerine binen dolu overlay çip: koyu temada beyaz zemin + menekşe
    // yazı, açık temada menekşe zemin + beyaz yazı (pillFill/pillOn tema token'ları).
    solid: Boolean = false,
) {
    val fg = when {
        solid -> Ui2.colors.pillOn
        kind != null -> statusColor(kind)
        else -> Ui2.colors.ink2
    }
    val bg = when {
        solid -> Ui2.colors.pillFill
        kind != null -> statusColor(kind).copy(alpha = 0.14f)
        else -> Ui2.colors.surface2
    }
    Box(
        modifier
            .background(bg, Ui2Tokens.pill)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
