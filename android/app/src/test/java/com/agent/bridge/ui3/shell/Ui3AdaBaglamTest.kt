package com.agent.bridge.ui3.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Adanın bağlam yüzdesi: arayüzün "sayı uydurmama" bekçisi.
 *
 * Yüzde token / pencere bölmesinden geliyor.
 */
class Ui3AdaBaglamTest {

    @Test
    fun bolmeYuzdeVerir() {
        assertEquals(25, adaBaglamYuzdesi(contextTokens = 2_000, contextWindow = 8_000))
    }

    // Pencere bilinmiyorken (0) bölme yapılmaz: "%0" da bir iddia olurdu.
    @Test
    fun pencereBilinmiyorkenYuzdeYok() {
        assertNull(adaBaglamYuzdesi(contextTokens = 44_555, contextWindow = 0))
    }

    // Token pencereyi aşsa bile ada taşmasın: sağ bant "%100"den geniş değil.
    @Test
    fun yuzdeSinirlarinIcindeKalir() {
        assertEquals(100, adaBaglamYuzdesi(contextTokens = 44_555, contextWindow = 12_000))
        assertEquals(0, adaBaglamYuzdesi(contextTokens = 0, contextWindow = 12_000))
    }
}
