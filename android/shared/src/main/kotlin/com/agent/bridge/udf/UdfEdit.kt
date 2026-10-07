package com.agent.bridge.udf

/**
 * Yazdırma düzeninde serbest yazma için satır düzeyi düzenleme (kullanıcı kararı
 * 16.08.2026: düzenleme blok listesinde değil, sayfanın ÜSTÜNDE olacak).
 *
 * Düzenlemenin birimi satırdır — kullanıcının dokunduğu şey o. Yazma gövdenin
 * kendisine ([UdfDocument.body]) uygulanır; gösterim blokları oradan yeniden
 * türetilir. [UdfDocument.text] BİLEREK dokunulmadan bırakılır: tablolar ve
 * tanınmayan öğeler ham XML olarak duruyor ve yazarken kendi dilimlerini özgün
 * CDATA metninden geri okuyorlar (bkz. `UdfParser.reoffsetRawRuns`).
 */
fun UdfDocument.replaceLineText(line: UdfLineRef, newText: String): UdfDocument {
    val node = body.getOrNull(line.bodyIndex) as? UdfParagraphNode ?: return this
    val updated = node.paragraph.replaceLine(line.lineIndex, newText) ?: return this
    return replaceBodyParagraph(line.bodyIndex, updated)
}

/** Seçili satır aralığındaki karakter biçimini açıp kapatır. */
fun UdfDocument.toggleLineStyle(line: UdfLineRef, start: Int, end: Int, style: String): UdfDocument {
    val paragraph = (body.getOrNull(line.bodyIndex) as? UdfParagraphNode)?.paragraph ?: return this
    val target = paragraph.lineRange(line.lineIndex, start, end) ?: return this
    val overlapping = paragraph.elementsInRange(target.first, target.last + 1)
    if (overlapping.isEmpty()) return this
    val enable = when (style) {
        "bold" -> overlapping.any { !it.bold }
        "italic" -> overlapping.any { !it.italic }
        "underline" -> overlapping.any { !it.underline }
        else -> return this
    }
    val updated = paragraph.formatRange(target.first, target.last + 1) { element ->
        when (style) {
            "bold" -> element.copy(bold = enable)
            "italic" -> element.copy(italic = enable)
            "underline" -> element.copy(underline = enable)
            else -> element
        }
    } ?: return this
    return replaceBodyParagraph(line.bodyIndex, updated)
}

/** Seçim varsa seçime, seçim yoksa satırın tamamına punto uygular. */
fun UdfDocument.setLineFontSize(line: UdfLineRef, start: Int, end: Int, size: Int): UdfDocument {
    val paragraph = (body.getOrNull(line.bodyIndex) as? UdfParagraphNode)?.paragraph ?: return this
    val target = paragraph.lineRange(line.lineIndex, start, end) ?: return this
    val safeSize = size.coerceIn(6, 72)
    val updated = paragraph.formatRange(target.first, target.last + 1) { it.copy(size = safeSize) } ?: return this
    return replaceBodyParagraph(line.bodyIndex, updated)
}

/** UDF hizalaması paragraf özelliğidir; dokunulan satırın ait olduğu paragrafı değiştirir. */
fun UdfDocument.setLineAlignment(line: UdfLineRef, alignment: Int): UdfDocument {
    val paragraph = (body.getOrNull(line.bodyIndex) as? UdfParagraphNode)?.paragraph ?: return this
    val safeAlignment = alignment.coerceIn(0, 3)
    if (paragraph.alignment == safeAlignment) return this
    return replaceBodyParagraph(line.bodyIndex, paragraph.copy(alignment = safeAlignment))
}

private fun UdfDocument.replaceBodyParagraph(bodyIndex: Int, updated: UdfParagraph): UdfDocument {
    val current = (body.getOrNull(bodyIndex) as? UdfParagraphNode)?.paragraph ?: return this
    if (current == updated) return this
    val newBody = body.toMutableList().apply { this[bodyIndex] = UdfParagraphNode(updated) }
    val first = blocks.indexOfFirst { it is UdfParagraphBlock && it.line?.bodyIndex == bodyIndex }
    val last = blocks.indexOfLast { it is UdfParagraphBlock && it.line?.bodyIndex == bodyIndex }
    // Satır sayısı değişebilir (yeni metin satır sonu taşıyorsa), o yüzden bu
    // düğümün blokları toptan yenilenir.
    val newBlocks = if (first < 0) blocks
    else blocks.subList(0, first) + updated.splitIntoLineBlocks(bodyIndex) + blocks.subList(last + 1, blocks.size)

    return copy(
        body = newBody,
        paragraphs = newBody.filterIsInstance<UdfParagraphNode>().map { it.paragraph },
        blocks = newBlocks,
    )
}

/**
 * Satır içindeki seçimi paragraf ofsetlerine taşır. İmleç tek noktadaysa bütün
 * satır hedeflenir; böylece mobilde seçim tutamaçlarıyla uğraşmadan B/I/U ve
 * punto uygulanabilir.
 */
private fun UdfParagraph.lineRange(lineIndex: Int, selectionStart: Int, selectionEnd: Int): IntRange? {
    val (lineStart, lineEnd) = splitTextIntoLines(plainText()).getOrNull(lineIndex) ?: return null
    val lineLength = lineEnd - lineStart
    if (lineLength <= 0 || !isRangeEditable(lineStart, lineEnd)) return null
    val low = minOf(selectionStart, selectionEnd).coerceIn(0, lineLength)
    val high = maxOf(selectionStart, selectionEnd).coerceIn(0, lineLength)
    val from = if (low == high) lineStart else lineStart + low
    val to = if (low == high) lineEnd else lineStart + high
    if (from >= to) return null
    return from until to
}

