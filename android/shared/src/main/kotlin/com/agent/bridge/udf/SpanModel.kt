package com.agent.bridge.udf

/**
 * Saf (Android/Compose bağımlılığı olmayan) span mutasyon algoritmaları.
 *
 * Tüm fonksiyonlar **immutable** liste alır, yeni liste döner. EditorViewModel
 * bu fonksiyonları çağırıp sonucu kendi mutable alanlarına geri yazar.
 *
 * Ofset kuralları (FormatSpan/AlignmentSpan için ortak):
 *  - [start, end) yarı-açık aralık
 *  - Ekleme/silme: cursorPos sonrasındaki span'ler delta kadar kayar;
 *    cursorPos bir span ortasındaysa yalnızca end uzar.
 */
object SpanModel {

    /**
     * Metin ekleme/silme sonrası [FormatSpan] ofsetlerini kaydırır.
     * [changePos]: değişikliğin başladığı ofset; [delta]: eklenen/çıkaran
     * karakter sayısı (pozitif=ekleme, negatif=silme).
     *
     * Not: collapsed (start==end) span'ler düşürülür (start < end filtresi).
     */
    fun shiftOnTextChange(spans: List<FormatSpan>, changePos: Int, delta: Int): List<FormatSpan> {
        if (delta == 0) return spans
        return spans.map { span ->
            val start = span.start
            val end = span.end
            when {
                start >= changePos ->
                    span.copy(start = start + delta, end = end + delta)
                changePos > start && changePos < end ->
                    span.copy(end = end + delta)
                else -> span
            }
        }.filter { it.start < it.end && it.start >= 0 }
    }

    /**
     * Metin ekleme/silme sonrası [AlignmentSpan] ofsetlerini kaydırır.
     *
     * FormatSpan'dan tek farkı: cursorPos span'in tam end noktasındaysa end
     * yine uzar (changePos <= end testi). Bu, hizalama span'lerinin satır
     * sonundaki '\n' ile birlikte taşınmasını sağlar. Boş (start==end)
     * span'ler korunabilir.
     */
    fun shiftAlignmentsOnTextChange(aligns: List<AlignmentSpan>, changePos: Int, delta: Int): List<AlignmentSpan> {
        if (delta == 0) return aligns
        return aligns.map { span ->
            val start = span.start
            val end = span.end
            when {
                start >= changePos ->
                    span.copy(start = start + delta, end = end + delta)
                changePos > start && changePos <= end ->
                    span.copy(end = end + delta)
                else -> span
            }
        }.filter { it.start <= it.end && it.start >= 0 }
    }

    /**
     * [newSpan]'i listeye ekler; çakışan mevcut span'leri 5 durumda işler:
     *  1. tamamen yeni span içinde → düşür
     *  2. tamamen yeni span dışında, onu sarıyor → iki parçaya böl
     *  3. sol kenardan çakışıyor → end'i yeni.start'e kırp
     *  4. sağ kenardan çakışıyor → start'ı yeni.end'e kırp
     *  5. ayrık → olduğu gibi bırak
     * Sonra [mergeAdjacent] ile bitişik aynı-öznitelik span'leri birleştirir.
     */
    fun insertAndMerge(spans: List<FormatSpan>, newSpan: FormatSpan): List<FormatSpan> {
        val start = newSpan.start
        val end = newSpan.end
        val processed = mutableListOf<FormatSpan>()
        for (span in spans) {
            when {
                span.start >= start && span.end <= end -> {
                    // tamamen yeni span içinde: düşür
                }
                span.start < start && span.end > end -> {
                    // yeni span'ı sarıyor: iki parçaya böl
                    processed.add(span.copy(end = start))
                    processed.add(span.copy(start = end))
                }
                span.start < start && span.end > start && span.end <= end -> {
                    // sol kenardan çakışıyor
                    processed.add(span.copy(end = start))
                }
                span.start >= start && span.start < end && span.end > end -> {
                    // sağ kenardan çakışıyor
                    processed.add(span.copy(start = end))
                }
                else -> {
                    // ayrık
                    processed.add(span)
                }
            }
        }
        processed.add(newSpan)
        return mergeAdjacent(processed)
    }

    /**
     * Sıralar ve bitişik (current.end >= next.start) aynı özniteliğe sahip
     * span'leri tek span'da birleştirir. collapsed (start==end) span'leri düşürür.
     */
    fun mergeAdjacent(spans: List<FormatSpan>): List<FormatSpan> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedBy { it.start }
        val merged = mutableListOf<FormatSpan>()
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (current.bold == next.bold &&
                current.italic == next.italic &&
                current.underline == next.underline &&
                current.strikethrough == next.strikethrough &&
                current.subscript == next.subscript &&
                current.superscript == next.superscript &&
                current.size == next.size &&
                current.family == next.family &&
                current.end >= next.start
            ) {
                current = current.copy(end = maxOf(current.end, next.end))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged.filter { it.start < it.end }
    }

    /**
     * Seçim **collapsed** iken cursor'ın hemen solundaki aktif span'ı döner.
     * Test edilen koşul `pos > it.start && pos <= it.end` (strict start,
     * inclusive end): cursor bir span'in başında değil ama içinde veya
     * tam sonundaysa o span geçerli sayılır. Bulunamazsa [default] döner.
     */
    fun spanAt(spans: List<FormatSpan>, pos: Int, default: FormatSpan): FormatSpan {
        return spans.lastOrNull { pos > it.start && pos <= it.end } ?: default
    }

    /**
     * [start, end) aralığındaki her karakterin, [predicate]'ı sağlayan bir
     * span tarafından kapsanıp kapsanmadığını söyler. Boş seçimde (start>=end)
     * [default] döner.
     */
    fun isRangeFormattingActive(
        spans: List<FormatSpan>,
        start: Int,
        end: Int,
        predicate: (FormatSpan) -> Boolean,
        default: Boolean
    ): Boolean {
        if (start >= end) return default
        for (i in start until end) {
            val hasActiveSpan = spans.any { it.start <= i && it.end > i && predicate(it) }
            if (!hasActiveSpan) return false
        }
        return true
    }

    /**
     * [start] ofsetini kapsayan (start <= it.start && it.end > start) ilk
     * span'ın font boyutu; yoksa null.
     */
    fun commonSize(spans: List<FormatSpan>, start: Int, end: Int): Int? {
        return spans.firstOrNull { it.start <= start && it.end > start }?.size
    }

    /**
     * [start] ofsetini kapsayan ilk span'ın font ailesi; yoksa null.
     */
    fun commonFamily(spans: List<FormatSpan>, start: Int, end: Int): String? {
        return spans.firstOrNull { it.start <= start && it.end > start }?.family
    }

    /**
     * [pos] ofsetini **strict** kapsayan (pos > start && pos < end) ilk span'ı
     * döner; yoksa [default]. Bu "doğal biçim" sorgusudur: cursor bir span'in
     * içindeyse (kenarları hariç) onun biçimi yazılan karakterlere uygulanır.
     */
    fun naturallyAppliedStyle(spans: List<FormatSpan>, pos: Int, default: FormatSpan): FormatSpan {
        return spans.firstOrNull { pos > it.start && pos < it.end } ?: default
    }
}
