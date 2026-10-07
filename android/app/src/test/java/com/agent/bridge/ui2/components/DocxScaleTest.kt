package com.agent.bridge.ui2.components

import org.junit.Assert.assertEquals
import org.junit.Test

// Önizleme ölçeği (kullanıcı kararı 05.08.2026): DOCX'ler punto taşımadığında
// her şey 14sp çiziliyor ve dar telefonda belge okunmuyordu.
class DocxScaleTest {

    @Test
    fun varsayilanBiraKucuk() {
        // Kullanıcı "biraz daha ufak" dedi — 1.0'ın altında ama okunur.
        assertEquals(0.8f, DOCX_SCALE_DEFAULT, 0.0001f)
    }

    @Test
    fun adimlarIzgaraDisiKalanFloatlariYuvarlar() {
        // 0.8f + 0.1f = 0.90000004 → yüzde etiketi "%90" yerine "%90" görünsün
        // diye 0.05 ızgarasına oturtuluyor.
        assertEquals(0.9f, adjustDocxScale(0.8f, 0.1f), 0.0001f)
        assertEquals(0.7f, adjustDocxScale(0.8f, -0.1f), 0.0001f)
        assertEquals(0.85f, adjustDocxScale(0.87f, -0.02f), 0.0001f)
    }

    @Test
    fun altVeUstSinirdaDurur() {
        assertEquals(0.6f, adjustDocxScale(0.6f, -0.1f), 0.0001f)
        assertEquals(1.4f, adjustDocxScale(1.4f, 0.1f), 0.0001f)
    }
}
