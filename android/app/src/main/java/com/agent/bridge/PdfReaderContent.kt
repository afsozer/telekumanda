package com.agent.bridge

/**
 * Okuma modu içeriğinin saf (Android'siz) modeli.
 *
 * Köprü tek bir markdown dizgisi ve sayfa başlangıç konumları döner. Burası onu
 * ekranda çizilebilir parçalara böler. Compose'a hiç bağlı değil — asıl sebebi
 * test: sayfa bölme ve geniş tablo tespiti JVM birim testinden geçiyor.
 */

/** Markdown'ın bir sayfaya düşen parçası. [number] 1'den başlar (belgedeki sayfa). */
data class ReaderPage(val number: Int, val markdown: String)

/**
 * Markdown'ı sayfa parçalarına böler.
 *
 * Neden tek parça çizilmiyor: 150 sayfalık bir kitap ~225 KB markdown demek ve
 * bunu tek TextView'a vermek hem ayrıştırmada hem yerleşimde saniyeler yiyor.
 * Sayfa parçaları tembel listede çizilir, yalnız görünen sayfa iş yapar.
 *
 * Boş sayfa (metni olmayan) ATLANIR — numara boşluğu bilgi taşır, o sayfada
 * okunacak bir şey yoktur.
 */
fun splitReaderPages(markdown: String, pageStarts: List<Int>): List<ReaderPage> {
    if (markdown.isEmpty()) return emptyList()
    if (pageStarts.isEmpty()) return listOf(ReaderPage(1, markdown))
    val out = mutableListOf<ReaderPage>()
    for (i in pageStarts.indices) {
        // Konumlar köprüde `trimEnd()` ÖNCESİ hesaplanır: baştaki hiçbir konum
        // kaymaz ama sondaki boş sayfaların başlangıcı dizgi boyunu aşabilir.
        val start = pageStarts[i].coerceIn(0, markdown.length)
        val end = (pageStarts.getOrNull(i + 1) ?: markdown.length).coerceIn(start, markdown.length)
        val slice = markdown.substring(start, end).trim()
        if (slice.isNotEmpty()) out.add(ReaderPage(i + 1, slice))
    }
    return out
}

/**
 * Okuyucu liste dizininden (uyarı şeridi dahil ham index) sayfa numarasına
 * çözer. [offset] uyarı şeridi listenin 0. öğesiyse 1, değilse 0 — çağıran
 * `truncated || tablesTruncated`e bakıp hesaplar (PdfViewSync'teki desen).
 */
fun readerListIndexToPage(pages: List<ReaderPage>, offset: Int, index: Int): Int? =
    pages.getOrNull(index - offset)?.number

/**
 * Sayfa numarasından okuyucu liste dizinine (uyarı şeridi dahil) çözer.
 * Sayfa metinsizse atlanmış olabilir (bkz. [splitReaderPages]); bu yüzden
 * "eşit ya da büyük ilk sayfa" aranır, tam eşleşme aranmaz.
 */
fun readerPageToListIndex(pages: List<ReaderPage>, offset: Int, page: Int): Int? {
    val idx = pages.indexOfFirst { it.number >= page }
    return if (idx >= 0) idx + offset else null
}

/** Sayfanın çizim birimleri: düz markdown ya da karta dönüştürülecek geniş tablo. */
sealed interface ReaderBlock {
    data class Prose(val markdown: String) : ReaderBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : ReaderBlock
}

/** Bir markdown satırı GFM tablo ayracı mı? (`| --- | --- |`) */
internal fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    return t.length >= 2 && t.contains('-') && t.contains('|') && t.all { it in "|-: \t" }
}

/**
 * Boru tablosu satırını hücrelere ayırır.
 *
 * Kaçışlı boru (`\|`) hücre içeriğidir, ayraç değil — köprü hücredeki `|`
 * karakterini böyle kaçırıyor. Satır başındaki/sonundaki boru GFM'de sınırdır,
 * boş hücre değil.
 */
internal fun splitTableRow(line: String): List<String> {
    val trimmed = line.trim()
    val out = mutableListOf<String>()
    val cell = StringBuilder()
    var i = 0
    while (i < trimmed.length) {
        val c = trimmed[i]
        when {
            c == '\\' && i + 1 < trimmed.length && trimmed[i + 1] == '|' -> { cell.append('|'); i += 2 }
            c == '|' -> { out.add(cell.toString().trim()); cell.clear(); i += 1 }
            else -> { cell.append(c); i += 1 }
        }
    }
    out.add(cell.toString().trim())
    if (trimmed.startsWith("|") && out.isNotEmpty()) out.removeAt(0)
    if (trimmed.endsWith("|") && !trimmed.endsWith("\\|") && out.isNotEmpty()) out.removeAt(out.size - 1)
    return out
}

/**
 * Sayfayı çizim bloklarına ayırır; yalnız [wideColumns] ve üstü sütunlu tablolar
 * ayrı blok olur.
 *
 * Gerekçe: Markwon dar tabloyu düzgün çiziyor, ama 6 sütunlu bir e-yoklama
 * formunu telefon genişliğine sıkıştırınca hücre başına iki karakter kalıyor.
 * Geniş tablo satır satır karta dönüşür; dar tablo markdown olarak kalır ki
 * ızgara görünümü korunsun.
 */
fun splitReaderBlocks(markdown: String, wideColumns: Int = 4): List<ReaderBlock> {
    val lines = markdown.lines()
    val out = mutableListOf<ReaderBlock>()
    val prose = StringBuilder()
    fun flushProse() {
        val text = prose.toString().trim('\n')
        if (text.isNotBlank()) out.add(ReaderBlock.Prose(text))
        prose.setLength(0)
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val startsTable = line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])
        if (!startsTable) {
            prose.append(line).append('\n')
            i += 1
            continue
        }
        val header = splitTableRow(line)
        val rows = mutableListOf<List<String>>()
        var j = i + 2
        while (j < lines.size && lines[j].contains('|')) {
            rows.add(splitTableRow(lines[j]))
            j += 1
        }
        if (header.size >= wideColumns) {
            flushProse()
            out.add(ReaderBlock.Table(header, rows))
        } else {
            for (k in i until j) prose.append(lines[k]).append('\n')
        }
        i = j
    }
    flushProse()
    return out
}
