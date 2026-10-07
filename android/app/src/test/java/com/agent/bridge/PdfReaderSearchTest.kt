package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfReaderSearchTest {

    @Test
    fun `bos sorgu bos sonuc doner`() {
        val pages = listOf(ReaderPage(1, "metin"))
        val result = searchReaderPages(pages, "   ")
        assertTrue(result.hits.isEmpty())
        assertFalse(result.truncated)
    }

    @Test
    fun `duz eslesme sayfa numarasiyla doner`() {
        val pages = listOf(ReaderPage(1, "Giriş."), ReaderPage(3, "Bu tensipte süre otuz gündür."))
        val hits = searchReaderPages(pages, "süre").hits
        assertEquals(1, hits.size)
        assertEquals(3, hits[0].page)
    }

    @Test
    fun `sayfa basina birden cok eslesme donebilir`() {
        val pages = listOf(ReaderPage(1, "icra icra icra"))
        val hits = searchReaderPages(pages, "icra").hits
        assertEquals(3, hits.size)
        assertTrue(hits.all { it.page == 1 })
    }

    @Test
    fun `limit asilinca kesilir ve truncated isaretlenir`() {
        val pages = listOf(ReaderPage(1, "a ".repeat(10)))
        val result = searchReaderPages(pages, "a", limit = 3)
        assertEquals(3, result.hits.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `limit asilmayinca truncated false`() {
        val pages = listOf(ReaderPage(1, "tek eşleşme burada"))
        val result = searchReaderPages(pages, "eşleşme", limit = 200)
        assertEquals(1, result.hits.size)
        assertFalse(result.truncated)
    }

    // ── Türkçe I/İ tuzağı ──────────────────────────────────────────────────

    @Test
    fun `noktali buyuk I Turkce locale ile kucuk i olur ve eslesir`() {
        // Locale VERMEDEN lowercase() 'İ' harfini iki karakterli "i" + birleşen
        // nokta imine çevirir; bu durumda "İCRA" araması "icra" içeren sayfayı
        // KAÇIRIRDI. Locale("tr","TR") tek karakterli 'i' üretir.
        val pages = listOf(ReaderPage(1, "Bu belge icra takibiyle ilgilidir."))
        val hits = searchReaderPages(pages, "İCRA").hits
        assertEquals(1, hits.size)
        assertEquals(1, hits[0].page)
    }

    @Test
    fun `noktasiz buyuk I Turkce locale ile noktasiz i olur farkli kelimeyle karismaz`() {
        // ASCII 'I' Türkçe locale'de noktasız 'ı' olur (İngilizce kuralda 'i'
        // olurdu). "ILGILI" -> "ılgılı"; "ilgili" (noktalı, gerçek kelime)
        // İLE KARIŞMAMALI.
        val pages = listOf(
            ReaderPage(1, "Konuyla ılgılı bir not."),
            ReaderPage(2, "Bu konuyla ilgili bir not."),
        )
        val hits = searchReaderPages(pages, "ILGILI").hits
        assertEquals(listOf(1), hits.map { it.page })
    }

    // ── snippet ────────────────────────────────────────────────────────────

    @Test
    fun `snippet markdown isaretlerini temizler`() {
        val pages = listOf(ReaderPage(1, "**Önemli:** madde şu şekildedir | tablo | değeri"))
        val hits = searchReaderPages(pages, "madde").hits
        assertEquals(1, hits.size)
        assertFalse(hits[0].snippet.contains("**"))
        assertFalse(hits[0].snippet.contains("|"))
    }

    @Test
    fun `snippet metin basinda ise onek uc nokta almaz`() {
        val pages = listOf(ReaderPage(1, "Baş."))
        val hits = searchReaderPages(pages, "Baş").hits
        assertFalse(hits[0].snippet.startsWith("…"))
    }

    @Test
    fun `snippet uzun metnin ortasindaysa iki yandan da kirpilir`() {
        val long = "x".repeat(100) + "HEDEF" + "y".repeat(100)
        val pages = listOf(ReaderPage(1, long))
        val hits = searchReaderPages(pages, "HEDEF").hits
        assertTrue(hits[0].snippet.startsWith("…"))
        assertTrue(hits[0].snippet.endsWith("…"))
        assertTrue(hits[0].snippet.contains("HEDEF"))
    }
}
