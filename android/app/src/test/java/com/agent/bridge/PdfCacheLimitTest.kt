package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfCacheLimitTest {

    private fun entries(vararg pairs: Pair<String, Long>) = pairs.map { CacheEntry(it.first, it.second) }

    @Test
    fun `tavan altinda hicbir sey silinmez`() {
        val files = entries("a" to 1L, "b" to 2L)
        assertTrue(cacheFilesToEvict(files, keep = 20).isEmpty())
    }

    @Test
    fun `tam tavanda silinmez`() {
        val files = (1..20).map { CacheEntry("f$it", it.toLong()) }
        assertTrue(cacheFilesToEvict(files, keep = 20).isEmpty())
    }

    @Test
    fun `tavani asinca en eskiler dusuyor`() {
        val files = (1..23).map { CacheEntry("f$it", it.toLong()) }
        assertEquals(listOf("f3", "f2", "f1"), cacheFilesToEvict(files, keep = 20))
    }

    @Test
    fun `en yeni kullanilan her zaman kalir`() {
        val files = entries("eski" to 1L, "yeni" to 99L, "orta" to 50L)
        assertEquals(listOf("eski"), cacheFilesToEvict(files, keep = 2))
    }

    @Test
    fun `esit damgada karar ada gore kararli`() {
        val files = entries("b" to 5L, "a" to 5L, "c" to 5L)
        // Aynı damga: ad sırası kararı belirler; iki çağrı aynı sonucu verir.
        val first = cacheFilesToEvict(files, keep = 1)
        val second = cacheFilesToEvict(files.reversed(), keep = 1)
        assertEquals(first, second)
        assertEquals(listOf("b", "c"), first)
    }

    @Test
    fun `keep sifir veya negatifse silme yapilmaz`() {
        // Savunma amaçlı: yanlış bir sabit tüm önbelleği silmesin.
        val files = (1..5).map { CacheEntry("f$it", it.toLong()) }
        assertTrue(cacheFilesToEvict(files, keep = 0).isEmpty())
        assertTrue(cacheFilesToEvict(files, keep = -3).isEmpty())
    }

    @Test
    fun `bos liste bos sonuc`() {
        assertTrue(cacheFilesToEvict(emptyList(), keep = 20).isEmpty())
    }
}
