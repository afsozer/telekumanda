package com.agent.bridge.udf

/**
 * Saf (Android/Compose bağımlılığı olmayan) satır-bazlı metin dönüşümleri.
 *
 * `applyLinePrefixTransform` ortak offset-remap çekirdeğidir; numaralı liste,
 * girinti ve benzeri dönüşümler onun üzerine inşa edilir. Her dönüşüm
 * metni, span'ları, hizalamaları ve yeni seçimi döner — EditorViewModel
 * sonucu kendi alanlarına geri yazar.
 */
object ParagraphTransforms {

    /**
     * Her satır için önek ekleme/kaldırma dönüşümü.
     *
     * @param text mevcut tam metin ('\n' ile sınırlandırılmış satırlar)
     * @param selStart seçim başlangıcı (text-relative)
     * @param selEnd seçim sonu (text-relative)
     * @param perLine her satır için çağrılır; döndürdüğü string önek olarak
     *        o satırın başına eklenir. Boş string = önek yok. Satır zaten önek
     *        içeriyorsa ve kaldırılması gerekiyorsa, callback satırı işleyip
     *        **yeni satır gövdesini** döndürmelidir — bu durumda callback dönüş
     *        değerinin yorumu [LinePrefixMode] ile ayrılır.
     *
     * Daha sade tutmak için callback iki moddan birini [LinePrefixMode] ile
     * bildirir: [LinePrefixMode.PREPEND] (gövde sabit, önek ekle) veya
     * [LinePrefixMode.REPLACE] (tüm satırı değiştir, önek kaldırılmış).
     */
    fun applyLinePrefixTransform(
        text: String,
        selStart: Int,
        selEnd: Int,
        perLine: (lineIndex: Int, line: String, lineStart: Int, lineEnd: Int, isAffected: Boolean) -> LineEdit
    ): LineTransformResult {
        val lines = text.split("\n")
        val lineStarts = IntArray(lines.size)
        var offset = 0
        for (i in lines.indices) {
            lineStarts[i] = offset
            offset += lines[i].length + 1
        }

        val sb = StringBuilder()
        val oldToNew = IntArray(text.length + 1)
        var oldIdx = 0
        var newIdx = 0

        for (i in lines.indices) {
            val line = lines[i]
            val lineStart = lineStarts[i]
            val lineEnd = lineStart + line.length
            val isAffected = lineStart <= selEnd && lineEnd >= selStart

            val edit = perLine(i, line, lineStart, lineEnd, isAffected)
            if (isAffected) {
                when (edit.mode) {
                    LinePrefixMode.PREPEND -> {
                        // Önce öneki ekle (eski karakter harcamadan)
                        if (edit.prefix.isNotEmpty()) {
                            sb.append(edit.prefix)
                            newIdx += edit.prefix.length
                        }
                        // İlk eski karakter (satır başı) yeni konuma haritalansın
                        if (line.isNotEmpty()) {
                            oldToNew[oldIdx] = newIdx
                        }
                    }
                    LinePrefixMode.REPLACE -> {
                        // Tüm eski satırı (kaldırılacak önek + gövde dahil) yeni
                        // gövdenin başlangıç konumuna harcalarız. Eski satırın son
                        // karakteri gövde sonunu, sonra gelen '\n' ayrı bir adımda
                        // işlenir.
                        for (k in line.indices) {
                            oldToNew[oldIdx] = newIdx
                            oldIdx++
                        }
                        sb.append(edit.replacementBody ?: "")
                        newIdx += (edit.replacementBody ?: "").length
                        // Gövde zaten yazıldı; satırın karakter döngüsünü atla,
                        // newline'ı normal akışta ekle.
                        if (oldIdx < text.length) {
                            oldToNew[oldIdx] = newIdx
                            sb.append('\n')
                            oldIdx++; newIdx++
                        }
                        continue
                    }
                }
            }

            for (k in line.indices) {
                oldToNew[oldIdx] = newIdx
                sb.append(line[k])
                oldIdx++
                newIdx++
            }
            if (oldIdx < text.length) {
                oldToNew[oldIdx] = newIdx
                sb.append('\n')
                oldIdx++
                newIdx++
            }
        }
        oldToNew[text.length] = newIdx

        val newText = sb.toString()
        val newSelStart = oldToNew[selStart.coerceIn(0, text.length)]
        val newSelEnd = oldToNew[selEnd.coerceIn(0, text.length)]

        return LineTransformResult(
            text = newText,
            oldToNew = oldToNew,
            textLength = text.length,
            newSelectionStart = newSelStart,
            newSelectionEnd = newSelEnd
        )
    }

