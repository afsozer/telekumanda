package com.agent.bridge

import com.agent.bridge.ui2.chat.parseMessageImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImagesParseTest {

    @Test
    fun kullaniciEkiDuzMarkerIleAyristirilir() {
        val raw = "bak buna\n\nEk dosyalar:\n- foto.png: C:/tmp/foto.png"
        val (text, images) = parseMessageImages(raw)
        assertEquals("bak buna", text)
        assertEquals(1, images.size)
        assertEquals("C:/tmp/foto.png", images[0].path)
    }

    // Asistan markdown yazdığı için marker kalın gelebiliyor; düz eşleşme bunu
    // kaçırıyordu ve görsel sohbette düz metin olarak kalıyordu.
    @Test
    fun asistanKalinMarkerIleDeAyristirilir() {
        val raw = "iste ekran:\n\n**Ek dosyalar:**\n- ekran.png: C:/tmp/ekran.png"
        val (text, images) = parseMessageImages(raw)
        assertEquals("iste ekran:", text)
        assertEquals(1, images.size)
        assertEquals("ekran.png", images[0].name)
    }

    @Test
    fun gorselOlmayanEkMetindeKalir() {
        val raw = "dosya\n\nEk dosyalar:\n- rapor.pdf: C:/tmp/rapor.pdf"
        val (text, images) = parseMessageImages(raw)
        assertTrue(images.isEmpty())
        assertTrue(text.contains("rapor.pdf"))
    }

    @Test
    fun karisikEklerdeYalnizGorselAyrilir() {
        val raw = "ikisi\n\nEk dosyalar:\n- a.png: C:/tmp/a.png\n- b.pdf: C:/tmp/b.pdf"
        val (text, images) = parseMessageImages(raw)
        assertEquals(1, images.size)
        assertEquals("a.png", images[0].name)
        assertTrue(text.contains("b.pdf"))
        assertTrue(!text.contains("a.png"))
    }

    @Test
    fun markerYoksaMetinAynenDoner() {
        val raw = "duz mesaj, ek yok"
        val (text, images) = parseMessageImages(raw)
        assertEquals(raw, text)
        assertTrue(images.isEmpty())
    }
}
