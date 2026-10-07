package com.agent.bridge.ui2.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

/**
 * Kart gövdesini DIŞARIDAN çizdirme yuvası.
 *
 * ui3, ui2 ekranlarını olduğu gibi ödünç alıyor. Paleti değiştirmek zemini ve
 * mürekkebi ui3'e çeviriyor ama kartın MALZEMESİNİ değiştirmiyor: `Surface` düz
 * dolgu + 1dp düz kenar çiziyor, ui3'ün camında ise rim gradyanı, spekular hat
 * ve gren var. Kullanıcı farkı doğrudan gördü: "dosya yöneticisi hiç cam değil".
 *
 * Ekranların hepsini yeniden yazmak yerine kart PRİMİTİFİNE yuva açıldı —
 * `SurfaceCard` bütün liste kartlarının ve içerik kutularının tek atası olduğu
 * için buradan geçen bir değişiklik Dosyalar, Ayarlar, Operasyon, Notlar ve
 * Projeler'in tamamına aynı anda ulaşıyor.
 *
 * Yuva doluyken bile ÖZEL RENKLİ kartlar (kullanım durum kartı, UDF tablo
 * hücresi) düz `Surface` yolundan gider: onların rengi anlam taşıyor.
 */
typealias Ui2KartYuzeyi = @Composable (
    modifier: Modifier,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    icerik: @Composable () -> Unit,
) -> Unit

val LocalUi2KartYuzeyi = staticCompositionLocalOf<Ui2KartYuzeyi?> { null }

// Yüzey kartı: ton farkı + ince kenar, GÖLGE YOK (anayasa 4.2).
// Tüm liste kartları ve içerik kutuları bunun üstüne kurulur.
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Ui2Tokens.s12),
    verticalGap: androidx.compose.ui.unit.Dp = Ui2Tokens.s8,
    containerColor: Color = Ui2.colors.surface,
    borderColor: Color = Ui2.colors.line,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Ui2Tokens.cornerCard)
    val border = BorderStroke(1.dp, borderColor)
    val body: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(verticalGap),
            content = content,
        )
    }
    // Dış yüzey yalnız kart VARSAYILAN renkleriyle çiziliyorsa devreye girer;
    // çağıran renk verdiyse o renk anlam taşıyor, ezilmez.
    val disYuzey = LocalUi2KartYuzeyi.current
    if (disYuzey != null && containerColor == Ui2.colors.surface && borderColor == Ui2.colors.line) {
        disYuzey(modifier.fillMaxWidth(), onClick, onLongClick, body)
        return
    }
    if (onLongClick != null) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick),
            shape = shape,
            color = containerColor,
            border = border,
            content = body,
        )
    } else if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            border = border,
            content = body,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            border = border,
            content = body,
        )
    }
}
