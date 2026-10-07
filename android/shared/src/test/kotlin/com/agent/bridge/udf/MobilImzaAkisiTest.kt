package com.agent.bridge.udf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilImzaAkisiTest {

    @Test
    fun gecerliGirdiHatasizGecer() {
        assertNull(mobilImzaGirdiHatasi("05321234567", "TURKCELL"))
        assertNull(mobilImzaGirdiHatasi("0532 123 45 67", "VODAFONE"))
        assertNull(mobilImzaGirdiHatasi("+905321234567", "TURK TELEKOM"))
    }

    @Test
    fun sabitHatVeEksikHaneReddedilir() {
        // Mobil imza yalnız cep numarasında; sabit hat geçitte de dönmez.
        assertTrue(mobilImzaGirdiHatasi("02121234567", "TURKCELL")!!.contains("05"))
        assertTrue(mobilImzaGirdiHatasi("532123456", "TURKCELL") != null)
        assertTrue(mobilImzaGirdiHatasi("", "TURKCELL") != null)
    }

    @Test
    fun tanimsizOperatorReddedilir() {
        assertEquals("GSM operatörünü seç.", mobilImzaGirdiHatasi("05321234567", "AVEA"))
        assertEquals("GSM operatörünü seç.", mobilImzaGirdiHatasi("05321234567", ""))
    }

    @Test
    fun arayuzdekiHerOperatorGecitTarafindaTanimli() {
        // Liste ile getOperatorId ayrı yerlerde; biri değişirse imza sessizce
        // "Bilinmeyen GSM operatörü" ile düşerdi.
        MOBIL_IMZA_OPERATORLERI.forEach { MobilImzaManager.getOperatorId(it) }
    }
}
