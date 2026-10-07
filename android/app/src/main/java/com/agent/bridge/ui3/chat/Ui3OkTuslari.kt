package com.agent.bridge.ui3.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * FİZİKSEL KLAVYE OK TUŞLARI — composer metin alanı için.
 *
 * İki ayrı derdi birden çözüyor ve ikisi de cihazda ölçüldü:
 *
 *  1. **Ok tuşu alandan KAÇIYOR.** Sol/sağ ok `BasicTextField`in içine hiç
 *     uğramadan odak gezinmesine dönüşüyor ve imleç metinde ilerleyeceğine odak
 *     composer'dan çıkıyordu (tablet + pogo pin klavye). Bu yüzden sol/sağ ok
 *     ÖN İZLEME aşamasında yakalanıp seçim ELLE taşınıyor; alan hiç görmüyor.
 *  2. **Yukarı/aşağı okta odak kaçışı.** Bunlar ÖNCE `onKeyEvent`te yutuluyordu;
 *     işe yaramadı ve sebebi ölçüldü (21.08.2026, tablet): Compose'un varsayılan
 *     odak gezinmesi olayı, ata düğümlerin `onKeyEvent`i çalışmadan ÖNCE
 *     tüketiyor. `onKeyEvent` geri kabarcıklanma aşaması, yani her zaman geç
 *     kalıyor. Bu yüzden yukarı/aşağı da ön izlemede yakalanıyor ve satır
 *     geçişi metin yerleşiminden ([TextLayoutResult]) elle hesaplanıyor.
 *
 * ui2'de aynı düzeltme `ui2/chat/Composer.kt` içinde satır satır duruyordu;
 * ui3'ün kendi composer'ı yazılırken taşınmamıştı. Buraya çıkarıldı ki bir daha
 * ekran yeniden yazılınca sessizce düşmesin.
 *
 * ui2'nin sürümünden FARK: Shift ile seçim genişletme ve Ctrl/Alt ile kelime
 * atlama da çalışıyor. Orada her ok tuşu seçimi çökertip bir karakter
 * ilerletiyordu, yani Shift+ok ile metin seçilemiyordu.
 *
 * [yerlesim] alanın son metin yerleşimi (`BasicTextField.onTextLayout`). Yukarı/
 * aşağı bunsuz hesaplanamaz — hangi karakterin hangi görsel satırda olduğunu
 * yalnız o biliyor. Henüz yoksa tuş yine de YUTULUR: hesaplayamamak odağı
 * uçurmak için sebep değil.
 */
internal fun Modifier.ui3OkTuslari(
    deger: () -> TextFieldValue,
    onDeger: (TextFieldValue) -> Unit,
    yerlesim: () -> TextLayoutResult?,
): Modifier = this.onPreviewKeyEvent { olay ->
    val yatay = olay.key == Key.DirectionLeft || olay.key == Key.DirectionRight
    val dikey = olay.key == Key.DirectionUp || olay.key == Key.DirectionDown
    if (!yatay && !dikey) return@onPreviewKeyEvent false
    if (olay.type == KeyEventType.KeyDown) {
        val simdi = deger()
        val yeni = if (yatay) {
            okTusuSecimi(
                metin = simdi.text,
                secim = simdi.selection,
                saga = olay.key == Key.DirectionRight,
                shift = olay.isShiftPressed,
                // Alt da kabul ediliyor: harici klavyelerin bir kısmında
                // (ve macOS alışkanlığında) kelime atlama Alt'la yapılıyor.
                kelime = olay.isCtrlPressed || olay.isAltPressed,
            )
        } else {
            satirOkuSecimi(
                yerlesim = yerlesim(),
                metinUzunlugu = simdi.text.length,
                secim = simdi.selection,
                asagi = olay.key == Key.DirectionDown,
                shift = olay.isShiftPressed,
            )
        }
        if (yeni != null) onDeger(simdi.copy(selection = yeni))
    }
    // KeyUp da tüketilir: yalnız KeyDown yutulursa bırakma vuruşu odak
    // gezinmesini tetikleyebiliyor, yani kaçış tam kapanmıyor.
    true
}

