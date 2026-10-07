package com.agent.bridge.ui2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

/**
 * Yazısız, hap biçimli ikon tuşu. Not editörünün eylem şeridi bununla tek
 * satıra sığıyor — yazılı tuşlar dar telefonda ikinci satıra taşıyordu.
 *
 * Ad KAYBOLMUYOR: basılı tutunca Material tooltip'i olarak çıkar, ekran
 * okuyucuya da contentDescription olarak gider. İkonun ne yaptığını tahmin
 * etmek zorunda kalmayasın diye tooltip zorunlu parametre.
 *
 * Seçili görünüm SegmentedTabs'la aynı dili konuşur (surface2 + lineStrong):
 * aynı ekranda iki farklı "seçili" görüntüsü olmasın.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconPillAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    // Vurgulu tuşlar (AI, hatırlatıcı) marka menekşesiyle çizilir; gerisi
    // ikincil metin rengiyle. Palet dışı renk yok (docs/ui-anayasasi.md).
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val ikonRengi = when {
        !enabled -> Ui2.colors.ink3
        selected -> Ui2.colors.accent
        else -> tint ?: Ui2.colors.ink2
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(34.dp)
                .background(
                    if (selected) Ui2.colors.surface2 else Ui2.colors.surface,
                    Ui2Tokens.pill,
                )
                .border(
                    1.dp,
                    if (selected) Ui2.colors.lineStrong else Ui2.colors.line,
                    Ui2Tokens.pill,
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = ikonRengi, modifier = Modifier.size(19.dp))
        }
    }
}

data class IconSegment(val icon: ImageVector, val label: String)

/**
 * Birbirine bağlı ikon seçenekleri (Önizleme / Düzenle) — TEK kapsül, içinde
 * kaydırılan seçim. Yan yana iki ayrı [IconPillAction] olarak durduklarında
 * bağımsız birer eylem gibi görünüyorlardı; oysa biri açıkken diğeri kapalı
 * (kullanıcı geri bildirimi 07.08).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconSegmentedTabs(
    segments: List<IconSegment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(34.dp)
            .background(Ui2.colors.surface, Ui2Tokens.pill)
            .border(1.dp, Ui2.colors.line, Ui2Tokens.pill)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            val secili = index == selectedIndex
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(segment.label) } },
                state = rememberTooltipState(),
            ) {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(30.dp)
                        .background(
                            if (secili) Ui2.colors.surface2 else Color.Transparent,
                            Ui2Tokens.pill,
                        )
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        segment.icon,
                        segment.label,
                        // Seçili menekşe, seçilmeyen sönük: hangisinin açık
                        // olduğu tek bakışta okunsun.
                        tint = if (secili) Ui2.colors.accent else Ui2.colors.ink3,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}
