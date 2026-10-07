package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDraftTest {

    // Ek zaten köprüye yüklenmiş; taslak yalnız ad+yolu taşır, dolayısıyla
    // kalıcılaştırmanın maliyeti yok — ekli taslak da tam geri gelmeli.
    @Test
    fun roundTripKeepsTabTextAndAttachments() {
        val draft = ComposerDraft(
            tabId = "tab-1",
            text = "dilekçeyi yarına yetiştir",
            attachments = listOf(
                ChatAttachment(name = "tensip.pdf", path = "C:\\Dosya\\tensip.pdf"),
                ChatAttachment(name = "foto.png", path = "C:\\Dosya\\foto.png", isImage = true, localUri = "content://x"),
            ),
        )
        val decoded = ComposerDraftJson.decode(ComposerDraftJson.encode(draft))
        assertEquals(draft, decoded)
    }

    // Taslak yanlış sekmeye doldurulursa mesaj yanlış oturuma gider; bu yüzden
    // eşleşme şart.
    @Test
    fun draftOnlyReturnsForItsOwnTab() {
        val raw = ComposerDraftJson.encode(ComposerDraft("tab-1", "metin"))
        assertEquals("metin", draftFor(raw, "tab-1").text)
        assertTrue(draftFor(raw, "tab-2").isEmpty)
        assertTrue(draftFor(raw, "").isEmpty)
    }

    // Yalnız ek içeren taslak da gerçek bir taslaktır (metin boş olabilir).
    @Test
    fun attachmentOnlyDraftIsNotEmpty() {
        val raw = ComposerDraftJson.encode(
            ComposerDraft("tab-1", "", listOf(ChatAttachment(name = "a.pdf", path = "C:\\a.pdf"))),
        )
        val draft = draftFor(raw, "tab-1")
        assertEquals(1, draft.attachments.size)
        assertTrue(!draft.isEmpty)
    }

    @Test
    fun brokenOrMissingRecordYieldsEmptyDraft() {
        assertTrue(draftFor(null, "tab-1").isEmpty)
        assertTrue(draftFor("", "tab-1").isEmpty)
        assertTrue(draftFor("{bozuk", "tab-1").isEmpty)
        // Sürümü tanımayan kayıt: sessizce boş.
        assertTrue(draftFor("""{"version":9,"tabId":"tab-1","text":"x"}""", "tab-1").isEmpty)
        // Sekmesiz eski kayıt da elenir.
        assertTrue(draftFor("""{"version":1,"text":"x"}""", "tab-1").isEmpty)
        // Yolu olmayan ek atılır: modele hiçbir şey ifade etmez.
        val noPath = ComposerDraftJson.decode("""{"version":1,"tabId":"t","text":"","attachments":[{"name":"a"}]}""")
        assertEquals(0, noPath.attachments.size)
    }
}
