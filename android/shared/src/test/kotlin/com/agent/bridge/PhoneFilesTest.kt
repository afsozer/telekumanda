package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhoneFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dosya(ad: String, icerik: String = "x"): File =
        temp.newFile(ad).apply { writeText(icerik) }

    @Test
    fun listDirSeparatesFilesAndDirectories() {
        dosya("a.txt", "12345")
        temp.newFolder("klasor")
        val out = PhoneFiles.listDir(temp.root.absolutePath)
        assertTrue(out.ok)
        assertEquals(setOf("a.txt", "klasor"), out.dirs.map { it.name }.toSet())
        assertEquals("dir", out.dirs.first { it.name == "klasor" }.type)
        assertEquals(5L, out.dirs.first { it.name == "a.txt" }.size)
    }

    @Test
    fun listDirOnMissingPathIsNotOk() {
        // Kullaniciya "bos klasor" degil hata gostermemiz icin ok=false sart.
        val out = PhoneFiles.listDir(File(temp.root, "yok").absolutePath)
        assertFalse(out.ok)
        assertTrue(out.dirs.isEmpty())
    }

    @Test
    fun renameRefusesPathEscape() {
        dosya("a.txt")
        val kacis = "../disari.txt"
        assertTrue(PhoneFiles.rename(File(temp.root, "a.txt").absolutePath, kacis))
        // Ust klasore SIZMAMALI: ad temizlenip ayni klasorde kalmali.
        assertTrue(File(temp.root, "disari.txt").exists())
        assertFalse(File(temp.root.parentFile, "disari.txt").exists())
    }

    @Test
    fun renameRefusesOverwrite() {
        dosya("a.txt")
        dosya("b.txt")
        assertFalse(PhoneFiles.rename(File(temp.root, "a.txt").absolutePath, "b.txt"))
        assertTrue(File(temp.root, "a.txt").exists())
    }

    @Test
    fun moveKeepsNameAndRefusesCollision() {
        dosya("a.txt")
        val hedef = temp.newFolder("hedef")
        assertTrue(PhoneFiles.move(File(temp.root, "a.txt").absolutePath, hedef.absolutePath))
        assertTrue(File(hedef, "a.txt").exists())

        dosya("a.txt")
        assertFalse(PhoneFiles.move(File(temp.root, "a.txt").absolutePath, hedef.absolutePath))
    }

    @Test
    fun deleteRemovesFolderTree() {
        val kok = temp.newFolder("agac")
        File(kok, "ic").mkdirs()
        File(kok, "ic/derin.txt").writeText("x")
        assertTrue(PhoneFiles.delete(kok.absolutePath))
        assertFalse(kok.exists())
    }

    @Test
    fun uniqueChildNumbersCollisions() {
        dosya("gorsel.png")
        assertEquals("gorsel (1).png", PhoneFiles.uniqueChild(temp.root.absolutePath, "gorsel.png").name)
        dosya("gorsel (1).png")
        assertEquals("gorsel (2).png", PhoneFiles.uniqueChild(temp.root.absolutePath, "gorsel.png").name)
    }

    @Test
    fun uniqueChildHandlesExtensionlessNames() {
        temp.newFolder("notlar")
        assertEquals("notlar (1)", PhoneFiles.uniqueChild(temp.root.absolutePath, "notlar").name)
    }

    @Test
    fun isWithinRejectsSiblingWithSharedPrefix() {
        // "/a/AgentBridge2" "/a/AgentBridge"in ICINDE degildir — duz startsWith
        // bunu kacirir, korkuluk delinir.
        assertTrue(PhoneFiles.isWithin("/a/AgentBridge", "/a/AgentBridge/x"))
        assertTrue(PhoneFiles.isWithin("/a/AgentBridge", "/a/AgentBridge"))
        assertFalse(PhoneFiles.isWithin("/a/AgentBridge", "/a/AgentBridge2/x"))
        assertFalse(PhoneFiles.isWithin("/a/AgentBridge", "/a"))
    }

    @Test
    fun parentStopsAtLockedRoot() {
        assertEquals("/a/kok/ic", PhoneFiles.parentWithin("/a/kok", "/a/kok/ic/derin"))
        assertEquals("", PhoneFiles.parentWithin("/a/kok", "/a/kok"))
    }

    @Test
    fun parentIsFreeWhenRootIsBlank() {
        // Merkez gezgininde ust klasorlere cikilabilmeli (kilit yok).
        assertEquals("/a/kok", PhoneFiles.parentWithin("", "/a/kok/ic"))
    }

    @Test
    fun coworkSpacePathMirrorsWorkspaceName() {
        assertEquals(
            "/sd/AgentBridge/CoworkSpaces/Dava Dosyasi",
            PhoneFiles.coworkSpacePath("/sd/AgentBridge", "Dava Dosyasi"),
        )
        assertEquals("", PhoneFiles.coworkSpacePath("/sd/AgentBridge", "  "))
    }

    @Test
    fun pathLogicIsPlatformIndependent() {
        // Bu fonksiyonlar Android yollarini isler ama testler Windows'ta kosuyor;
        // java.io.File kullanilirsa yol "C:\..." olup mantik sessizce bozulur.
        assertEquals("/sd/AgentBridge/Pictures", PhoneFiles.join("/sd", "AgentBridge", "Pictures"))
        assertEquals("/sd/AgentBridge", PhoneFiles.parentWithin("", "/sd/AgentBridge/Pictures"))
    }
}
