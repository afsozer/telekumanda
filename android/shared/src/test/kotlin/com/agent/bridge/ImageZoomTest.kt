package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageZoomTest {

    @Test
    fun wideImageFitsByWidth() {
        // 2:1 görsel, kare ekran -> genişlik dolar, yükseklik yarı kalır.
        val fit = fitInside(1000f, 1000f, 2000f, 1000f)
        assertEquals(1000f, fit.width, 0.01f)
        assertEquals(500f, fit.height, 0.01f)
    }

    @Test
    fun tallImageFitsByHeight() {
        val fit = fitInside(1000f, 1000f, 500f, 2000f)
        assertEquals(250f, fit.width, 0.01f)
        assertEquals(1000f, fit.height, 0.01f)
    }

    @Test
    fun degenerateSizesReturnZero() {
        assertEquals(0f, fitInside(0f, 100f, 10f, 10f).width, 0.01f)
        assertEquals(0f, fitInside(100f, 100f, 0f, 10f).height, 0.01f)
    }

    @Test
    fun panIsBoundedByImageEdges() {
        // 2000px içerik, 1000px ekran -> her yöne en fazla 500px kayabilir.
        assertEquals(500f, clampPan(9999f, 2000f, 1000f), 0.01f)
        assertEquals(-500f, clampPan(-9999f, 2000f, 1000f), 0.01f)
        assertEquals(120f, clampPan(120f, 2000f, 1000f), 0.01f)
    }

    @Test
    fun contentSmallerThanViewportCannotMove() {
        // Fotoğraf ekrandan küçükse kaydırma hakkı yok — eskiden ekrandan
        // kaçırılabiliyordu.
        assertEquals(0f, clampPan(400f, 600f, 1000f), 0.01f)
        assertEquals(0f, clampPan(-400f, 1000f, 1000f), 0.01f)
    }

    @Test
    fun smallZoomSnapsBackToOne() {
        assertEquals(1f, settleScale(1.02f), 0.001f)
        assertEquals(1f, settleScale(1.14f), 0.001f)
    }

    @Test
    fun realZoomIsKept() {
        assertEquals(1.15f, settleScale(1.15f), 0.001f)
        assertEquals(3.4f, settleScale(3.4f), 0.001f)
    }
}
