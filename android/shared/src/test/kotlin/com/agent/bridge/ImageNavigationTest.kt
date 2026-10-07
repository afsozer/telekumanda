package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageNavigationTest {

    @Test
    fun movesForwardAndBackward() {
        assertEquals(3, adjacentImageIndex(2, 5, 1))
        assertEquals(1, adjacentImageIndex(2, 5, -1))
    }

    @Test
    fun stopsAtBothEnds() {
        // Sarma yok: son görselden ileri, ilkinden geri gidilemez.
        assertNull(adjacentImageIndex(4, 5, 1))
        assertNull(adjacentImageIndex(0, 5, -1))
    }

    @Test
    fun singleImageHasNoNeighbour() {
        assertNull(adjacentImageIndex(0, 1, 1))
        assertNull(adjacentImageIndex(0, 1, -1))
    }

    @Test
    fun unknownIndexIsIgnored() {
        // index = -1: görsel klasör listesinde yok (ör. dış intent'le açıldı).
        assertNull(adjacentImageIndex(-1, 5, 1))
        assertNull(adjacentImageIndex(0, 0, 1))
        assertNull(adjacentImageIndex(9, 5, -1))
    }

    @Test
    fun swipeLeftGoesToNextImage() {
        assertEquals(1, swipeDirection(-120f, 72f))
        assertEquals(-1, swipeDirection(120f, 72f))
    }

    @Test
    fun shortDragIsNotASwipe() {
        assertEquals(0, swipeDirection(-40f, 72f))
        assertEquals(0, swipeDirection(0f, 72f))
    }

    @Test
    fun exactThresholdCounts() {
        assertEquals(1, swipeDirection(-72f, 72f))
        assertEquals(-1, swipeDirection(72f, 72f))
    }

    @Test
    fun nonPositiveThresholdNeverSwipes() {
        // Ölçüm henüz yapılmamışsa (0 px) her dokunuş kaydırma sayılmamalı.
        assertEquals(0, swipeDirection(-500f, 0f))
    }
}
