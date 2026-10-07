package com.agent.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Tablo içeren prose bloğu çekmece jestinden muaf tutulur (kullanıcı isteği
// 05.08.2026); tespit yanlış-pozitifte düz metnin üstünde jesti öldürür,
// yanlış-negatifte tabloda çekmece açılır — iki yön de test edilir.
class MarkdownTableDetectTest {

    @Test
    fun gfmTablosuTespitEdilir() {
        assertTrue(containsMarkdownTable("| Ad | Soyad |\n|---|---|\n| A | B |"))
        assertTrue(containsMarkdownTable("Başlık\n\nAd | Soyad\n--- | ---\nA | B"))
        assertTrue(containsMarkdownTable("| Sol | Sağ |\n|:---|---:|"))
    }

    @Test
    fun duzMetinVePipeliSatirTabloSayilmaz() {
        assertFalse(containsMarkdownTable("düz paragraf, tablo yok"))
        // '|' geçen ama ayraçsız satır (ör. "a | b" karşılaştırması) tablo değildir.
        assertFalse(containsMarkdownTable("x | y karşılaştırması\nnormal satır"))
        // Ayraç benzeri satır tek başına (üstünde '|' yok) tablo değildir.
        assertFalse(containsMarkdownTable("başlık\n-----"))
        assertFalse(containsMarkdownTable(""))
    }

    @Test
    fun yatayCizgiTabloAyraciSanilmaz() {
        // "---" tek başına hr'dir; '|' içermediği için ayraç sayılmaz.
        assertFalse(containsMarkdownTable("a | b\n---"))
    }
}
