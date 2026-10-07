package com.agent.bridge.udf

/**
 * Paragrafı sert satırlarına (`\n`) böler: her satır ayrı bir çizim ve sayfalama
 * birimi olur.
 *
 * NEDEN: UDF'te satır sonu paragrafı bitirmek zorunda değil. Ölçülen gerçek
 * dilekçelerde belgenin TAMAMI tek `<paragraph>` içinde duruyor, satır sonları
 * CDATA gövdesinde `\n` olarak geçiyor. Bölmezsek sayfa başına dilimleme
 * (16.08.2026) böyle bir belgede hiçbir şey kazandırmaz — tek blok bütün
 * sayfaların aralığına girdiği için her yaprağa yeniden diziliyor. Aynı bölme
 * dokun-düzenle için de doğru hedef birimi üretir: kullanıcı bir satıra dokunur.
 *
 * Yerleşim DEĞİŞMEZ. `\n` zaten yerleşim motoru için de paragraf sınırıdır:
 * hizalama iki yana yaslıyken satır sonundan önceki satır yaslanmaz, ilk satır
 * girintisi her `\n`'den sonra yeniden uygulanır. Bölmek bunu taklit etmez,
 * aynısını yapar.
 *
 * Çıktı yalnız GÖSTERİM içindir — [UdfDocument.body] gibi yazma yolları buna
 * bakmaz. Bölünen çalıştırmalarda `startOffset`/`length` satır içinde yeniden
 * numaralanır ve [UdfElement.rawText] düşer; ikisi de yalnız CDATA'ya yazarken
 * anlamlıdır.
 */
internal fun UdfParagraph.splitIntoLines(): List<UdfParagraph> {
    if (elements.none { it.textRun.contains('\n') }) return listOf(this)

    val lines = mutableListOf(mutableListOf<UdfElement>())
    var offset = 0
    for (element in elements.sortedBy { it.startOffset }) {
        val text = element.textRun
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            if (end > start) {
                val slice = text.substring(start, end)
                lines.last().add(
                    element.copy(
                        textRun = slice,
                        rawText = "",
                        startOffset = offset,
                        length = slice.length,
                    )
                )
                offset += slice.length
            }
            if (newline < 0) break
            lines.add(mutableListOf())
            offset = 0
            start = newline + 1
        }
    }
    // Sondaki satır sonu paragrafın KENDİ sonlandırıcısıdır, ardından boş satır
    // getirmez — [splitTextIntoLines] ile aynı kural.
    if (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
    return lines.map { copy(elements = it) }
}

/**
 * [splitIntoLines]'ı çizim bloğu olarak paketler ve her satıra gövdedeki
 * adresini ([UdfLineRef]) ve düzenlenebilirliğini iliştirir.
 *
 * @param bodyIndex paragrafın [UdfDocument.body] içindeki sırası.
 */
internal fun UdfParagraph.splitIntoLineBlocks(bodyIndex: Int): List<UdfBlock> {
    val ranges = splitTextIntoLines(plainText())
    return splitIntoLines().mapIndexed { index, line ->
        val range = ranges.getOrNull(index)
        UdfParagraphBlock(
            paragraph = line,
            line = UdfLineRef(bodyIndex, index),
            editable = range != null && isRangeEditable(range.first, range.second),
        )
    }
}

/** Çalıştırmaların çözülmüş metinlerinin birleşimi — ekranda görünen paragraf. */
fun UdfParagraph.plainText(): String =
    elements.sortedBy { it.startOffset }.joinToString("") { it.textRun }

/**
 * Metni satırlara böler; `[başlangıç, bitiş)` çiftleri döner. Sondaki satır sonu
 * paragrafın sonlandırıcısıdır, ARDINDAN boş satır üretmez; ortadaki boş satırlar
 * (`"a\n\nb"`) korunur.
 */
internal fun splitTextIntoLines(text: String): List<Pair<Int, Int>> {
    val out = mutableListOf<Pair<Int, Int>>()
    var start = 0
    while (true) {
        val newline = text.indexOf('\n', start)
        if (newline < 0) {
            out.add(start to text.length)
            break
        }
        out.add(start to newline)
        start = newline + 1
        if (start == text.length) break
    }
    return out
}

/**
 * `[start, end)` aralığı serbestçe yeniden yazılabilir mi?
 *
 * Aralığa değen her çalıştırma düz `content` OLMALI ve ham dilimi görünen
 * metniyle aynı kalmalı. Değilse satır kilitlidir:
 * - `field`: metni değiştirmek şablon alan bağını koparır, `<data>` bölümüyle
 *   tutarsız bir belge bırakır;
 * - `tab`: ham dilim bir `\t` karakteri, görünen hizalama ondan geliyor;
 * - koşullu bölge harcı (`fieldGroupName` taşıyan content): görünen metni
 *   boşaltılmış olabilir, ham dilim hâlâ dolu — dilimlemek metni geri getirirdi.
 *
 * Boş satırda aralık sıfır uzunluktadır; o zaman ölçüt satır sonunu TAŞIYAN
 * çalıştırmadır, çünkü yazma onu ikiye böler.
 */
internal fun UdfParagraph.isRangeEditable(start: Int, end: Int): Boolean {
    var cursor = 0
    for (element in elements.sortedBy { it.startOffset }) {
        val runStart = cursor
        val runEnd = cursor + element.textRun.length
        cursor = runEnd
        if (runEnd <= start || runStart >= end) continue
        if (element.kind != UdfElementKind.CONTENT) return false
        if (element.rawText != element.textRun) return false
    }
    return true
}
