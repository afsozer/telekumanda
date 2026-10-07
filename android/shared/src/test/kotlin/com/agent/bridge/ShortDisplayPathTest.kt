package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortDisplayPathTest {

    @Test
    fun longAndroidPathKeepsLastSegments() {
        assertEquals(
            "…/AgentBridge/Pictures",
            shortDisplayPath("/storage/emulated/0/AgentBridge/Pictures"),
        )
    }

    @Test
    fun windowsPathIsNormalised() {
        assertEquals("…/ornek/agtest", shortDisplayPath("C:\\Users\\ornek\\agtest"))
    }

    @Test
    fun shortPathIsLeftAlone() {
        assertEquals("Bilgisayar", shortDisplayPath("Bilgisayar"))
        assertEquals("/sdcard", shortDisplayPath("/sdcard"))
    }

    @Test
    fun trailingSlashDoesNotCreateEmptySegment() {
        assertEquals("…/AgentBridge/Pictures", shortDisplayPath("/sdcard/AgentBridge/Pictures/"))
    }

    @Test
    fun blankStaysBlank() {
        assertEquals("", shortDisplayPath("   "))
    }
}
