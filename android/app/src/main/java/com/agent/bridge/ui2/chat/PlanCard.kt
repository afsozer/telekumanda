package com.agent.bridge.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

// Plan kartı: plan modunda adım listesi. Tamamlanan adım yeşil onay işareti,
// bekleyen adım içi boş nokta.
data class PlanStepUi(val text: String, val done: Boolean = false)

@Composable
fun PlanCard(
    steps: List<PlanStepUi>,
    modifier: Modifier = Modifier,
    title: String = "Plan",
) {
    val shape = RoundedCornerShape(Ui2Tokens.cornerInline)
    Column(
        modifier
            .fillMaxWidth()
            .background(Ui2.colors.surface, shape)
            .border(1.dp, Ui2.colors.line, shape)
            .padding(horizontal = Ui2Tokens.s12, vertical = Ui2Tokens.s12),
        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
        steps.forEach { step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
            ) {
                if (step.done) {
                    Icon(
                        Icons.Default.Check,
                        null,
                        tint = Ui2.colors.done,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Box(
                        Modifier
                            .size(14.dp)
                            .padding(3.dp)
                            .border(1.5.dp, Ui2.colors.ink3, CircleShape)
                    )
                }
                Text(
                    step.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step.done) Ui2.colors.ink3 else Ui2.colors.ink2,
                )
            }
        }
    }
}