    /** applyLinePrefixTransform sonucu. */
    data class LineTransformResult(
        val text: String,
        val oldToNew: IntArray,
        val textLength: Int,
        val newSelectionStart: Int,
        val newSelectionEnd: Int
    ) {
        /** Bir eski ofseti yeni metindeki konumuna harcalar. */
        fun mapOffset(oldOffset: Int): Int {
            return oldToNew[oldOffset.coerceIn(0, textLength)]
        }

        /** Span listesini yeni ofsetlere yeniden harcalar; collapsed olanları düşürür. */
        fun remapSpans(spans: List<FormatSpan>): List<FormatSpan> {
            return spans.map { it.copy(start = mapOffset(it.start), end = mapOffset(it.end)) }
                .filter { it.start < it.end }
        }

        /** Hizalama listesini yeniden harcalar; boş olabilecek span'ler korunur. */
        fun remapAlignments(aligns: List<AlignmentSpan>): List<AlignmentSpan> {
            return aligns.map { it.copy(start = mapOffset(it.start), end = mapOffset(it.end)) }
                .filter { it.start <= it.end }
        }
    }

    /** Callback dönüşü: bir satır için ne yapılacağı. */
    data class LineEdit(
        val mode: LinePrefixMode,
        val prefix: String = "",
        val replacementBody: String? = null
    )

    enum class LinePrefixMode { PREPEND, REPLACE }

    /**
     * Numaralı liste önekini ("1. ", "2. ", ...) seçimle çakışan satırlarda
     * açar/kapatır. Mevcut liste ise kaldırır, değilse ekler.
     */
    fun toggleNumberedList(
        text: String,
        selStart: Int,
        selEnd: Int,
        spans: List<FormatSpan>,
        alignments: List<AlignmentSpan>
    ): TransformResult {
        val lines = text.split("\n")
        val lineStarts = IntArray(lines.size)
        var off = 0
        for (i in lines.indices) {
            lineStarts[i] = off
            off += lines[i].length + 1
        }
        val affected = mutableListOf<Int>()
        for (i in lines.indices) {
            val ls = lineStarts[i]
            val le = ls + lines[i].length
            if (ls <= selEnd && le >= selStart) affected.add(i)
        }
        if (affected.isEmpty()) {
            return TransformResult(text, spans, alignments, selStart, selEnd)
        }

        val listRegex = Regex("""^(\d+)\.\s+""")
        val firstLine = lines.getOrNull(affected.first()) ?: ""
        val isList = listRegex.find(firstLine) != null

        // Eski implementasyonla uyumlu: numaralandırma her zaman 1'den başlar
        // (etkilenen ilk satırdan bağımsız olarak).
        var listNumber = 1
        val result = applyLinePrefixTransform(text, selStart, selEnd) { idx, line, _, _, isAffected ->
            if (!isAffected || idx !in affected) {
                LineEdit(LinePrefixMode.PREPEND)
            } else if (isList) {
                val m = listRegex.find(line)
                if (m != null) LineEdit(LinePrefixMode.REPLACE, replacementBody = line.substring(m.value.length))
                else LineEdit(LinePrefixMode.PREPEND)
            } else {
                val prefix = "$listNumber. "
                listNumber++
                LineEdit(LinePrefixMode.PREPEND, prefix = prefix)
            }
        }

        return TransformResult(
            text = result.text,
            spans = result.remapSpans(spans),
            alignments = result.remapAlignments(alignments),
            newSelectionStart = result.newSelectionStart,
            newSelectionEnd = result.newSelectionEnd
        )
    }

