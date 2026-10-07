package com.agent.bridge.ui3.chat

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ok tuşunun imleci nereye taşıdığı.
 *
 * Bu hesap `BasicTextField`in yerine geçiyor (alan ok tuşlarını hiç görmüyor,
 * gerekçe [ui3OkTuslari]), yani buradaki her kural alanın kaybettiği bir
 * davranışın yerine konmuş: seçim çökertme, Shift ile genişletme, kelime atlama.
 */
class Ui3OkTuslariTest {

    private val metin = "merhaba dünya"

    @Test
    fun `duz ok bir karakter ilerletir`() {
        assertEquals(TextRange(4), okTusuSecimi(metin, TextRange(3), saga = true, shift = false, kelime = false))
        assertEquals(TextRange(2), okTusuSecimi(metin, TextRange(3), saga = false, shift = false, kelime = false))
    }

    @Test
    fun `metnin uclarinda takilir, tasmaz`() {
        assertEquals(TextRange(0), okTusuSecimi(metin, TextRange(0), saga = false, shift = false, kelime = false))
        val son = metin.length
        assertEquals(TextRange(son), okTusuSecimi(metin, TextRange(son), saga = true, shift = false, kelime = false))
    }

    @Test
    fun `secim varken duz ok secimi cokertir`() {
        // Sola basınca başına, sağa basınca sonuna — bir karakter ilerletmek
        // imleci seçimin dışına atardı.
        assertEquals(TextRange(2), okTusuSecimi(metin, TextRange(2, 7), saga = false, shift = false, kelime = false))
        assertEquals(TextRange(7), okTusuSecimi(metin, TextRange(2, 7), saga = true, shift = false, kelime = false))
    }

    @Test
    fun `shift capayi yerinde tutar, ucu tasir`() {
        assertEquals(TextRange(2, 8), okTusuSecimi(metin, TextRange(2, 7), saga = true, shift = true, kelime = false))
        assertEquals(TextRange(2, 6), okTusuSecimi(metin, TextRange(2, 7), saga = false, shift = true, kelime = false))
    }

    @Test
    fun `shift ile secim sifirdan baslatilir`() {
        assertEquals(TextRange(3, 4), okTusuSecimi(metin, TextRange(3), saga = true, shift = true, kelime = false))
    }

    @Test
    fun `kelime atlama once bosluklari sonra kelimeyi yutar`() {
        // "merhaba| dünya" → sağa: boşluk + "dünya" = sona.
        assertEquals(TextRange(13), okTusuSecimi(metin, TextRange(7), saga = true, shift = false, kelime = true))
        // "merhaba dünya|" → sola: "dünya"nın başına.
        assertEquals(TextRange(8), okTusuSecimi(metin, TextRange(13), saga = false, shift = false, kelime = true))
    }

    @Test
    fun `kelime atlama shift ile secimi buyutur`() {
        assertEquals(TextRange(0, 7), okTusuSecimi(metin, TextRange(0), saga = true, shift = true, kelime = true))
    }

    @Test
    fun `hedef satir metnin icindeyse dondurulur`() {
        assertEquals(2, okHedefSatiri(satir = 1, satirSayisi = 4, asagi = true))
        assertEquals(0, okHedefSatiri(satir = 1, satirSayisi = 4, asagi = false))
    }

    @Test
    fun `ilk satirda yukari ve son satirda asagi tasar`() {
        // null = "satır yok, metnin ucuna git". Odak kaçışının yerine geçen
        // davranış bu; tuş yine tüketiliyor.
        assertEquals(null, okHedefSatiri(satir = 0, satirSayisi = 3, asagi = false))
        assertEquals(null, okHedefSatiri(satir = 2, satirSayisi = 3, asagi = true))
    }

    @Test
    fun `tek satirlik metinde iki yon de tasar`() {
        assertEquals(null, okHedefSatiri(satir = 0, satirSayisi = 1, asagi = true))
        assertEquals(null, okHedefSatiri(satir = 0, satirSayisi = 1, asagi = false))
    }

    @Test
    fun `yerlesim yokken satir oku secimi degistirmez`() {
        // null dönüyor ki çağıran taraf seçime dokunmadan tuşu yutsun.
        assertEquals(
            null,
            satirOkuSecimi(yerlesim = null, metinUzunlugu = 5, secim = TextRange(2), asagi = true, shift = false),
        )
    }

    @Test
    fun `bos metinde patlamaz`() {
        assertEquals(TextRange(0), okTusuSecimi("", TextRange(0), saga = true, shift = false, kelime = true))
        assertEquals(TextRange(0), okTusuSecimi("", TextRange(0), saga = false, shift = false, kelime = false))
    }
}
