package com.agent.bridge.ui3.material

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui3.theme.LocalUi3Palet
import com.agent.bridge.ui3.theme.Ui3Tokens
import dev.chrisbanes.haze.HazeState

/**
 * Anlam tonları. Kovan dilinde yalnız [Amber] görünür iz bırakır (onay kartı:
 * vurgu renginde kenar); öbürleri düz karttır.
 */
enum class GlassTint { Notr, Vio, Amber, Cyan }

/**
 * Kart yüzeyi (7 Kas 2026: cam emekli, Kovan'ın düz kartı).
 *
 * Opak dolgu ([Ui3Palet.yuzey1]) + 1dp düz kenar ([Ui3Palet.cizgi]). Blur,
 * rim gradyanı, spekular hat ve gren yok. Ad ve imza ui3'ün her yerinden
 * çağrıldığı için korundu; [hazeState] artık kullanılmıyor.
 *
 * @param ortu verilirse dolgunun yerine geçer (menü gibi odak yüzeyleri).
 */
@Composable
fun GlassSurface(
    @Suppress("UNUSED_PARAMETER") hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Ui3Tokens.r26),
    tint: GlassTint = GlassTint.Notr,
    ortu: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    KovanKart(modifier, shape, tint, ortu, content)
}

/** [GlassSurface]'in kaydırılan akıştaki eşi; Kovan'da ikisi aynı kart. */
@Composable
fun GlassLikeSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Ui3Tokens.r18),
    tint: GlassTint = GlassTint.Notr,
    dolgu: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    KovanKart(modifier, shape, tint, dolgu, content)
}

@Composable
private fun KovanKart(
    modifier: Modifier,
    shape: Shape,
    tint: GlassTint,
    dolgu: Color?,
    content: @Composable BoxScope.() -> Unit,
) {
    val palet = LocalUi3Palet.current
    val kenar = if (tint == GlassTint.Amber) palet.amber else palet.cizgi
    Box(
        modifier
            .clip(shape)
            .background(dolgu ?: palet.yuzey1, shape)
            .border(1.dp, kenar, shape),
    ) {
        content()
    }
}
