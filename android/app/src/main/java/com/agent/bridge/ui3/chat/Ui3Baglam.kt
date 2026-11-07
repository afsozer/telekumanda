package com.agent.bridge.ui3.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.shell.SheetBasligi
import com.agent.bridge.ui3.material.GlassTint
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

// Bağlam doluluğunun "artık umursa" eşiği: üstünde kehribar vurgu. 80: ajanın
// kendi otomatik özetlemesi devreye girmeden önce kullanıcının karar verebileceği
// son aralık.
internal const val UI3_BAGLAM_ESIGI = 80

/** Doluluk "artık umursa" eşiğini aştı mı? null = köprü ölçemiyor -> uyarı yok. */
internal fun baglamDoldu(baglamYuzdesi: Int?): Boolean =
    baglamYuzdesi != null && baglamYuzdesi >= UI3_BAGLAM_ESIGI

// ------------------------------------------------------------- bağlam ayrıntısı

/**
 * Büyük sayıyı binlik ayraçla yazar: 44555 -> "44.555".
 *
 * Ayraç ELDE konuyor. `String.format`/`NumberFormat` cihaz diline bakıyor ve
 * aynı sayı iki telefonda "44.555" / "44,555" çıkıyor — bu depoda Türkçe-I
 * tuzağıyla aynı sınıftan bir hata. 0 ve altı "bilinmiyor" demek: köprü
 * pencereyi ısıtmadan 0 gönderiyor ve sıfır yazmak "pencere sıfır" gibi
 * okunurdu.
 */
internal fun baglamSayisi(deger: Int): String {
    if (deger <= 0) return "—"
    val ham = deger.toString()
    return ham.reversed().chunked(3).joinToString(".").reversed()
}

/**
 * BAĞLAM AYRINTISI — adaya dokununca açılan küçük yüzey.
 *
 * Ada 28dp'lik bir çubuk ve üstünde yalnız yüzde var; "44 bin token nereye
 * gitti" sorusunun cevabı oraya sığmıyor. Yüzeyde ham sayılar duruyor ve
 * yüzdenin YOK olduğu hâl açıkça yazılıyor — köprü pencereyi bilmiyorken ada
 * hiçbir şey göstermediği için kullanıcı "bozuk mu" diye düşünüyordu.
 *
 * Sıkıştır: kullanıcı bağlamı istediği an özetleyebilsin diye; sağlayıcı
 * desteklemiyorsa [sikistirilabilir] false gelir ve tuş çizilmez.
 */
@Composable
internal fun ColumnScope.Ui3BaglamAyrintisi(
    yuzde: Int?,
    tokenlar: Int,
    pencere: Int,
    calisiyor: Boolean,
    sikistirilabilir: Boolean,
    onSikistir: () -> Unit,
    onGeri: (() -> Unit)?,
) {
    SheetBasligi(
        "Bağlam",
        // Yüzde yokken başlıkta da yalan söylenmiyor: köprü ölçemiyorsa bunu
        // yaz, "%0" gösterme.
        if (yuzde != null) "%$yuzde dolu" else "doluluk ölçülemiyor",
        onGeri,
    )
    BaglamSatiri("Kullanılan", "${baglamSayisi(tokenlar)} token")
    BaglamSatiri(
        "Pencere",
        if (pencere > 0) "${baglamSayisi(pencere)} token" else "bilinmiyor",
    )
    BaglamSatiri(
        "Doluluk",
        if (yuzde != null) "%$yuzde" else "—",
        vurgu = baglamDoldu(yuzde),
    )

    if (!sikistirilabilir) return
    if (calisiyor) {
        Text(
            "Tur sürerken sıkıştırılamaz — önce durdur ya da bitmesini bekle.",
            style = Ui3Type.alt,
            color = Ui3Colors.ink3,
            modifier = Modifier.padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s12),
        )
        return
    }
    Box(
        Modifier
            .padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s12)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ui3Colors.amber.copy(alpha = 0.14f))
            .clickable(onClick = onSikistir)
            .padding(vertical = 11.dp)
            .testTag("baglam_sikistir"),
        contentAlignment = Alignment.Center,
    ) {
        Text("Sıkıştır", style = Ui3Type.govde, color = Ui3Colors.amber, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BaglamSatiri(etiket: String, deger: String, vurgu: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Ui3Tokens.s20, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(etiket, style = Ui3Type.govde, color = Ui3Colors.ink3, modifier = Modifier.weight(1f))
        Text(
            deger,
            style = Ui3Type.govde,
            color = if (vurgu) Ui3Colors.amber else Ui3Colors.ink,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
