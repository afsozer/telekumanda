package com.agent.bridge.ui2.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.components.statusColor
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Mono
import com.agent.bridge.ui2.theme.Ui2Tokens

// Araç çağrısı kartı (mockup .tool): tür etiketi + mono özet + sonuç.
// Katlanabilir: body verilirse dokununca açılır; verilmezse pasif satır.
@Composable
fun ToolCallCard(
    kind: String,
    summary: String,
    modifier: Modifier = Modifier,
    resultText: String? = null,
    resultKind: StatusKind? = null,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Ui2Tokens.cornerInline)
    Column(
        modifier
            .fillMaxWidth()
            .background(Ui2.colors.surface, shape)
            .border(1.dp, Ui2.colors.line, shape)
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = Ui2Tokens.s12, vertical = Ui2Tokens.s8),
        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(kind, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink3)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Ui2Mono),
                color = Ui2.colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (resultText != null) {
                Text(
                    resultText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (resultKind != null) statusColor(resultKind) else Ui2.colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onToggle != null) {
                Icon(
                    if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    if (expanded) "Kapat" else "Aç",
                    tint = Ui2.colors.ink3,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (body != null) {
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Ui2.colors.codeBg, RoundedCornerShape(8.dp))
                        .padding(Ui2Tokens.s8),
                ) { body() }
            }
        }
    }
}

// Ardışık araç adımlarının toplandığı grup kartı: kapalıyken "N adım" + son
// adımın mono özeti (akış sürerken canlı "ne yapıyor" hissi), açılınca üyeler
// alt alta kendi ToolCallCard'ları olarak çizilir (her biri ayrıca açılabilir).
// Gövde ToolCallCard'ın codeBg bloğundan farklı: kartlar zaten kendi zeminini
// taşır, ikinci bir koyu blok gürültü olurdu.
@Composable
fun ToolGroupCard(
    count: Int,
    lastSummary: String,
    modifier: Modifier = Modifier,
    label: String = "Araç",
    expanded: Boolean = false,
    onToggle: () -> Unit,
    body: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Ui2Tokens.cornerInline)
    Column(
        modifier
            .fillMaxWidth()
            .background(Ui2.colors.surface, shape)
            .border(1.dp, Ui2.colors.line, shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = Ui2Tokens.s12, vertical = Ui2Tokens.s8),
        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("$label · $count adım", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink3)
            Text(
                lastSummary,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Ui2Mono),
                color = Ui2.colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                if (expanded) "Kapat" else "Aç",
                tint = Ui2.colors.ink3,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
            ) { body() }
        }
    }
}
