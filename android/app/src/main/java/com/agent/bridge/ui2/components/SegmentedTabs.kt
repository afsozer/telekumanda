package com.agent.bridge.ui2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

// Filtre çipleri (Tümü / Sabitli / Arşiv gibi). Tek seçim; seçili çip
// surface2 + güçlü kenar, diğerleri sınır çizgili şeffaf pill.
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp? = null,
    horizontalPadding: Dp = Ui2Tokens.s12,
    // Çipler satıra sığmayacaksa (backend süzgeci gibi değişken sayıda çip)
    // yatay kaydırma açılır; varsayılan kapalı, mevcut çağıranlar aynı kalsın.
    scrollable: Boolean = false,
) {
    val rowModifier = if (scrollable) modifier.horizontalScroll(rememberScrollState()) else modifier
    Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
        options.forEachIndexed { index, label ->
            val on = index == selectedIndex
            val chipModifier = Modifier
                .background(if (on) Ui2.colors.surface2 else Ui2.colors.surface, Ui2Tokens.pill)
                .border(1.dp, if (on) Ui2.colors.lineStrong else Ui2.colors.line, Ui2Tokens.pill)
                .clickable { onSelect(index) }
                .let { base -> if (itemHeight != null) base.height(itemHeight) else base }
                .padding(horizontal = horizontalPadding, vertical = if (itemHeight == null) 5.dp else 0.dp)
            Box(
                modifier = chipModifier,
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) Ui2.colors.ink else Ui2.colors.ink2,
                )
            }
        }
    }
}
