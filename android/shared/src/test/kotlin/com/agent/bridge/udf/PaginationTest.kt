package com.agent.bridge.udf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PaginationTest {

    @Test
    fun officialLineHeightUsesAbsoluteInchFormula() {
        assertEquals(14.4f, UdfUnits.lineHeightPoints(null), 0.001f)
        assertEquals(14.4f, UdfUnits.lineHeightPoints(0f), 0.001f)
        assertEquals(21.6f, UdfUnits.lineHeightPoints(0.5f), 0.001f)
        assertEquals(28.8f, UdfUnits.lineHeightPoints(1f), 0.001f)
    }

    @Test
    fun emptyDocumentStillProducesOnePage() {
        assertEquals(listOf(PageSlice(0f, 100f)), paginateLineUnits(emptyList(), 100f))
    }

    @Test
    fun breaksOnlyBetweenMeasuredUnits() {
        val pages = paginateLineUnits(
            lines = listOf(0f to 30f, 30f to 60f, 60f to 90f, 90f to 120f),
            pageContentHeightPx = 75f
        )

        assertEquals(listOf(PageSlice(0f, 60f), PageSlice(60f, 120f)), pages)
    }

    @Test
    fun oversizedUnitGetsItsOwnPage() {
        val pages = paginateLineUnits(
            lines = listOf(0f to 120f, 120f to 150f),
            pageContentHeightPx = 75f
        )

        assertEquals(listOf(PageSlice(0f, 120f), PageSlice(120f, 150f)), pages)
    }

    @Test
    fun rejectsNonPositivePageHeight() {
        assertThrows(IllegalArgumentException::class.java) {
            paginateLineUnits(emptyList(), 0f)
        }
    }
}