private fun UdfParagraph.elementsInRange(start: Int, end: Int): List<UdfElement> {
    var cursor = 0
    return elements.sortedBy { it.startOffset }.filter { element ->
        val runStart = cursor
        val runEnd = cursor + element.textRun.length
        cursor = runEnd
        runEnd > start && runStart < end && element.kind == UdfElementKind.CONTENT
    }
}

/** Çalıştırmaları hedef aralığın sınırlarından böler, yalnız orta parçayı dönüştürür. */
private fun UdfParagraph.formatRange(
    start: Int,
    end: Int,
    transform: (UdfElement) -> UdfElement,
): UdfParagraph? {
    if (start >= end || !isRangeEditable(start, end)) return null
    val out = ArrayList<UdfElement>(elements.size + 2)
    var cursor = 0
    for (element in elements.sortedBy { it.startOffset }) {
        val runStart = cursor
        val runEnd = cursor + element.textRun.length
        cursor = runEnd
        if (runEnd <= start || runStart >= end) {
            out.add(element)
            continue
        }
        val before = (start - runStart).coerceIn(0, element.textRun.length)
        val after = (end - runStart).coerceIn(0, element.textRun.length)
        if (before > 0) out.add(element.sliceForFormat(0, before))
        if (after > before) out.add(transform(element.sliceForFormat(before, after)))
        if (after < element.textRun.length) out.add(element.sliceForFormat(after, element.textRun.length))
    }
    return copy(elements = renumber(out))
}

private fun UdfElement.sliceForFormat(from: Int, to: Int): UdfElement {
    val text = textRun.substring(from, to)
    // Biçimlenebilir satır yalnız düz content taşır; rawText de aynı dilimdir.
    return copy(textRun = text, rawText = text, length = text.length)
}

/**
 * Paragrafın [lineIndex]. satırını [newText] ile değiştirir; satır kilitliyse
 * (bkz. [isRangeEditable]) null döner.
 *
 * Satırı kuşatan çalıştırmalar aralığın DIŞINDA kalan parçalarıyla korunur;
 * satırı bitiren `\n` aralığın dışındadır, yani yerinde kalır. Yeni metin TEK
 * çalıştırma olur ve biçimini satırın ilk çalıştırmasından alır: satır içi
 * karışık biçim (yarısı kalın bir satır) yeniden yazılınca ilk parçanın biçimine
 * düzleşir. Serbest yazmada kabul edilebilir; korunması gereken satırlar zaten
 * kilitli.
 */
internal fun UdfParagraph.replaceLine(lineIndex: Int, newText: String): UdfParagraph? {
    val (start, end) = splitTextIntoLines(plainText()).getOrNull(lineIndex) ?: return null
    if (!isRangeEditable(start, end)) return null

    val ordered = elements.sortedBy { it.startOffset }
    val styleSource = styleSourceAt(ordered, start, end)
    val out = ArrayList<UdfElement>(ordered.size + 2)
    var cursor = 0
    var inserted = false

    fun insertNew() {
        if (inserted) return
        inserted = true
        if (newText.isEmpty()) return
        out.add(
            styleSource.copy(
                textRun = newText,
                rawText = newText,
                kind = UdfElementKind.CONTENT,
                length = newText.length,
                // Alan öznitelikleri (fieldName vb.) düşer: bu artık düz metin.
                extraAttributes = emptyMap(),
            )
        )
    }

    for (element in ordered) {
        val runStart = cursor
        val runEnd = cursor + element.textRun.length
        cursor = runEnd
        when {
            runEnd <= start -> out.add(element)
            runStart >= end -> {
                insertNew()
                out.add(element)
            }
            else -> {
                if (runStart < start) out.add(element.sliceText(0, start - runStart))
                insertNew()
                if (runEnd > end) out.add(element.sliceText(end - runStart, element.textRun.length))
            }
        }
    }
    insertNew()

    return copy(elements = renumber(out))
}

/** Yeni metnin biçimini alacağı çalıştırma: satırın ilki, yoksa satır sonunu taşıyan. */
private fun styleSourceAt(ordered: List<UdfElement>, start: Int, end: Int): UdfElement {
    var cursor = 0
    var fallback: UdfElement? = null
    for (element in ordered) {
        val runStart = cursor
        val runEnd = cursor + element.textRun.length
        cursor = runEnd
        if (runEnd > start && runStart < end && element.textRun.isNotEmpty()) return element
        if (runStart <= start && runEnd >= start) fallback = element
    }
    return fallback ?: ordered.firstOrNull() ?: UdfElement()
}

/** Düz metin çalıştırmasının bir dilimi; ham dilim de aynı yerden kesilir. */
private fun UdfElement.sliceText(from: Int, to: Int): UdfElement {
    val slice = textRun.substring(from, to)
    return copy(textRun = slice, rawText = slice, length = slice.length)
}

/**
 * `startOffset`'leri metin sırasına göre yeniden numaralar. Değerler artık özgün
 * CDATA konumları DEĞİLDİR — yazarken hepsi baştan hesaplanıyor
 * (`UdfParser.buildContentXml`), burada tek işlevleri çizim tarafındaki sıralamayı
 * doğru tutmak.
 */
private fun renumber(elements: List<UdfElement>): List<UdfElement> {
    var offset = 0
    return elements.map { element ->
        val next = element.copy(startOffset = offset)
        offset += element.textRun.length
        next
    }
}
