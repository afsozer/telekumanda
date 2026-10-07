package com.agent.bridge.ui3.chat

import com.agent.bridge.OpencodeTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Görev panosunun okunabilir tarafı — cihazsız sınanabilsin diye Compose'un
 * dışında duran saf fonksiyonlar.
 *
 * Panonun bütün değeri "3/7 · şu an ne yapıyor" satırında: uzun otonom koşuyu
 * telefondan izlemenin tek amacı bu. Sayının yanlış olması ilerlemeyi olduğundan
 * ileri/geri gösterir, satırın boş kalması ise panoyu yalnız sayı gösteren bir
 * kutuya indirir.
 */
class Ui3GorevPanosuTest {

    private val liste = listOf(
        OpencodeTodo("Köprüyü oku", "completed"),
        OpencodeTodo("Uçları bul", "completed"),
        OpencodeTodo("Panoyu ekle", "in_progress"),
        OpencodeTodo("Testleri yaz", "pending"),
    )

    @Test fun ozetSayacVeSurenMaddeyiBirlikteVerir() {
        assertEquals("2/4 · Panoyu ekle", gorevPanosuOzeti(liste))
    }

    @Test fun iptalEdilenMaddeBitmisSayilir() {
        // "cancelled" artık beklemiyor; bekleyenler arasında saymak kullanıcıya
        // hiç bitmeyecek bir sayaç gösterirdi.
        val iptalli = liste.map { if (it.content == "Testleri yaz") it.copy(status = "cancelled") else it }
        assertEquals("3/4 · Panoyu ekle", gorevPanosuOzeti(iptalli))
        assertEquals(3, gorevPanosuBiten(iptalli))
    }

    @Test fun inProgressYokkenIlkBitmemisMaddeyeDuser() {
        // Bazı ajanlar todowrite'ı yalnız pending/completed ile kullanıyor,
        // in_progress'i hiç işaretlemiyor.
        val isaretsiz = liste.map { if (it.suradaki) it.copy(status = "pending") else it }
        assertEquals("2/4 · Panoyu ekle", gorevPanosuOzeti(isaretsiz))
    }

    @Test fun hepsiBitinceYalnizSayacKalir() {
        val bitmis = liste.map { it.copy(status = "completed") }
        assertEquals("4/4", gorevPanosuOzeti(bitmis))
    }

    @Test fun bosListedeOzetYok() {
        assertEquals("", gorevPanosuOzeti(emptyList()))
    }

    @Test fun panoBostaVeListesizkenHicCizilmez() {
        // Kalıcı yük eklememe kuralı: composer'ın üstündeki her dp sohbetten
        // çalınıyor (RunPod hapı tam bu yüzden kaldırılmıştı).
        assertFalse(gorevPanosuGorunur(emptyList(), baglamYuzdesi = null))
        assertTrue(gorevPanosuGorunur(liste, baglamYuzdesi = null))
    }

    // SÜREKLİ "Bağlam %X" ÇUBUĞU KALKTI: aynı sayı adada zaten duruyor, pano
    // onun ikinci kopyası olmamalıydı. Eşiğin ALTINDA bağlam panoyu hiç açmaz.
    @Test fun esikAltiBaglamPanoyuAcmaz() {
        assertFalse(gorevPanosuGorunur(emptyList(), baglamYuzdesi = 0))
        assertFalse(gorevPanosuGorunur(emptyList(), baglamYuzdesi = 42))
        assertFalse(gorevPanosuGorunur(emptyList(), baglamYuzdesi = UI3_BAGLAM_ESIGI - 1))
    }

    // Eşiğin ÜSTÜNDE pano listesiz de açılır: geriye kalan tek şey EYLEM
    // (kehribar uyarı + Sıkıştır) ve Sıkıştır tam da tur bitmişken lazım —
    // o an todo listesi çoktan boşalmış olabiliyor.
    @Test fun esikUstuBaglamListesizDePanoyuAcar() {
        assertTrue(gorevPanosuGorunur(emptyList(), baglamYuzdesi = UI3_BAGLAM_ESIGI))
        assertTrue(gorevPanosuGorunur(emptyList(), baglamYuzdesi = 97))
    }

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
