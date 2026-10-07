package com.agent.bridge.ui2.chat

import com.agent.bridge.ChatDisplayRow
import com.agent.bridge.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Kaydırma çapası (kullanıcı isteği 05.08.2026). Ham lazy index saklanmıyor:
// liste reverseLayout ve yeni mesaj index 0'a giriyor, aradaki her yeni mesaj
// eski index'i kaydırıyor. Çapa satırın KİMLİĞİ.
class ChatScrollMemoryTest {

    private fun msg(id: String) = ChatMessage(role = "assistant", text = "x", rowId = id)

    private val messages = listOf(msg("a"), msg("b"), msg("c"), msg("d"))
    private val rows = listOf(
        ChatDisplayRow.Single(0),
        ChatDisplayRow.Single(1),
        ChatDisplayRow.Single(2),
        ChatDisplayRow.Single(3),
    )

    @Test
    fun cevrimGidisDonus() {
        // lazy index 1 = sondan ikinci satır ("c").
        val rowId = chatAnchorRowId(messages, rows, lazyIndex = 1, tailExtra = 0)
        assertEquals("c", rowId)
        assertEquals(1, chatLazyIndexForRowId(messages, rows, "c", tailExtra = 0))
    }

    @Test
    fun yeniMesajGelinceCapaKAYMAZ() {
        // "c"yi çapaladık; sonra iki yeni mesaj geldi. Ham index 1 artık "e"yi
        // gösterirdi — kimlik üzerinden 3'e kayıyor, aynı satırda kalıyoruz.
        val grown = messages + listOf(msg("e"), msg("f"))
        val grownRows = rows + listOf(ChatDisplayRow.Single(4), ChatDisplayRow.Single(5))
        assertEquals(3, chatLazyIndexForRowId(grown, grownRows, "c", tailExtra = 0))
    }

    @Test
    fun dipKartlariIndexiKaydirir() {
        // Onay kartı gibi mesajlardan önce emit edilen 2 öğe varken lazy index 3,
        // mesaj satırlarında pos 1'e ("c") denk gelir.
        assertEquals("c", chatAnchorRowId(messages, rows, lazyIndex = 3, tailExtra = 2))
        assertEquals(3, chatLazyIndexForRowId(messages, rows, "c", tailExtra = 2))
    }

    @Test
    fun dipKartininKendisiCapaOlmaz() {
        assertNull(chatAnchorRowId(messages, rows, lazyIndex = 0, tailExtra = 2))
    }

    @Test
    fun silinmisSatirNullDoner() {
        assertNull(chatLazyIndexForRowId(messages, rows, "yok", tailExtra = 0))
    }

    @Test
    fun grupSatirindaIlkUyeCapaOlur() {
        val grouped = listOf(ChatDisplayRow.Single(0), ChatDisplayRow.ToolGroup(listOf(1, 2)), ChatDisplayRow.Single(3))
        // lazy index 1 = sondan ikinci görünüm satırı = grup.
        assertEquals("b", chatAnchorRowId(messages, grouped, lazyIndex = 1, tailExtra = 0))
        // Grubun HERHANGİ bir üyesi aynı satırı bulur.
        assertEquals(1, chatLazyIndexForRowId(messages, grouped, "c", tailExtra = 0))
    }

    @Test
    fun rowIdBosOlaninAnahtariIndextenTurer() {
        val anon = listOf(ChatMessage(role = "user", text = "x"))
        val anonRows = listOf(ChatDisplayRow.Single(0))
        assertEquals("idx_0", chatAnchorRowId(anon, anonRows, lazyIndex = 0, tailExtra = 0))
    }

    @Test
    fun bellekOturumBasinaAyriVeDipteKayitSilinir() {
        val memory = ChatScrollMemory()
        memory.remember("claude-app:s1", ChatScrollAnchor("c", 12))
        memory.remember("codex-app:s2", ChatScrollAnchor("a", 0))

        assertEquals(ChatScrollAnchor("c", 12), memory.anchor("claude-app:s1"))
        assertEquals(ChatScrollAnchor("a", 0), memory.anchor("codex-app:s2"))

        memory.remember("claude-app:s1", null)
        assertNull(memory.anchor("claude-app:s1"))
    }
}
