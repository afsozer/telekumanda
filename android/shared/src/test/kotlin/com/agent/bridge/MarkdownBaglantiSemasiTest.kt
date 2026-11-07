package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Markdown'daki dosya-dışı bağlantılardan yalnız http/https/mailto/tel açılır.
class MarkdownBaglantiSemasiTest {
    @Test
    fun izinliSemalarAynenAcilir() {
        assertEquals("https://example.com/a?b=1", markdownBaglantiHedefi("https://example.com/a?b=1"))
        assertEquals("http://100.64.0.1:8787/ui", markdownBaglantiHedefi("http://100.64.0.1:8787/ui"))
        assertEquals("HTTPS://EXAMPLE.COM", markdownBaglantiHedefi("HTTPS://EXAMPLE.COM"))
        assertEquals("mailto:av@example.com", markdownBaglantiHedefi("mailto:av@example.com"))
        assertEquals("tel:+905551112233", markdownBaglantiHedefi("tel:+905551112233"))
    }

    @Test
    fun semasizWwwHttpsIleTamamlanir() {
        assertEquals("https://www.example.com/x", markdownBaglantiHedefi("www.example.com/x"))
    }

    @Test
    fun digerSemalarAcilmaz() {
        listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.x;end",
            "content://com.agent.bridge.fileprovider/files/settings.xml",
            "file:///data/data/com.agent.bridge/shared_prefs/settings.xml",
            "javascript:alert(1)",
            "market://details?id=com.x",
            "sms:+905551112233",
            "geo:0,0",
            "otherapp://deep/link",
            "",
            "düz metin",
        ).forEach { assertNull(it, markdownBaglantiHedefi(it)) }
    }
}
