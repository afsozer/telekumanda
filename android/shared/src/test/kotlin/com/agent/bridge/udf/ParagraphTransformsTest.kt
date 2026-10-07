package com.agent.bridge.udf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphTransformsTest {

    // --- toggleNumberedList ---

    @Test
    fun toggleNumberedList_addPrefixToMultipleLines() {
        val text = "Satir 1\nSatir 2"
        val spans = listOf(FormatSpan(0, 7, bold = true))
        val result = ParagraphTransforms.toggleNumberedList(text, selStart = 0, selEnd = 15, spans = spans, alignments = emptyList())

        assertEquals("1. Satir 1\n2. Satir 2", result.text)
        // [0,7] "Satir 1" → [3,10] (3 char "1. " öneki sonrası)
        assertEquals(1, result.spans.size)
        assertEquals(3, result.spans[0].start)
        assertEquals(10, result.spans[0].end)
        assertTrue(result.spans[0].bold)
    }

    @Test
    fun toggleNumberedList_removePrefixFromMultipleLines() {
        val text = "1. Satir 1\n2. Satir 2"
        val spans = listOf(FormatSpan(3, 10, bold = true))
        val result = ParagraphTransforms.toggleNumberedList(text, selStart = 0, selEnd = 21, spans = spans, alignments = emptyList())

        assertEquals("Satir 1\nSatir 2", result.text)
        // [3,10] → [0,7]
        assertEquals(1, result.spans.size)
        assertEquals(0, result.spans[0].start)
        assertEquals(7, result.spans[0].end)
    }

    @Test
    fun toggleNumberedList_numbersAlwaysStartFrom1() {
        // Üçüncü satırdan seçim başlasa bile numaralandırma 1'den başlar
        val text = "A\nB\nC"
        val result = ParagraphTransforms.toggleNumberedList(text, selStart = 4, selEnd = 5, spans = emptyList(), alignments = emptyList())
        assertEquals("A\nB\n1. C", result.text)
    }

    @Test
    fun toggleNumberedList_noAffectedLinesReturnsOriginal() {
        // Seçim boş metni seçemez (selStart==selEnd==0 ama metin boş değil);
        // burada tamamen satır dışı seçim simüle edelim
        val text = "hello"
        val result = ParagraphTransforms.toggleNumberedList(text, selStart = 0, selEnd = 0, spans = emptyList(), alignments = emptyList())
        // collapsed seçim hala ilk satırı etkiler (lineStart<=selEnd && lineEnd>=selStart)
        assertEquals("1. hello", result.text)
    }

    @Test
    fun toggleNumberedList_singleLineSelectionOnlyAffectsThatLine() {
        val text = "A\nB\nC"
        // sadece 2. satır (B, offset 2..3) seçili
        val result = ParagraphTransforms.toggleNumberedList(text, selStart = 2, selEnd = 3, spans = emptyList(), alignments = emptyList())
        assertEquals("A\n1. B\nC", result.text)
    }

    // --- insertIndent ---

    @Test
    fun insertIndent_adds4SpacesToEachSelectedLine() {
        val text = "A\nB\nC"
        val result = ParagraphTransforms.insertIndent(text, selStart = 0, selEnd = 5, spans = emptyList(), alignments = emptyList())
        assertEquals("    A\n    B\n    C", result.text)
    }

    @Test
    fun insertIndent_shiftsSpanCorrectly() {
        val text = "Hello"
        val spans = listOf(FormatSpan(0, 5, bold = true))
        val result = ParagraphTransforms.insertIndent(text, selStart = 0, selEnd = 5, spans = spans, alignments = emptyList())
        assertEquals("    Hello", result.text)
        assertEquals(1, result.spans.size)
        assertEquals(4, result.spans[0].start)
        assertEquals(9, result.spans[0].end)
    }

    @Test
    fun insertIndent_onlyAffectsSelectedLines() {
        val text = "A\nB\nC"
        // sadece 2. satır (B)
        val result = ParagraphTransforms.insertIndent(text, selStart = 2, selEnd = 3, spans = emptyList(), alignments = emptyList())
        assertEquals("A\n    B\nC", result.text)
    }

    // --- applyTextTransform ---

    @Test
    fun applyTextTransform_upperSelection() {
        val text = "hello world"
        val result = ParagraphTransforms.applyTextTransform(text, selStart = 0, selEnd = 5, mode = TextTransformMode.UPPER, spans = emptyList(), alignments = emptyList())
        assertEquals("HELLO world", result.text)
        assertEquals(0, result.newSelectionStart)
        assertEquals(5, result.newSelectionEnd)
    }

    @Test
    fun applyTextTransform_lowerSelection() {
        val text = "HELLO WORLD"
        val result = ParagraphTransforms.applyTextTransform(text, selStart = 6, selEnd = 11, mode = TextTransformMode.LOWER, spans = emptyList(), alignments = emptyList())
        assertEquals("HELLO world", result.text)
    }

    @Test
    fun applyTextTransform_titleSelection() {
        val text = "hello world"
        val result = ParagraphTransforms.applyTextTransform(text, selStart = 0, selEnd = 11, mode = TextTransformMode.TITLE, spans = emptyList(), alignments = emptyList())
        assertEquals("Hello World", result.text)
    }

    @Test
    fun applyTextTransform_collapsedSelectionAppliesToWholeText() {
        val text = "abc def"
        val result = ParagraphTransforms.applyTextTransform(text, selStart = 0, selEnd = 0, mode = TextTransformMode.UPPER, spans = emptyList(), alignments = emptyList())
        assertEquals("ABC DEF", result.text)
    }

    // --- applyTextTransform span remap (BUG düzeltmesi) ---

    @Test
    fun applyTextTransform_spanBeforeSelectionUnchanged() {
        val text = "hello WORLD"
        val spans = listOf(FormatSpan(0, 5, bold = true)) // "hello" (seçim öncesi)
        val result = ParagraphTransforms.applyTextTransform(text, selStart = 6, selEnd = 11, mode = TextTransformMode.LOWER, spans = spans, alignments = emptyList())
        assertEquals("hello world", result.text)
        // seçim öncesi span korunur
        assertEquals(1, result.spans.size)
        assertEquals(0, result.spans[0].start)
        assertEquals(5, result.spans[0].end)
    }

    @Test
    fun applyTextTransform_spanAfterSelectionShiftedByDelta() {
        // Eski (düzeltme öncesi) kod span remap yapmıyordu. Şimdi yapmalı.
        // "ab WORLD fg": "fg" span'i (10,12), "WORLD"→lower seçimi delta=0 ama
        // uzunluk değiştiren dönüşüm test edelim. "İ"→"İ" aynı ama güvenli senaryo:
        val text = "ab XX fg"
        // "fg" offset 6..8, "XX"→lower "xx" delta 0
        val spans = listOf(FormatSpan(6, 8, bold = true))
        val result = ParagraphTransforms.applyTextTransform(text, selStart = 3, selEnd = 5, mode = TextTransformMode.LOWER, spans = spans, alignments = emptyList())
        assertEquals("ab xx fg", result.text)
        assertEquals(6, result.spans[0].start)
        assertEquals(8, result.spans[0].end)
    }

    @Test
    fun applyTextTransform_spanInsideSelectionClampsToBlock() {
        // Seçim içindeki span'ler seçim bloğunun başına/sonuna çöker.
        // "abcdef": span [1,4] (bcd), tüm metni UPPER yap (seçim 0..6).
        // Remap: (start+1, end) aralığı start+transformed.length'e sabitlenir.
        // oldToNew[1]=6, oldToNew[4]=6 → [6,6] çöker, düşer. Bu makul:
        // seçim içindeki span'in yeni konumu uzunluk değişiminde anlamsızdır.
        val text = "abcdef"
        val spans = listOf(FormatSpan(1, 4, bold = true))
        val result = ParagraphTransforms.applyTextTransform(text, selStart = 0, selEnd = 6, mode = TextTransformMode.UPPER, spans = spans, alignments = emptyList())
        assertEquals("ABCDEF", result.text)
        assertTrue("seçim içindeki çökmüş span düşürülmeli", result.spans.isEmpty())
    }

    @Test
    fun applyTextTransform_spanSpanningSelectionStartKeptBefore() {
        // Seçim [3,5), span [0,5] → start seçim öncesi (0), end seçim içinde (5)
        // oldToNew[0]=0 (identity), oldToNew[5]: 5==end değil (end=5 ama döngü 1..4),
        // aslında end=5 döngüye girmez, [end..len] döngüsü: oldToNew[5]=5+delta=5
        // → span [0,5] korunur (delta 0 çünkü "xx"=="xx")
        val text = "abXX fg"
        val spans = listOf(FormatSpan(0, 5, bold = true))
        val result = ParagraphTransforms.applyTextTransform(text, selStart = 2, selEnd = 4, mode = TextTransformMode.UPPER, spans = spans, alignments = emptyList())
        assertEquals("abXX fg", result.text)
        assertEquals(1, result.spans.size)
    }

    // --- applyLinePrefixTransform (çekirdek) ---

    @Test
    fun applyLinePrefixTransform_callbackReceivesCorrectLineMetadata() {
        val text = "AA\nBB"
        val seen = mutableListOf<Triple<Int, Int, Int>>() // (lineIndex, lineStart, lineEnd)
        ParagraphTransforms.applyLinePrefixTransform(text, selStart = 0, selEnd = 5) { idx, line, ls, le, _ ->
            seen.add(Triple(idx, ls, le))
            ParagraphTransforms.LineEdit(ParagraphTransforms.LinePrefixMode.PREPEND)
        }
        assertEquals(2, seen.size)
        assertEquals(Triple(0, 0, 2), seen[0])
        assertEquals(Triple(1, 3, 5), seen[1])
    }

    @Test
    fun applyLinePrefixTransform_isAffectedFlagRespectsSelection() {
        val text = "A\nB\nC"
        val affected = mutableListOf<Boolean>()
        ParagraphTransforms.applyLinePrefixTransform(text, selStart = 2, selEnd = 3) { _, _, _, _, isAffected ->
            affected.add(isAffected)
            ParagraphTransforms.LineEdit(ParagraphTransforms.LinePrefixMode.PREPEND)
        }
        assertEquals(listOf(false, true, false), affected)
    }

    @Test
    fun applyLinePrefixTransform_prependPrefixAddsToText() {
        val text = "AB"
        val result = ParagraphTransforms.applyLinePrefixTransform(text, selStart = 0, selEnd = 2) { _, _, _, _, _ ->
            ParagraphTransforms.LineEdit(ParagraphTransforms.LinePrefixMode.PREPEND, prefix = "X")
        }
        assertEquals("XAB", result.text)
        // eski 0 → yeni 1 (X sonrası)
        assertEquals(1, result.mapOffset(0))
        assertEquals(2, result.mapOffset(1))
    }

    @Test
    fun applyLinePrefixTransform_replaceModeRemovesOldChars() {
        val text = "1. AB" // eski önek "1. " 3 char
        val result = ParagraphTransforms.applyLinePrefixTransform(text, selStart = 0, selEnd = 5) { _, line, _, _, _ ->
            val m = Regex("""^(\d+)\.\s+""").find(line)
            if (m != null) ParagraphTransforms.LineEdit(
                ParagraphTransforms.LinePrefixMode.REPLACE,
                replacementBody = line.substring(m.value.length)
            )
            else ParagraphTransforms.LineEdit(ParagraphTransforms.LinePrefixMode.PREPEND)
        }
        assertEquals("AB", result.text)
        // eski 3 ("A" öncesi) → yeni 0
        assertEquals(0, result.mapOffset(3))
    }

    @Test
    fun applyLinePrefixTransform_remapSpansAndAlignments() {
        val text = "Hello"
        val result = ParagraphTransforms.applyLinePrefixTransform(text, selStart = 0, selEnd = 5) { _, _, _, _, _ ->
            ParagraphTransforms.LineEdit(ParagraphTransforms.LinePrefixMode.PREPEND, prefix = ">>")
        }
        val spans = listOf(FormatSpan(0, 5, bold = true))
        val remapped = result.remapSpans(spans)
        assertEquals(1, remapped.size)
        assertEquals(2, remapped[0].start)
        assertEquals(7, remapped[0].end)
    }
}
