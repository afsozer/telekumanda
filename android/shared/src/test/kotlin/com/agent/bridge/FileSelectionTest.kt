package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSelectionTest {

    @Test
    fun togglingAddsThenRemoves() {
        val bir = toggleSelection(emptySet(), "/a/x.txt")
        assertEquals(setOf("/a/x.txt"), bir)
        assertEquals(emptySet<String>(), toggleSelection(bir, "/a/x.txt"))
    }

    @Test
    fun togglingKeepsOtherSelections() {
        assertEquals(setOf("/a", "/b"), toggleSelection(setOf("/a"), "/b"))
    }

    @Test
    fun emptyClipboardCannotPaste() {
        assertFalse(canPasteInto(FileClipboard(), "/hedef"))
    }

    @Test
    fun copyIntoSameFolderIsAllowed() {
        // Ayni klasore kopyalamak gecerli: " (1)" ekiyle cogaltma.
        val pano = FileClipboard(listOf("/a/x.txt"), move = false, sourceDir = "/a")
        assertTrue(canPasteInto(pano, "/a"))
    }

    @Test
    fun cutIntoSameFolderIsBlocked() {
        // Tasima ayni klasore anlamsiz; "tasindi" demek yaniltici olurdu.
        val pano = FileClipboard(listOf("/a/x.txt"), move = true, sourceDir = "/a")
        assertFalse(canPasteInto(pano, "/a"))
        assertTrue(canPasteInto(pano, "/b"))
    }

    @Test
    fun trailingSlashDoesNotFoolSameFolderCheck() {
        val pano = FileClipboard(listOf("/a/x.txt"), move = true, sourceDir = "/a/")
        assertFalse(canPasteInto(pano, "/a"))
    }

    @Test
    fun windowsSeparatorsCompareEqual() {
        // PC tarafinda yollar ters bolu geliyor.
        val pano = FileClipboard(listOf("C:\\i\\x.txt"), move = true, sourceDir = "C:\\i")
        assertFalse(canPasteInto(pano, "C:/i"))
    }

    @Test
    fun blankDestinationCannotPaste() {
        val pano = FileClipboard(listOf("/a/x.txt"), move = false, sourceDir = "/a")
        assertFalse(canPasteInto(pano, ""))
    }

    @Test
    fun moveClearsClipboardButCopyKeepsIt() {
        // Kopyalananı birden cok yere yapistirabilmeli; tasinan artik kaynakta yok.
        val kes = FileClipboard(listOf("/a/x"), move = true, sourceDir = "/a")
        assertTrue(clipboardAfterPaste(kes).isEmpty)
        val kopya = FileClipboard(listOf("/a/x"), move = false, sourceDir = "/a")
        assertEquals(kopya, clipboardAfterPaste(kopya))
    }

    @Test
    fun selectAllPicksEverythingVisible() {
        assertEquals(setOf("/a", "/b"), selectAllToggle(setOf("/a"), listOf("/a", "/b")))
        assertEquals(setOf("/a", "/b"), selectAllToggle(emptySet(), listOf("/a", "/b")))
    }

    @Test
    fun selectAllWhenEverythingSelectedClearsInstead() {
        assertEquals(emptySet<String>(), selectAllToggle(setOf("/a", "/b"), listOf("/a", "/b")))
    }

    @Test
    fun selectAllInEmptyFolderStaysEmpty() {
        assertEquals(emptySet<String>(), selectAllToggle(emptySet(), emptyList()))
    }

    @Test
    fun selectionTitleCountsItems() {
        assertEquals("3 öğe seçildi", selectionTitle(3))
    }
}
