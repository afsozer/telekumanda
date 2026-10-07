package com.agent.bridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ── Kalan kullanım göstergeleri ───────────────────────────────────────────

@Composable
internal fun UsageBucketRow(bucket: UsageBucket) {
    val remaining = bucket.remainingFraction.coerceIn(0.0, 1.0)
    // 10 $ üstü bakiye: eşik rengi değil, marka vurgusu (bkz. HubUsageScreen).
    val color = if (bucket.creditAbundant()) MaterialTheme.colorScheme.primary else usageColor(remaining)
    val valueColor = if (bucket.metered) color else MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(bucket.label.ifBlank { bucket.window }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(bucket.value.ifBlank { "%${(remaining * 100).roundToInt()}" }, style = MaterialTheme.typography.labelMedium, color = valueColor)
        }
        if (bucket.metered) {
            LinearProgressIndicator(
                progress = { remaining.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        if (bucket.description.isNotBlank()) {
            Text(bucket.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun usageColor(remaining: Double): Color = when {
    remaining >= 0.5 -> Color(0xFF10B981)
    remaining >= 0.15 -> Color(0xFFF59E0B)
    else -> Color(0xFFEF4444)
}
