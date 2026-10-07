package com.agent.bridge.udf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpanModelTest {

    // --- shiftOnTextChange ---

    @Test
    fun shiftOnTextChange_insertAtStartShiftsSpanRight() {
        val spans = listOf(FormatSpan(0, 7, bold = true))
        val result = SpanModel.shiftOnTextChange(spans, changePos = 0, delta = 6)
        assertEquals(1, result.size)
        assertEquals(6, result[0].start)
        assertEquals(13, result[0].end)
        assertTrue(result[0].bold)
    }

    @Test
    fun shiftOnTextChange_insertInMiddleExtendsSpanEnd() {
        val spans = listOf(FormatSpan(0, 7, bold = true))
        val result = SpanModel.shiftOnTextChange(spans, changePos = 3, delta = 1)
        assertEquals(1, result.size)
        assertEquals(0, result[0].start)
        assertEquals(8, result[0].end)
    }

    @Test
    fun shiftOnTextChange_deletionShiftsSubsequentSpanLeft() {
        val spans = listOf(FormatSpan(8, 13, italic = true))
        val result = SpanModel.shiftOnTextChange(spans, changePos = 0, delta = -8)
        assertEquals(1, result.size)
        assertEquals(0, result[0].start)
        assertEquals(5, result[0].end)
    }

    @Test
    fun shiftOnTextChange_spanBeforeCursorUnchanged() {
        val spans = listOf(FormatSpan(0, 5))
        val result = SpanModel.shiftOnTextChange(spans, changePos = 10, delta = 3)
        assertEquals(FormatSpan(0, 5), result[0])
    }

    @Test
    fun shiftOnTextChange_zeroDeltaReturnsSameList() {
        val spans = listOf(FormatSpan(0, 5))
        val result = SpanModel.shiftOnTextChange(spans, changePos = 2, delta = 0)
        assertEquals(spans, result)
    }

    @Test
    fun shiftOnTextChange_dropsSpanThatCollapsesToZeroAfterShift() {
        // [5,8] span'i, changePos=6, delta=-3: cursor span içinde (6>5 && 6<8)
        // → sadece end uzar: end+(-3)=5 → [5,5] çöker, düşürülmeli
        val spans = listOf(FormatSpan(5, 8))
        val result = SpanModel.shiftOnTextChange(spans, changePos = 6, delta = -3)
        assertTrue("çökmüş span düşürülmeli", result.isEmpty())
    }

    // --- shiftAlignmentsOnTextChange ---

    @Test
    fun shiftAlignments_cursorAtSpanEndExtendsIt() {
        // FormatSpan'dan farkı: changePos <= end → end uzar
        val aligns = listOf(AlignmentSpan(0, 5, 1))
        val result = SpanModel.shiftAlignmentsOnTextChange(aligns, changePos = 5, delta = 3)
        assertEquals(1, result.size)
        assertEquals(0, result[0].start)
        assertEquals(8, result[0].end)
    }

    @Test
    fun shiftAlignments_preservesEmptySpan() {
        // start==end hizalama span'i korunabilir (filter it.start <= it.end)
        val aligns = listOf(AlignmentSpan(2, 2, 1))
        val result = SpanModel.shiftAlignmentsOnTextChange(aligns, changePos = 5, delta = 1)
        assertEquals(1, result.size)
    }

    // --- insertAndMerge (5 overlap durumu) ---

    @Test
    fun insertAndMerge_completelyWrappedRemovesInnerSpan() {
        // Eski [2,5] yeni [0,13] içinde → düşer
        val spans = listOf(FormatSpan(2, 5, bold = true))
        val newSpan = FormatSpan(0, 13, bold = true)
        val result = SpanModel.insertAndMerge(spans, newSpan)
        assertEquals(1, result.size)
        assertEquals(0, result[0].start)
        assertEquals(13, result[0].end)
    }

    @Test
    fun insertAndMerge_leftOverlapClipsOldEnd() {
        // Eski [2,7] yeni [5,10] ile çakışıyor → [2,5]'e kırpılır
        val spans = listOf(FormatSpan(2, 7, bold = true))
        val newSpan = FormatSpan(5, 10, italic = true)
        val result = SpanModel.insertAndMerge(spans, newSpan)
        assertEquals(2, result.size)
        val bold = result.first { it.bold }
        val italic = result.first { it.italic }
        assertEquals(2, bold.start); assertEquals(5, bold.end)
        assertEquals(5, italic.start); assertEquals(10, italic.end)
    }

    @Test
    fun insertAndMerge_rightOverlapClipsOldStart() {
        // Eski [5,10] yeni [0,7] ile çakışıyor → [7,10]'a kırpılır
        val spans = listOf(FormatSpan(5, 10, bold = true))
        val newSpan = FormatSpan(0, 7, italic = true)
        val result = SpanModel.insertAndMerge(spans, newSpan)
        assertEquals(2, result.size)
        val bold = result.first { it.bold }
        val italic = result.first { it.italic }
        assertEquals(7, bold.start); assertEquals(10, bold.end)
        assertEquals(0, italic.start); assertEquals(7, italic.end)
    }

    @Test
    fun insertAndMerge_splitSpanAroundNewSpan() {
        // Eski [0,10] yeni [3,7] ile sarılıyor → [0,3] ve [7,10]
        val spans = listOf(FormatSpan(0, 10, bold = true))
        val newSpan = FormatSpan(3, 7, italic = true)
        val result = SpanModel.insertAndMerge(spans, newSpan)
        assertEquals(3, result.size)
        val bolds = result.filter { it.bold }.sortedBy { it.start }
        assertEquals(0, bolds[0].start); assertEquals(3, bolds[0].end)
        assertEquals(7, bolds[1].start); assertEquals(10, bolds[1].end)
        val italic = result.first { it.italic }
        assertEquals(3, italic.start); assertEquals(7, italic.end)
    }

    @Test
    fun insertAndMerge_disjointSpanKeptAsIs() {
        val spans = listOf(FormatSpan(0, 5, bold = true))
        val newSpan = FormatSpan(10, 15, italic = true)
        val result = SpanModel.insertAndMerge(spans, newSpan)
        assertEquals(2, result.size)
    }

    @Test
    fun insertAndMerge_mergesAdjacentSameAttributeSpans() {
        // [0,5] bold + yeni [5,10] bold → tek [0,10] bold
        val spans = listOf(FormatSpan(0, 5, bold = true))
        val result = SpanModel.insertAndMerge(spans, FormatSpan(5, 10, bold = true))
        assertEquals(1, result.size)
        assertEquals(0, result[0].start)
        assertEquals(10, result[0].end)
        assertTrue(result[0].bold)
    }

    // --- mergeAdjacent ---

    @Test
    fun mergeAdjacent_mergesContiguousSameSpans() {
        val spans = listOf(
            FormatSpan(0, 5, bold = true),
            FormatSpan(5, 10, bold = true)
        )
        val result = SpanModel.mergeAdjacent(spans)
        assertEquals(1, result.size)
        assertEquals(0, result[0].start)
        assertEquals(10, result[0].end)
    }

    @Test
    fun mergeAdjacent_doesNotMergeDifferentAttributes() {
        val spans = listOf(
            FormatSpan(0, 5, bold = true),
            FormatSpan(5, 10, italic = true)
        )
        val result = SpanModel.mergeAdjacent(spans)
        assertEquals(2, result.size)
    }

    @Test
    fun mergeAdjacent_emptyInputReturnsEmpty() {
        assertTrue(SpanModel.mergeAdjacent(emptyList()).isEmpty())
    }

    @Test
    fun mergeAdjacent_dropsCollapsedSpans() {
        val spans = listOf(FormatSpan(3, 3, bold = true))
        assertTrue(SpanModel.mergeAdjacent(spans).isEmpty())
    }

    // --- spanAt ---

    @Test
    fun spanAt_returnsSpanWhenCursorInside() {
        val spans = listOf(FormatSpan(0, 10, bold = true))
        val found = SpanModel.spanAt(spans, pos = 5, default = FormatSpan(5, 5))
        assertTrue(found.bold)
    }

    @Test
    fun spanAt_inclusiveEndCursorAtEndReturnsSpan() {
        // pos == end → dahil (cursor span sonunda)
        val spans = listOf(FormatSpan(0, 10, bold = true))
        val found = SpanModel.spanAt(spans, pos = 10, default = FormatSpan(10, 10))
        assertTrue(found.bold)
    }

    @Test
    fun spanAt_strictStartCursorAtStartReturnsDefault() {
        // pos == start → hariç (cursor span başında)
        val spans = listOf(FormatSpan(5, 10, bold = true))
        val found = SpanModel.spanAt(spans, pos = 5, default = FormatSpan(5, 5))
        assertFalse(found.bold)
    }

    @Test
    fun spanAt_returnsDefaultWhenNoCoveringSpan() {
        val found = SpanModel.spanAt(emptyList(), pos = 3, default = FormatSpan(3, 3, size = 14))
        assertEquals(14, found.size)
    }

    // --- isRangeFormattingActive ---

    @Test
    fun isRangeFormattingActive_trueWhenAllCharsCovered() {
        val spans = listOf(FormatSpan(0, 5, bold = true))
        assertTrue(SpanModel.isRangeFormattingActive(spans, 0, 5, { it.bold }, false))
    }

    @Test
    fun isRangeFormattingActive_falseWhenGapExists() {
        val spans = listOf(FormatSpan(0, 2, bold = true), FormatSpan(3, 5, bold = true))
        // 2. karakter boşlukta
        assertFalse(SpanModel.isRangeFormattingActive(spans, 0, 5, { it.bold }, false))
    }

    @Test
    fun isRangeFormattingActive_emptySelectionReturnsDefault() {
        val spans = listOf(FormatSpan(0, 5, bold = true))
        assertTrue(SpanModel.isRangeFormattingActive(spans, 3, 3, { it.bold }, default = true))
        assertFalse(SpanModel.isRangeFormattingActive(spans, 3, 3, { it.bold }, default = false))
    }

    // --- commonSize / commonFamily ---

    @Test
    fun commonSize_returnsSizeOfSpanCoveringStart() {
        val spans = listOf(FormatSpan(0, 5, size = 14))
        assertEquals(14, SpanModel.commonSize(spans, 2, 4))
    }

    @Test
    fun commonSize_returnsNullWhenNoSpanCoversStart() {
        assertNull(SpanModel.commonSize(emptyList(), 0, 5))
    }

    @Test
    fun commonFamily_returnsFamilyOfSpanCoveringStart() {
        val spans = listOf(FormatSpan(0, 5, family = "Arial"))
        assertEquals("Arial", SpanModel.commonFamily(spans, 0, 5))
    }

    // --- naturallyAppliedStyle ---

    @Test
    fun naturallyAppliedStyle_strictlyInsideReturnsSpan() {
        val spans = listOf(FormatSpan(0, 10, bold = true))
        val found = SpanModel.naturallyAppliedStyle(spans, pos = 5, default = FormatSpan(5, 5))
        assertTrue(found.bold)
    }

    @Test
    fun naturallyAppliedStyle_atBoundaryReturnsDefault() {
        // strict (pos > start && pos < end) → baş ve son hariç
        val spans = listOf(FormatSpan(0, 10, bold = true))
        assertEquals(
            FormatSpan(0, 0),
            SpanModel.naturallyAppliedStyle(spans, pos = 0, default = FormatSpan(0, 0))
        )
        assertEquals(
            FormatSpan(10, 10),
            SpanModel.naturallyAppliedStyle(spans, pos = 10, default = FormatSpan(10, 10))
        )
    }
}
