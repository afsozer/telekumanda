package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfResumeStoreTest {

    // ── kodlama / çözme ────────────────────────────────────────────────────

    @Test
    fun `bos json bos harita doner`() {
        assertTrue(decodePdfResumeMap("").isEmpty())
        assertTrue(decodePdfResumeMap("   ").isEmpty())
    }

    @Test
    fun `bozuk json cokme uretmez bos harita doner`() {
        assertTrue(decodePdfResumeMap("{ bu json degil").isEmpty())
    }

    @Test
    fun `kodla-coz gidis donusu korur`() {
        val map = mapOf(
            "c:/dosyalar/a.pdf" to PdfResumePosition(page = 12, ri = 5, ro = 40, mode = "r", savedAt = 1000L),
            "c:/dosyalar/b.pdf" to PdfResumePosition(page = 1, mode = "p", savedAt = 2000L),
        )
        val decoded = decodePdfResumeMap(encodePdfResumeMap(map))
        assertEquals(map, decoded)
    }

    @Test
    fun `eksik alanli girdi makul varsayilanla coziliyor`() {
        val json = """{"c:/x.pdf": {"page": 3}}"""
        val decoded = decodePdfResumeMap(json)
        assertEquals(PdfResumePosition(page = 3, ri = 0, ro = 0, mode = "p", savedAt = 0L), decoded["c:/x.pdf"])
    }

    @Test
    fun `bozuk tekil girdi digerlerini bozmadan atlanir`() {
        // JSONObject.optJSONObject bir dizi/skaler değer için null döner —
        // tek satır bozuksa tüm haritayı kaybetmemeli.
        val json = """{"a.pdf": {"page": 1}, "b.pdf": "bozuk deger"}"""
        val decoded = decodePdfResumeMap(json)
        assertEquals(setOf("a.pdf"), decoded.keys)
    }

    // ── LRU budama ─────────────────────────────────────────────────────────

    @Test
    fun `tavan asilmayinca yeni girdi eklenir hicbiri atilmaz`() {
        val map = mapOf("a" to PdfResumePosition(page = 1, savedAt = 1L))
        val updated = putPdfResumePosition(map, "b", PdfResumePosition(page = 2, savedAt = 2L), maxEntries = 5)
        assertEquals(2, updated.size)
        assertEquals(1, updated["a"]?.page)
        assertEquals(2, updated["b"]?.page)
    }

    @Test
    fun `ayni anahtar guncellenince boyut buyumez`() {
        val map = mapOf("a" to PdfResumePosition(page = 1, savedAt = 1L))
        val updated = putPdfResumePosition(map, "a", PdfResumePosition(page = 9, savedAt = 5L), maxEntries = 5)
        assertEquals(1, updated.size)
        assertEquals(9, updated["a"]?.page)
    }

    @Test
    fun `tavan asilinca en eski t degerine sahip girdi atilir`() {
        val map = mapOf(
            "eski" to PdfResumePosition(page = 1, savedAt = 1L),
            "orta" to PdfResumePosition(page = 2, savedAt = 2L),
        )
        val updated = putPdfResumePosition(map, "yeni", PdfResumePosition(page = 3, savedAt = 3L), maxEntries = 2)
        assertEquals(2, updated.size)
        assertEquals(setOf("orta", "yeni"), updated.keys)
        assertNull(updated["eski"])
    }

    @Test
    fun `varsayilan tavan 100`() {
        var map = emptyMap<String, PdfResumePosition>()
        for (i in 1..105) {
            map = putPdfResumePosition(map, "k$i", PdfResumePosition(page = i, savedAt = i.toLong()))
        }
        assertEquals(100, map.size)
        // İlk 5 (t=1..5) en eski olduğu için atılmış olmalı.
        assertNull(map["k1"])
        assertNull(map["k5"])
        assertTrue(map.containsKey("k105"))
        assertTrue(map.containsKey("k6"))
    }
}
