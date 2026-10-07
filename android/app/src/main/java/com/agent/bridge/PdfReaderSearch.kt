package com.agent.bridge

import java.util.Locale

// Türkçe küçük harfe çevirme İngilizce kuraldan FARKLI: 'İ' (nokta) -> 'i',
// 'I' (noktasız) -> 'ı'. Locale VERMEDEN lowercase() kullanmak "İCRA"
// aramasını "icra" içeren sayfada KAÇIRIR (İ tek Türkçe 'i'ye değil, iki
// karakterlik "i" + birleşen nokta imine döner) — bu yüzden sabit locale.
private val TR_LOCALE = Locale("tr", "TR")

/** Tek bir arama eşleşmesi: bulunduğu sayfa + eşleşmenin bağlamı. */
data class ReaderSearchHit(val page: Int, val snippet: String)

/** Arama sonuçlarının sarmalayıcısı; [limit] aşılırsa [truncated] true olur. */
data class ReaderSearchResult(val hits: List<ReaderSearchHit>, val truncated: Boolean)

/**
 * Okuma modu sayfalarında Türkçe duyarlı küçük harf araması yapar.
 *
 * Sayfa başına birden çok eşleşme dönebilir. [limit]'e ulaşınca arama durur
 * ve [ReaderSearchResult.truncated] true olur — kullanıcıya "daha fazla eşleşme
 * var ama gösterilmiyor" bilgisi sessizce kaybolmasın diye.
 */
fun searchReaderPages(pages: List<ReaderPage>, query: String, limit: Int = 200): ReaderSearchResult {
    val needle = query.trim().lowercase(TR_LOCALE)
    if (needle.isEmpty()) return ReaderSearchResult(emptyList(), false)
    val hits = mutableListOf<ReaderSearchHit>()
    var truncated = false
    outer@ for (page in pages) {
        val haystack = page.markdown.lowercase(TR_LOCALE)
        var from = 0
        while (true) {
            val idx = haystack.indexOf(needle, from)
            if (idx < 0) break
            if (hits.size >= limit) {
                truncated = true
                break@outer
            }
            hits.add(ReaderSearchHit(page.number, readerSearchSnippet(page.markdown, idx, needle.length)))
            from = idx + needle.length
        }
    }
    return ReaderSearchResult(hits, truncated)
}

/**
 * Eşleşmenin ±[radius] karakterlik bağlamı. Markdown işaretleri (`**`, `#`,
 * `|`, ``` ` ```, `_`) temizlenir — sonuç listesinde ham markdown çirkin
 * duruyordu. Kelime ortasından kesmekten kaçınmaya ÇALIŞILMAZ (spesifikasyon):
 * sabit karakter yarıçapı basit ve öngörülebilir, kelime sınırı arayışı uzun
 * satırlarda snippet'i belirsiz uzunlukta yapardı.
 */
internal fun readerSearchSnippet(text: String, matchIndex: Int, matchLength: Int, radius: Int = 40): String {
    val start = (matchIndex - radius).coerceAtLeast(0)
    val end = (matchIndex + matchLength + radius).coerceAtMost(text.length)
    val cleaned = text.substring(start, end)
        .replace(Regex("[*#|_`]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return "$prefix$cleaned$suffix"
}
