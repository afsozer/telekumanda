package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sekme başına taslak — 19.08.2026'da canlıda bildirilen sızıntının testi:
 * bir sekmede yazılan metin sekme değişince composer'da kalıyordu ve yanlış
 * oturuma gönderilebiliyordu.
 */
class ComposerDraftSwitchTest {

    private fun sekme(id: String) = AppTab(id = id, backend = "claude-app", title = id)

    private fun durum(vararg ids: String, aktif: String = ids.first()) = RemoteUiState(
        openTabs = ids.map { sekme(it) },
        activeTabId = aktif,
    )

    @Test
    fun `sekme degisince metin composer'da KALMAZ`() {
        val once = durum("a", "b").copy(input = "yarim cumle")
        val sonra = once.withActiveTab("b")
        assertEquals("", sonra.input)
        assertEquals("b", sonra.activeTabId)
    }

    @Test
    fun `geri donunce ayni sekmenin taslagi geri gelir`() {
        val donus = durum("a", "b")
            .copy(input = "yarim cumle")
            .withActiveTab("b")
            .copy(input = "b'nin metni")
            .withActiveTab("a")
        assertEquals("yarim cumle", donus.input)
        // b'ninki sözlükte bekliyor, a'nınki artık input'ta.
        assertEquals("b'nin metni", donus.composerDrafts["b"]?.text)
        assertNull(donus.composerDrafts["a"])
    }

    @Test
    fun `ekler de sekmeyle birlikte tasinir`() {
        val ek = ChatAttachment(name = "not.md", path = "/tmp/not.md")
        val sonra = durum("a", "b")
            .copy(attachments = listOf(ek))
            .withActiveTab("b")
        assertTrue(sonra.attachments.isEmpty())
        assertEquals(listOf(ek), sonra.withActiveTab("a").attachments)
    }

    @Test
    fun `bos taslak sozlukte yer kaplamaz`() {
        val sonra = durum("a", "b").withActiveTab("b")
        assertTrue(sonra.composerDrafts.isEmpty())
    }

    @Test
    fun `gonderilen taslak geri donunce hortlamaz`() {
        val gonderildi = durum("a", "b")
            .copy(input = "gidecek")
            .withActiveTab("b")
            .withActiveTab("a")
            .copy(input = "")          // gönderim input'u boşaltır
            .withActiveTab("b")
            .withActiveTab("a")
        assertEquals("", gonderildi.input)
    }

    @Test
    fun `ayni sekmeye gecis durumu bozmaz`() {
        val once = durum("a", "b").copy(input = "duruyor")
        assertEquals(once, once.withActiveTab("a"))
    }

    @Test
    fun `kapanan sekmenin taslagi elenir`() {
        val temiz = durum("a", "b")
            .copy(input = "b icin")
            .withActiveTab("b")          // "b icin" a'nın taslağı olarak saklanır
            .let { it.copy(openTabs = it.openTabs.filterNot { t -> t.id == "a" }) }
            .pruneComposerDrafts()
        assertTrue(temiz.composerDrafts.isEmpty())
    }

    @Test
    fun `diske yazilan tablo etkin sekmeyi de icerir`() {
        val hepsi = durum("a", "b")
            .copy(input = "a metni")
            .withActiveTab("b")
            .copy(input = "b metni")
            .allComposerDrafts()
        assertEquals("a metni", hepsi["a"]?.text)
        assertEquals("b metni", hepsi["b"]?.text)
    }

    @Test
    fun `sozluk gidis donus`() {
        val ek = ChatAttachment(name = "a.png", path = "/tmp/a.png", isImage = true)
        val tablo = mapOf(
            "a" to ComposerDraft("a", "bir"),
            "b" to ComposerDraft("b", "iki", listOf(ek)),
        )
        val geri = ComposerDraftsJson.decode(ComposerDraftsJson.encode(tablo))
        assertEquals(tablo, geri)
    }

    @Test
    fun `v1 tek taslagi goc eder`() {
        val eski = ComposerDraftJson.encode(ComposerDraft("a", "eski metin"))
        val goc = decodeComposerDrafts(null, eski)
        assertEquals("eski metin", goc["a"]?.text)
    }

    @Test
    fun `sekmesiz v1 kaydi goc etmez`() {
        val eski = ComposerDraftJson.encode(ComposerDraft("", "sahipsiz"))
        assertTrue(decodeComposerDrafts(null, eski).isEmpty())
    }

    @Test
    fun `yeni anahtar varsa eskiye bakilmaz`() {
        val eski = ComposerDraftJson.encode(ComposerDraft("a", "eski metin"))
        val yeni = ComposerDraftsJson.encode(emptyMap())
        assertTrue(decodeComposerDrafts(yeni, eski).isEmpty())
    }
}
