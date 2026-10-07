package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfReaderContentTest {

    // ── sayfa bölme ────────────────────────────────────────────────────────

    @Test
    fun `sayfa konumlarina gore boler ve numaralari 1den baslatir`() {
        val markdown = "Birinci sayfa.\n\nİkinci sayfa."
        val starts = listOf(0, 16)
        val pages = splitReaderPages(markdown, starts)
        assertEquals(2, pages.size)
        assertEquals(1, pages[0].number)
        assertEquals("Birinci sayfa.", pages[0].markdown)
        assertEquals(2, pages[1].number)
        assertEquals("İkinci sayfa.", pages[1].markdown)
    }

    @Test
    fun `bos sayfa atlanir ama sonraki sayfanin numarasi kaymaz`() {
        // 2. sayfa metinsiz: köprü onun için ayrı bir parça üretmez, başlangıç
        // konumu 3. sayfayla aynı olur.
        val markdown = "İlk.\n\nÜçüncü."
        val pages = splitReaderPages(markdown, listOf(0, 6, 6))
        assertEquals(listOf(1, 3), pages.map { it.number })
        assertEquals("Üçüncü.", pages[1].markdown)
    }

    @Test
    fun `dizgi boyunu asan konum cokme uretmez`() {
        // Köprü konumları trimEnd ÖNCESİ hesaplıyor: sondaki boş sayfaların
        // başlangıcı kırpılmış dizginin dışında kalabilir.
        val pages = splitReaderPages("Kısa metin", listOf(0, 999))
        assertEquals(1, pages.size)
        assertEquals("Kısa metin", pages[0].markdown)
    }

    @Test
    fun `konum yoksa tek sayfa doner`() {
        val pages = splitReaderPages("metin", emptyList())
        assertEquals(1, pages.size)
        assertEquals(1, pages[0].number)
    }

    @Test
    fun `bos markdown bos liste doner`() {
        assertTrue(splitReaderPages("", listOf(0, 5)).isEmpty())
    }

    // ── satır ayrıştırma ───────────────────────────────────────────────────

    @Test
    fun `borulari hucrelere ayirir kenar borusunu veri saymaz`() {
        assertEquals(listOf("a", "b", "c"), splitTableRow("| a | b | c |"))
    }

    @Test
    fun `bos hucre korunur`() {
        assertEquals(listOf("", "a"), splitTableRow("|  | a |"))
    }

    @Test
    fun `kacisli boru hucre icerigidir`() {
        // Köprü hücredeki '|' karakterini `\|` olarak kaçırıyor; ayraç sanılırsa
        // sütunlar kayıyor.
        assertEquals(listOf("a|b", "c"), splitTableRow("""| a\|b | c |"""))
    }

    @Test
    fun `ayrac satiri taninir`() {
        assertTrue(isTableSeparator("| --- | --- |"))
        assertTrue(isTableSeparator("|:---:|---:|"))
        assertEquals(false, isTableSeparator("| a | b |"))
        assertEquals(false, isTableSeparator("düz metin"))
    }

    // ── blok ayırma ────────────────────────────────────────────────────────

    @Test
    fun `dar tablo markdown olarak kalir`() {
        val md = "Giriş.\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\nSon."
        val blocks = splitReaderBlocks(md)
        assertEquals(1, blocks.size)
        val prose = blocks[0] as ReaderBlock.Prose
        assertTrue(prose.markdown.contains("| a | b |"))
        assertTrue(prose.markdown.contains("Son."))
    }

    @Test
    fun `genis tablo ayri blok olur ve cevresi duz metin kalir`() {
        val md = buildString {
            append("Giriş.\n\n")
            append("| a | b | c | d |\n")
            append("| --- | --- | --- | --- |\n")
            append("| 1 | 2 | 3 | 4 |\n")
            append("| 5 | 6 | 7 | 8 |\n\n")
            append("Son.")
        }
        val blocks = splitReaderBlocks(md)
        assertEquals(3, blocks.size)
        assertTrue((blocks[0] as ReaderBlock.Prose).markdown.startsWith("Giriş."))
        val table = blocks[1] as ReaderBlock.Table
        assertEquals(listOf("a", "b", "c", "d"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("5", "6", "7", "8"), table.rows[1])
        assertTrue((blocks[2] as ReaderBlock.Prose).markdown.contains("Son."))
    }

    @Test
    fun `esik sinirinda dar sayilir`() {
        // 3 sütun Markwon'da hâlâ okunur; kart eşiği 4'ten başlar.
        val md = "| a | b | c |\n| --- | --- | --- |\n| 1 | 2 | 3 |"
        assertTrue(splitReaderBlocks(md).single() is ReaderBlock.Prose)
    }

    @Test
    fun `veri satiri olmayan genis tablo da blok olur`() {
        // Boş resmî form: köprü tabloyu görüyor ama içi dolu değil. Başlığı
        // kaybetmemek için yine de blok üretilmeli.
        val md = "| ad | soyad | tc | tarih |\n| --- | --- | --- | --- |"
        val table = splitReaderBlocks(md).single() as ReaderBlock.Table
        assertEquals(4, table.header.size)
        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun `borusuz metin tablo sayilmaz`() {
        val blocks = splitReaderBlocks("Yalnızca düz bir paragraf.")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is ReaderBlock.Prose)
    }

    // ── sayfa ↔ liste dizini eşlemesi ─────────────────────────────────────

    @Test
    fun `liste dizininden sayfa numarasi coziliyor uyari seridi olmadan`() {
        val pages = listOf(ReaderPage(1, "a"), ReaderPage(3, "b"))
        assertEquals(1, readerListIndexToPage(pages, offset = 0, index = 0))
        assertEquals(3, readerListIndexToPage(pages, offset = 0, index = 1))
    }

    @Test
    fun `liste dizininden sayfa numarasi coziliyor uyari seridiyle`() {
        // 0. öğe uyarı şeridiyse gerçek sayfalar 1'den kaymış demektir.
        val pages = listOf(ReaderPage(1, "a"), ReaderPage(3, "b"))
        assertEquals(null, readerListIndexToPage(pages, offset = 1, index = 0))
        assertEquals(1, readerListIndexToPage(pages, offset = 1, index = 1))
        assertEquals(3, readerListIndexToPage(pages, offset = 1, index = 2))
    }

    @Test
    fun `araligin disindaki dizin null doner`() {
        val pages = listOf(ReaderPage(1, "a"))
        assertEquals(null, readerListIndexToPage(pages, offset = 0, index = 5))
        assertEquals(null, readerListIndexToPage(pages, offset = 0, index = -1))
    }

    @Test
    fun `sayfa numarasindan liste dizinine coziliyor`() {
        val pages = listOf(ReaderPage(1, "a"), ReaderPage(3, "b"), ReaderPage(4, "c"))
        assertEquals(0, readerPageToListIndex(pages, offset = 0, page = 1))
        // 2. sayfa metinsiz (atlanmış): en yakın sonraki sayfaya (3) düşer.
        assertEquals(1, readerPageToListIndex(pages, offset = 0, page = 2))
        assertEquals(2, readerPageToListIndex(pages, offset = 0, page = 4))
    }

    @Test
    fun `sayfa numarasindan liste dizinine coziliyor uyari seridiyle`() {
        val pages = listOf(ReaderPage(1, "a"), ReaderPage(2, "b"))
        assertEquals(1, readerPageToListIndex(pages, offset = 1, page = 1))
        assertEquals(2, readerPageToListIndex(pages, offset = 1, page = 2))
    }

    @Test
    fun `belgedeki son sayfadan sonrasi icin null doner`() {
        val pages = listOf(ReaderPage(1, "a"), ReaderPage(2, "b"))
        assertEquals(null, readerPageToListIndex(pages, offset = 0, page = 5))
    }
}