/**
 * Yukarı/aşağı okun seçimi — yerleşim gerektirir, o yüzden [okTusuSecimi]nden ayrı.
 *
 * Yerleşim yoksa `null`: seçim değişmez ama tuş yine tüketilir (çağıran taraf).
 * Metnin ilk satırında yukarı → metnin BAŞINA, son satırında aşağı → SONUNA
 * gider; masaüstü editörlerinin davranışı bu ve odak da kaçmamış olur.
 */
internal fun satirOkuSecimi(
    yerlesim: TextLayoutResult?,
    metinUzunlugu: Int,
    secim: TextRange,
    asagi: Boolean,
    shift: Boolean,
): TextRange? {
    if (yerlesim == null) return null
    val uc = secim.end.coerceIn(0, metinUzunlugu)
    val satir = yerlesim.getLineForOffset(uc)
    val hedefSatir = okHedefSatiri(satir, yerlesim.lineCount, asagi)
    val hedef = if (hedefSatir == null) {
        if (asagi) metinUzunlugu else 0
    } else {
        // Sütun KORUNUR: imlecin piksel cinsinden yatay yeri alınıp hedef
        // satırın ortasında aynı x'e en yakın karakter aranıyor. Karakter
        // indeksini taşımak orantısız uzunluktaki satırlarda imleci kaydırırdı.
        val x = yerlesim.getHorizontalPosition(uc, usePrimaryDirection = true)
        val y = (yerlesim.getLineTop(hedefSatir) + yerlesim.getLineBottom(hedefSatir)) / 2f
        yerlesim.getOffsetForPosition(Offset(x, y)).coerceIn(0, metinUzunlugu)
    }
    return if (shift) TextRange(secim.start.coerceIn(0, metinUzunlugu), hedef) else TextRange(hedef)
}

/** Hedef satır; metnin dışına taşarsa `null` (uca gidilir). */
internal fun okHedefSatiri(satir: Int, satirSayisi: Int, asagi: Boolean): Int? {
    val hedef = if (asagi) satir + 1 else satir - 1
    return if (hedef in 0 until satirSayisi) hedef else null
}

/**
 * Ok tuşunun seçimi nereye taşıdığı — saf, sınanabilir.
 *
 * Kurallar tek tek şu davranışları karşılıyor:
 *  - Seçim varken düz ok seçimi ÇÖKERTİR (sola basınca başına, sağa basınca
 *    sonuna). Bir karakter ilerletmek imleci seçimin dışına atardı.
 *  - Shift çapayı ([TextRange.start]) yerinde tutar, yalnız ucu taşır — seçimi
 *    büyütüp küçültmek böyle çalışıyor.
 *  - Kelime atlamada önce boşluklar, sonra kelime yutulur; iki kelime arasında
 *    tek basışta takılıp kalmamak için sıra bu.
 */
internal fun okTusuSecimi(
    metin: String,
    secim: TextRange,
    saga: Boolean,
    shift: Boolean,
    kelime: Boolean,
): TextRange {
    val n = metin.length
    // Hareket eden uç: Shift'te seçimin ucu, düz okta imlecin bulunduğu yer.
    val uc = secim.end.coerceIn(0, n)
    val hedef = when {
        kelime -> if (saga) kelimeSagi(metin, uc) else kelimeSolu(metin, uc)
        shift || secim.collapsed -> if (saga) (uc + 1).coerceAtMost(n) else (uc - 1).coerceAtLeast(0)
        // Seçim var ve Shift yok: çökert.
        saga -> secim.max.coerceIn(0, n)
        else -> secim.min.coerceIn(0, n)
    }
    return if (shift) TextRange(secim.start.coerceIn(0, n), hedef) else TextRange(hedef)
}

private fun kelimeSagi(metin: String, baslangic: Int): Int {
    var i = baslangic
    while (i < metin.length && metin[i].isWhitespace()) i++
    while (i < metin.length && !metin[i].isWhitespace()) i++
    return i
}

private fun kelimeSolu(metin: String, baslangic: Int): Int {
    var i = baslangic
    while (i > 0 && metin[i - 1].isWhitespace()) i--
    while (i > 0 && !metin[i - 1].isWhitespace()) i--
    return i
}
