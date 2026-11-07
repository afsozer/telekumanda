package com.agent.bridge.ui3.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bağlam ayrıntısının saf fonksiyonları.
 */
class Ui3BaglamTest {

    @Test fun baglamEsigiVurguSinirini80deTutar() {
        // Eşik köprüdeki hesapla değil ARAYÜZ kararıyla ilgili; sabitin kaymasını
        // fark edelim diye bağlanıyor.
        assertEquals(80, UI3_BAGLAM_ESIGI)
        assertFalse(baglamDoldu(79))
        assertTrue(baglamDoldu(80))
        // null = köprü ölçemiyor. "Bilmiyorum" bir uyarı sebebi DEĞİL.
        assertFalse(baglamDoldu(null))
    }

    // Binlik ayraç ELDE konuyor: NumberFormat cihaz diline bakıyor ve aynı sayı
    // iki telefonda farklı yazılıyordu (Türkçe-I tuzağıyla aynı sınıf).
    @Test fun baglamSayisiBinlikAyracKoyar() {
        assertEquals("44.555", baglamSayisi(44_555))
        assertEquals("999", baglamSayisi(999))
        assertEquals("1.000", baglamSayisi(1_000))
        assertEquals("1.234.567", baglamSayisi(1_234_567))
    }

    // 0 "pencere sıfır" diye okunurdu; köprü pencereyi ısıtmadan 0 gönderiyor.
    @Test fun baglamSayisiSifiriBilinmiyorSayar() {
        assertEquals("—", baglamSayisi(0))
        assertEquals("—", baglamSayisi(-1))
    }
}
