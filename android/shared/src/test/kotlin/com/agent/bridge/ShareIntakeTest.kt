package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntakeTest {

    private fun file(name: String) = SharedFile(cachePath = "/cache/$name", name = name)

    @Test
    fun singleFileTitleShowsTheName() {
        assertEquals("tensip.pdf", shareDialogTitle(listOf(file("tensip.pdf"))))
    }

    // Çoklu paylaşımda adları yan yana dizmek başlığı taşırıyordu (WhatsApp'tan
    // 5 ek seçmek tipik); sayıya düşülür.
    @Test
    fun multipleFilesFallBackToCount() {
        assertEquals("3 dosya", shareDialogTitle(listOf(file("a.pdf"), file("b.pdf"), file("c.pdf"))))
    }

    @Test
    fun emptyShareIsStatedNotBlank() {
        assertEquals("Paylaşılan dosya yok", shareDialogTitle(emptyList()))
    }

    // Çalışma alanı yoksa hedef sorusu HİÇ sorulmaz: kullanıcıya boş liste
    // göstermek yerine paylaşımın eski davranışı (sohbete ekle) sürer.
    @Test
    fun workspaceTargetNeedsAtLeastOneWorkspace() {
        assertFalse(shareHasWorkspaceTarget(emptyList()))
        assertTrue(shareHasWorkspaceTarget(listOf(CoworkWorkspace("Dosya", "C:/x"))))
    }
}