    /**
     * Seçimle çakışan her satırın başına 4 boşlukluk girinti ekler.
     */
    fun insertIndent(
        text: String,
        selStart: Int,
        selEnd: Int,
        spans: List<FormatSpan>,
        alignments: List<AlignmentSpan>
    ): TransformResult {
        val result = applyLinePrefixTransform(text, selStart, selEnd) { _, _, _, _, isAffected ->
            if (isAffected) LineEdit(LinePrefixMode.PREPEND, prefix = "    ")
            else LineEdit(LinePrefixMode.PREPEND)
        }
        return TransformResult(
            text = result.text,
            spans = result.remapSpans(spans),
            alignments = result.remapAlignments(alignments),
            newSelectionStart = result.newSelectionStart,
            newSelectionEnd = result.newSelectionEnd
        )
    }

    /**
     * Seçili metne (collapsed ise tüm belgeye) BÜYÜK/küçük/Başlık dönüşümü uygular.
     *
     * Önceki implementasyondan farkı: dönüşüm **uzunluk değiştirse bile** span
     * ve hizalama ofsetlerini doğru şekilde remap eder. Örn. "ß".uppercase() → "SS"
     * (1→2 karakter) veya bazı yerel ayarlarda Title dönüşümü uzunluk değiştirebilir.
     */
    fun applyTextTransform(
        text: String,
        selStart: Int,
        selEnd: Int,
        mode: TextTransformMode,
        spans: List<FormatSpan>,
        alignments: List<AlignmentSpan>
    ): TransformResult {
        val start = if (selStart >= selEnd) 0 else selStart.coerceIn(0, text.length)
        val end = if (selStart >= selEnd) text.length else selEnd.coerceIn(0, text.length)
        if (start >= end) {
            return TransformResult(text, spans, alignments, selStart, selEnd)
        }

        val locale = java.util.Locale.getDefault()
        val target = text.substring(start, end)
        val transformed = when (mode) {
            TextTransformMode.UPPER -> target.uppercase(locale)
            TextTransformMode.LOWER -> target.lowercase(locale)
            TextTransformMode.TITLE -> target.split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            }
        }

        val prefix = text.substring(0, start)
        val suffix = text.substring(end)
        val newText = prefix + transformed + suffix

        // oldToNew haritası: [0, start) → identity, [start, end) → transformed içindeki
        // konuma (karakter karakter değil "blok" olarak taşırız, çünkü tek tek karakter
        // eşlemesi uzunluk değişiminde anlamsızdır), [end, len] → delta kaydırılmış.
        val oldLen = text.length
        val newLen = newText.length
        val oldToNew = IntArray(oldLen + 1)
        for (i in 0..start) oldToNew[i] = i
        val delta = transformed.length - (end - start)
        for (i in end..oldLen) oldToNew[i] = i + delta
        // [start+1, end) aralığını transformed blok sonuna (start + transformed.length) sabitle:
        // bu sayede seçim içindeki span'lar ya tamamen seçim öncesi (start) ya da sonrasına
        // (start+transformed.length) çöker — en tutarlı davranış.
        for (i in (start + 1) until end) oldToNew[i] = start + transformed.length

        val remap: (Int) -> Int = { old -> oldToNew[old.coerceIn(0, oldLen)] }
        val newSpans = spans.map { it.copy(start = remap(it.start), end = remap(it.end)) }
            .filter { it.start < it.end }
        val newAligns = alignments.map { it.copy(start = remap(it.start), end = remap(it.end)) }
            .filter { it.start <= it.end }

        val newSelStart = start
        val newSelEnd = start + transformed.length
        // newLen/oldLen kullanılmıyor ama tutarlılık için saklı
        @Suppress("UNUSED_VARIABLE") val _lenRef = newLen

        return TransformResult(newText, newSpans, newAligns, newSelStart, newSelEnd)
    }

    /** Tüm dönüşümlerin ortak dönüş tipi. */
    data class TransformResult(
        val text: String,
        val spans: List<FormatSpan>,
        val alignments: List<AlignmentSpan>,
        val newSelectionStart: Int,
        val newSelectionEnd: Int
    )
}
