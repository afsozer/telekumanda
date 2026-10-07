package com.agent.bridge.ui2.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

// Her liste ekranının üç hali tasarlanır: dolu / boş / hata (anayasa 6).
// Boş durum: tek cümle + tek aksiyon. Hata: neden + yeniden dene.

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(Ui2Tokens.s28),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Ui2.colors.ink3, modifier = Modifier.size(40.dp).padding(bottom = Ui2Tokens.s8))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = Ui2.colors.ink2,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = Ui2.colors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Ui2Tokens.s4),
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = Ui2Tokens.s16),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ui2.colors.accent,
                    contentColor = Ui2.colors.onAccent,
                ),
            ) { Text(actionLabel) }
        }
    }
}

// Uzun yüklemede iskelet tercih edilir; spinner yalnız kısa/belirsiz bekleme
// (anayasa 6). Satır formunda nabız atan kutular.
@Composable
fun LoadingSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 3,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
        repeat(rows) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Ui2Tokens.rowMinHeight)
                    .background(
                        Ui2.colors.surface2.copy(alpha = alpha),
                        RoundedCornerShape(Ui2Tokens.cornerInline),
                    )
            )
        }
    }
}
