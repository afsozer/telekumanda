package com.agent.bridge.ui3.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Adanın bağlam yüzdesi — arayüzün "sayı uydurmama" bekçisi.
 *
 * Canlıda ada %100 gösterdi, köprü ise aynı anda "ölçemiyorum" diyordu
 * (`contextTokens=44555, contextWindow=0, contextPct=null`). Sebep adanın
 * yüzdeyi kendi başına bölmesiydi: üst-düzey pencere alanında ÖNCEKİ oturumdan
 * kalan küçük bir sayı duruyordu ve yeni oturumun token'ı ona bölününce oran
 * tavana yapıştı. Bu dosya o yalanın geri gelmesini engelliyor.
 */
class Ui3AdaBaglamTest {

    // ASIL REGRESYON. Küçük pencereli bir oturumdan (yerel model, 12k) penceresi
    // HENÜZ BİLİNMEYEN yeni bir opencode oturumuna geçildiğinde ada hiçbir şey
    // göstermeli — %100 değil. Bayat pencere state'te sarksa bile opencode'da
    // artık okunmuyor; tek kaynak köprünün contextPct'i.
    @Test
    fun kucukPencereliOturumdanGecistePencereBilinmiyorsaYuzdeYok() {
        val yuzde = adaBaglamYuzdesi(
            opencodeAktif = true,
            contextPct = null,
            contextTokens = 44_555,
            contextWindow = 12_000,
        )

        assertNull(yuzde)
    }

    @Test
    fun opencodeYuzdesiKopruninHesabindanGelir() {
        // Bölme 371 verirdi; köprü 37 diyorsa ada 37 der.
        assertEquals(
            37,
            adaBaglamYuzdesi(
                opencodeAktif = true,
                contextPct = 37,
                contextTokens = 44_555,
                contextWindow = 12_000,
            ),
        )
    }

    // Köprü bozuk bir sayı gönderirse (ya da sözleşme değişirse) ada taşmasın:
    // sağ bant "%100"den geniş değil, üç haneden fazlası kırpılıp YANLIŞ sayı
    // gösterirdi.
    @Test
    fun opencodeYuzdesiSinirlarinIcindeKalir() {
        assertEquals(100, adaBaglamYuzdesi(true, 480, 0, 0))
        assertEquals(0, adaBaglamYuzdesi(true, -5, 0, 0))
    }

    // Diğer backend'lerde köprü contextPct göndermiyor; orada bölme tek yol.
    @Test
    fun digerBackendlerdeBolmeSuruyor() {
        assertEquals(
            25,
            adaBaglamYuzdesi(
                opencodeAktif = false,
                contextPct = null,
                contextTokens = 2_000,
                contextWindow = 8_000,
            ),
        )
    }

    // Pencere bilinmiyorken (0) bölme yapılmaz: "%0" da bir iddia olurdu.
    @Test
    fun pencereBilinmiyorkenDigerBackendlerdeDeYuzdeYok() {
        assertNull(adaBaglamYuzdesi(false, null, 44_555, 0))
    }

    // opencode'da contextPct null ise, üst-düzey alanlar TUTARLI olsa bile
    // yüzde çizilmez: köprü "ölçemiyorum" diyorsa arayüz de bilmiyordur.
    @Test
    fun opencodeDaKopruOlcemiyorsaBolmeyeDusulmez() {
        assertNull(
            adaBaglamYuzdesi(
                opencodeAktif = true,
                contextPct = null,
                contextTokens = 2_000,
                contextWindow = 8_000,
            ),
        )
    }
}
