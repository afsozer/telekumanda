package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchLogicTest {

    private fun msgs() = listOf(
        ChatMessage("user", "ilk mesaj burada", rowId = "r1"),
        ChatMessage("agent", "cevap bir", rowId = "r2"),
        ChatMessage("thought", "ara ipucu araci", rowId = "r3"), // aranmaz (thought)
        ChatMessage("user", "ikinci ipucu var", rowId = "r4"),
        ChatMessage("agent", "son ipucu cevabi", rowId = "r5"),
    )

    @Test
    fun shortQueryReturnsNoMatch() {
        val m = ChatSearchLogic.computeMatch(msgs(), "i", preferredIndex = -1)
        assertEquals(emptyList<String>(), m.matchRowIds)
        assertEquals(-1, m.selectedIndex)
    }

    @Test
    fun matchesAcrossFullHistoryIgnoringThoughts() {
        // "ipucu" user(r4) ve agent(r5)'te var; thought(r3) atlanır.
        val m = ChatSearchLogic.computeMatch(msgs(), "ipucu", preferredIndex = -1)
        assertEquals(listOf("r4", "r5"), m.matchRowIds)
        assertEquals(0, m.selectedIndex) // preferredIndex yok -> ilk eşleşme
    }

    @Test
    fun preferredIndexSelectsThatOrdinal() {
        // Deep-link: ikinci eşleşme (ordinal 1) seçilmeli.
        val m = ChatSearchLogic.computeMatch(msgs(), "ipucu", preferredIndex = 1)
        assertEquals(listOf("r4", "r5"), m.matchRowIds)
        assertEquals(1, m.selectedIndex)
    }

    @Test
    fun outOfRangePreferredIndexFallsBackToFirst() {
        val m = ChatSearchLogic.computeMatch(msgs(), "ipucu", preferredIndex = 9)
        assertEquals(0, m.selectedIndex)
    }

    @Test
    fun blankRowIdUsesIndexFallbackAlignedWithUi() {
        val list = listOf(
            ChatMessage("user", "alpha bulundu", rowId = ""),
            ChatMessage("agent", "beta", rowId = ""),
            ChatMessage("user", "alpha yine", rowId = ""),
        )
        val m = ChatSearchLogic.computeMatch(list, "alpha", preferredIndex = -1)
        // idx_<index> ChatRootScreen ile aynı enumeration index'i kullanır.
        assertEquals(listOf("idx_0", "idx_2"), m.matchRowIds)
    }

    @Test
    fun caseInsensitiveMatch() {
        val m = ChatSearchLogic.computeMatch(msgs(), "IPUCU", preferredIndex = -1)
        assertEquals(listOf("r4", "r5"), m.matchRowIds)
    }
}
