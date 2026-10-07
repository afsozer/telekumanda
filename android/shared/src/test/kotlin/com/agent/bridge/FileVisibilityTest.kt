package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileVisibilityTest {

    private fun entry(name: String) = DirEntry(name = name, path = "/x/$name", type = "file")

    @Test
    fun dotFilesAreHidden() {
        assertTrue(isHiddenEntry(".nomedia"))
        assertTrue(isHiddenEntry(".git"))
        assertFalse(isHiddenEntry("rapor.md"))
    }

    @Test
    fun nameWithDotInsideIsNotHidden() {
        // "dava.2026.docx" gizli değil; yalnız BAŞTAKİ nokta sayılır.
        assertFalse(isHiddenEntry("dava.2026.docx"))
    }

    @Test
    fun windowsSystemNamesAreHidden() {
        // Windows'ta öznitelikle gizlenenleri ada bakarak eliyoruz.
        assertTrue(isHiddenEntry("desktop.ini"))
        assertTrue(isHiddenEntry("Thumbs.db"))
        assertTrue(isHiddenEntry("\$RECYCLE.BIN"))
        assertTrue(isHiddenEntry("System Volume Information"))
        assertTrue(isHiddenEntry("node_modules"))
    }

    @Test
    fun toggleOnShowsEverythingUnchanged() {
        val liste = listOf(entry(".nomedia"), entry("a.md"), entry("Thumbs.db"))
        assertEquals(liste, filterHidden(liste, showHidden = true))
    }

    @Test
    fun toggleOffRemovesHiddenOnly() {
        val liste = listOf(entry(".nomedia"), entry("a.md"), entry("Thumbs.db"), entry("b.png"))
        assertEquals(listOf("a.md", "b.png"), filterHidden(liste, showHidden = false).map { it.name })
    }
}
