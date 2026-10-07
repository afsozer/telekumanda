package com.agent.bridge

import com.agent.bridge.ui3.nav.navigationNonceFires
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tek seferlik navigasyon olaylarının sayaç kuralı.
 *
 * Eski kod `if (nonce > 0) navigate()` diyordu ve sayaç ViewModel'de yaşadığı
 * için EKRAN DÖNDÜRME navigasyonu yeniden tetikliyordu: kullanıcı sohbetteyken
 * telefonu yatay çevirince dosyasız boş bir not editörü açılıyor, dikeye
 * dönünce de kapanmıyordu (09.08.2026, canlı görüldü).
 */
class NavigationNonceTest {
    @Test
    fun hicOlayYokkenNavigasyonYok() {
        assertFalse(navigationNonceFires(nonce = 0L, handled = 0L))
    }

    @Test
    fun ilkOlayNavigasyonTetikler() {
        assertTrue(navigationNonceFires(nonce = 1L, handled = 0L))
    }

    @Test
    fun ayniOlayIkinciKezTetiklemez() {
        // Ekran döndürme: Activity yeniden kuruluyor, sayaç aynı değerle
        // geliyor. Asıl hata buydu.
        assertFalse(navigationNonceFires(nonce = 1L, handled = 1L))
    }

    @Test
    fun sonrakiGercekOlayYineTetikler() {
        assertTrue(navigationNonceFires(nonce = 2L, handled = 1L))
    }

    @Test
    fun surecOlduktenSonraSifirlananSayacTetiklemez() {
        // Süreç ölüp ViewModel sıfırdan kurulunca sayaç 0'a döner ama
        // rememberSaveable'daki son işlenen değer Bundle'dan geri gelir.
        // Bu durumda navigasyon YAPILMAMALI — ortada yeni bir olay yok.
        assertFalse(navigationNonceFires(nonce = 0L, handled = 3L))
    }

    @Test
    fun surecOlumundenSonraGelenIlkOlayYutulmaz() {
        // Yukarıdaki durumda çağıran taraf `handled`i 0'a hizalıyor; ondan
        // sonra gelen gerçek olay normal şekilde tetiklemeli. Hizalama
        // yapılmasaydı 1 > 3 yanlış çıkar ve olay sessizce kaybolurdu.
        assertTrue(navigationNonceFires(nonce = 1L, handled = 0L))
    }
}
