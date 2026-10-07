package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDisplayRowsTest {

    private fun msg(role: String, text: String, thoughtIndex: Int = -1) =
        ChatMessage(role = role, text = text, time = "", thoughtIndex = thoughtIndex)

    @Test
    fun `ardisik arac satirlari tek grupta toplanir`() {
        val rows = buildChatDisplayRows(listOf(
            msg("user", "selam"),
            msg("thought", "Grep foo", 0),
            msg("thought", "$ dir", 1),
            msg("thought", "Read bar.kt", 2),
            msg("agent", "cevap"),
        ))
        assertEquals(3, rows.size)
        assertEquals(ChatDisplayRow.Single(0), rows[0])
        assertEquals(ChatDisplayRow.ToolGroup(listOf(1, 2, 3)), rows[1])
        assertEquals(ChatDisplayRow.Single(4), rows[2])
    }

    @Test
    fun `tek arac satiri da kararli bir grup olur`() {
        val rows = buildChatDisplayRows(listOf(
            msg("user", "selam"),
            msg("thought", "Grep foo", 0),
            msg("agent", "cevap"),
        ))
        assertEquals(listOf<ChatDisplayRow>(
            ChatDisplayRow.Single(0),
            ChatDisplayRow.ToolGroup(listOf(1), GroupKind.TOOL),
            ChatDisplayRow.Single(2),
        ), rows)
    }

    @Test
    fun `claude thinking ve araclari tur boyunca ayri gruplara iner`() {
        // Claude thinking özeti boş olsa da ayrıntısı thoughtIndex üzerinden
        // yüklenebilir; akışta yalnız tek kapalı grup yer kaplar.
        val rows = buildChatDisplayRows(listOf(
            msg("thought", "Grep a", 0),
            msg("thought", "Grep b", 1),
            msg("thought", "", 2),
            msg("thought", "Read c", 3),
            msg("thought", "Edit d", 4),
        ))
        assertEquals(listOf<ChatDisplayRow>(
            ChatDisplayRow.ToolGroup(listOf(2), GroupKind.THOUGHT),
            ChatDisplayRow.ToolGroup(listOf(0, 1, 3, 4)),
        ), rows)
    }

    @Test
    fun `uzun indexsiz thinking gruplanir kisa durum seridi kalir`() {
        val longThought = "x".repeat(250)
        val rows = buildChatDisplayRows(listOf(
            msg("user", "selam"),
            msg("thought", longThought),            // eski opencode uzun düşüncesi → gizli
            msg("thought", "süreç beklenmedik kapandı"), // hata/durum şeridi → kalır
            msg("agent", "cevap"),
        ))
        assertEquals(listOf<ChatDisplayRow>(
            ChatDisplayRow.Single(0),
            ChatDisplayRow.ToolGroup(listOf(1), GroupKind.THOUGHT),
            ChatDisplayRow.Single(2),
            ChatDisplayRow.Single(3),
        ), rows)
    }

    @Test
    fun `agent rolundeki arac benzeri metin gruplanmaz`() {
        val rows = buildChatDisplayRows(listOf(
            msg("agent", "Read the docs"),
            msg("agent", "Grep is a tool"),
        ))
        assertEquals(listOf<ChatDisplayRow>(
            ChatDisplayRow.Single(0), ChatDisplayRow.Single(1),
        ), rows)
    }

    // Codex reasoning'i her item icin ayri "🧠" satiri yaziyor; akış üst üste
    // 5-6 boş "Düşünce" kartına dönmemeli (cihazda görüldü).
    @Test
    fun `ardisik dusunce satirlari kendi grubunda toplanir`() {
        val rows = buildChatDisplayRows(listOf(
            msg("user", "selam"),
            msg("thought", "🧠", 0),
            msg("thought", "🧠", 1),
            msg("thought", "🧠", 2),
            msg("agent", "cevap"),
        ))
        assertEquals(listOf<ChatDisplayRow>(
            ChatDisplayRow.Single(0),
            ChatDisplayRow.ToolGroup(listOf(1, 2, 3), GroupKind.THOUGHT),
            ChatDisplayRow.Single(4),
        ), rows)
    }

    @Test
    fun `arac ve dusunce tur boyunca kendi tek grubunda birlesir`() {
        val rows = buildChatDisplayRows(listOf(
            msg("thought", "Grep a", 0),
            msg("thought", "Grep b", 1),
            msg("thought", "🧠", 2),
            msg("thought", "🧠", 3),
            msg("thought", "$ dir", 4),
            msg("thought", "Read c", 5),
        ))
        assertEquals(listOf<ChatDisplayRow>(
            ChatDisplayRow.ToolGroup(listOf(2, 3), GroupKind.THOUGHT),
            ChatDisplayRow.ToolGroup(listOf(0, 1, 4, 5), GroupKind.TOOL),
        ), rows)
    }

    @Test
    fun `tek dusunce satiri da kararli bir grup olur`() {
        val rows = buildChatDisplayRows(listOf(
            msg("thought", "🧠", 0),
            msg("agent", "cevap"),
        ))
        assertEquals(listOf<ChatDisplayRow>(
            ChatDisplayRow.ToolGroup(listOf(0), GroupKind.THOUGHT),
            ChatDisplayRow.Single(1),
        ), rows)
    }

    @Test
    fun `ayni kullanıcı turundaki flood iki gruba iner`() {
        val rows = buildChatDisplayRows(listOf(
            msg("user", "istek"),
            msg("agent", "Önce inceleyeceğim"),
            msg("thought", "$ rg foo", 0),
            msg("thought", "🧠", 1),
            msg("agent", "İlk bulgu hazır"),
            msg("thought", "functions.exec", 2), // sağlayıcıya özgü araç adı
            msg("thought", "🧠", 3),
            msg("agent", "Tamamlandı"),
        ))

        assertEquals(listOf<ChatDisplayRow>(
            ChatDisplayRow.Single(0),
            ChatDisplayRow.Single(1),
            ChatDisplayRow.ToolGroup(listOf(3, 6), GroupKind.THOUGHT),
            ChatDisplayRow.ToolGroup(listOf(2, 5), GroupKind.TOOL),
            ChatDisplayRow.Single(4),
            ChatDisplayRow.Single(7),
        ), rows)
    }

    @Test
    fun `yeni kullanıcı mesaji grup siniridir`() {
        val rows = buildChatDisplayRows(listOf(
            msg("user", "bir"),
            msg("thought", "Read a", 0),
            msg("user", "iki"),
            msg("thought", "Read b", 1),
        ))

        assertEquals(listOf<ChatDisplayRow>(
            ChatDisplayRow.Single(0),
            ChatDisplayRow.ToolGroup(listOf(1), GroupKind.TOOL),
            ChatDisplayRow.Single(2),
            ChatDisplayRow.ToolGroup(listOf(3), GroupKind.TOOL),
        ), rows)
    }

    @Test
    fun `bos liste bos doner`() {
        assertEquals(emptyList<ChatDisplayRow>(), buildChatDisplayRows(emptyList()))
    }
}
