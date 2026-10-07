package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationPagingTest {

    private fun msgs(vararg ids: String) = ids.map { ChatMessage("agent", "m-$it", rowId = it) }

    // Sunucu penceresi (HTTP 500-satır tavanı / WS canlı pencere) sayfalanmış
    // listeyi KIRPMAMALI. Canlı vaka 06.08.2026: arama 6000 satır yüklüyor,
    // poll'un 500 satırlık cevabı listeyi çökertiyor, kullanıcı "sınır"a
    // sıkışıp eski mesajlara çıkamıyordu.
    @Test
    fun kuyrukPenceresiYukluGecmisiKirpmaz() {
        val loaded = msgs("a", "b", "c", "d", "e", "f")
        val window = msgs("d", "e", "f") // sunucunun son-3 penceresi
        assertEquals(loaded, ConversationPaging.mergeTailWindow(loaded, window))
    }

    @Test
    fun penceredekiGuncellemeKuyruguDegistirir() {
        val loaded = msgs("a", "b", "c")
        // "c" akışta büyüdü, "d" yeni geldi; pencere b'den başlıyor.
        val window = listOf(
            ChatMessage("agent", "m-b", rowId = "b"),
            ChatMessage("agent", "m-c GUNCEL", rowId = "c"),
            ChatMessage("agent", "m-d", rowId = "d"),
        )
        val merged = ConversationPaging.mergeTailWindow(loaded, window)
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.rowId })
        assertEquals("m-c GUNCEL", merged.first { it.rowId == "c" }.text)
    }

    // Geri sarmada pencereden sonraki bayat satırlar sunucu gerçeğine uyar: çapa
    // noktasından itibaren HER ŞEY gelenle değişir.
    @Test
    fun geriSarmadaBayatKuyrukDuser() {
        val loaded = msgs("a", "b", "c", "d", "e")
        val window = msgs("c") // sunucu c'den sonrasını sildi
        assertEquals(listOf("a", "b", "c"), ConversationPaging.mergeTailWindow(loaded, window).map { it.rowId })
    }

    @Test
    fun ayrisanListedeGelenKazanir() {
        // /clear sonrası yeni satırlar: çapa yok → sunucu listesi aynen alınır.
        val loaded = msgs("a", "b")
        val window = msgs("x", "y")
        assertEquals(window, ConversationPaging.mergeTailWindow(loaded, window))
    }

    @Test
    fun bosGelenMevcuduKorurBosMevcutGeleniAlir() {
        val loaded = msgs("a")
        assertEquals(loaded, ConversationPaging.mergeTailWindow(loaded, emptyList()))
        assertEquals(loaded, ConversationPaging.mergeTailWindow(emptyList(), loaded))
    }

    @Test
    fun testTargetSelection() {
        // 1. Claude App backend
        val stateClaude = RemoteUiState(
            backend = "claude-app",
            claudeAppSessionId = "claude-sess-123",
            messagesList = listOf(ChatMessage("user", "hi", rowId = "msg-10"))
        )
        val targetClaude = ConversationPaging.target(stateClaude)
        assertEquals("claude-app", targetClaude?.provider)
        assertEquals("claude-sess-123", targetClaude?.sessionId)
        assertEquals("msg-10", targetClaude?.beforeRowId)

        // 2. Codex App backend
        val stateCodex = RemoteUiState(
            backend = "codex-app",
            codexAppSessionId = "codex-sess-456",
            messagesList = listOf(ChatMessage("user", "hi", rowId = "msg-20"))
        )
        val targetCodex = ConversationPaging.target(stateCodex)
        assertEquals("codex-app", targetCodex?.provider)
        assertEquals("codex-sess-456", targetCodex?.sessionId)
        assertEquals("msg-20", targetCodex?.beforeRowId)

        // 3. Cowork backend with codex-app provider
        val stateCowork = RemoteUiState(
            backend = "cowork",
            cowork = CoworkUiState(provider = "codex-app"),
            codexAppSessionId = "cowork-codex-sess",
            messagesList = listOf(ChatMessage("user", "hi", rowId = "msg-30"))
        )
        val targetCowork = ConversationPaging.target(stateCowork)
        assertEquals("codex-app", targetCowork?.provider)
        assertEquals("cowork-codex-sess", targetCowork?.sessionId)
        assertEquals("msg-30", targetCowork?.beforeRowId)

        // 3b. Cowork backend with BLANK provider -> claude-app'e normalize edilmeli
        // (poll/refresh her yerde normalizeCoworkProvider kullanır; target da uymalı).
        val stateCoworkBlank = RemoteUiState(
            backend = "cowork",
            cowork = CoworkUiState(provider = ""),
            claudeAppSessionId = "cowork-claude-sess",
            messagesList = listOf(ChatMessage("user", "hi", rowId = "msg-31"))
        )
        val targetCoworkBlank = ConversationPaging.target(stateCoworkBlank)
        assertEquals("claude-app", targetCoworkBlank?.provider)
        assertEquals("cowork-claude-sess", targetCoworkBlank?.sessionId)
        assertEquals("msg-31", targetCoworkBlank?.beforeRowId)

        // 4. Empty messagesList -> null target
        val stateEmptyMessages = RemoteUiState(
            backend = "claude-app",
            claudeAppSessionId = "claude-sess-123",
            messagesList = emptyList()
        )
        assertNull(ConversationPaging.target(stateEmptyMessages))

        // 5. Blank sessionId -> null target
        val stateBlankSession = RemoteUiState(
            backend = "claude-app",
            claudeAppSessionId = "  ",
            messagesList = listOf(ChatMessage("user", "hi", rowId = "msg-10"))
        )
        assertNull(ConversationPaging.target(stateBlankSession))
    }

    @Test
    fun testGrowLocalPage() {
        val state = RemoteUiState(
            messagesList = List(150) { ChatMessage("user", "msg $it") },
            messagePageSize = 50
        )
        val grown = ConversationPaging.growLocalPage(state)
        assertEquals(150, grown.messagePageSize) // 50 + 100 = 150

        // Constrained by list size
        val state2 = RemoteUiState(
            messagesList = List(120) { ChatMessage("user", "msg $it") },
            messagePageSize = 50
        )
        val grown2 = ConversationPaging.growLocalPage(state2)
        assertEquals(120, grown2.messagePageSize) // clamped to 120
    }

    @Test
    fun testMergeOlder() {
        val initialMessages = listOf(
            ChatMessage("user", "hello", rowId = "msg-2"),
            ChatMessage("assistant", "hi", rowId = "msg-3")
        )
        val state = RemoteUiState(
            messagesList = initialMessages,
            messagePageSize = 100
        )

        // 1. Merge empty older page -> geçmiş tükendi: messagePageSize liste
        // boyunun üstüne çekilir ki UI "Daha eskiyi göster" tuşunu gizlesin
        // (görünürlük koşulu: size >= messagePageSize).
        val emptyPage = ConversationResult("", emptyList(), false, false)
        val stateAfterEmpty = ConversationPaging.mergeOlder(state, emptyPage)
        assertEquals(state.messagesList, stateAfterEmpty.messagesList)
        assertEquals(state.messagesList.size + 100, stateAfterEmpty.messagePageSize)

        // 2. Merge non-empty older page with no duplicate ids
        val olderMessages = listOf(
            ChatMessage("user", "first message", rowId = "msg-1")
        )
        val page = ConversationResult("", olderMessages, false, false)
        val stateAfterMerge = ConversationPaging.mergeOlder(state, page)
        assertEquals(3, stateAfterMerge.messagesList.size)
        assertEquals("msg-1", stateAfterMerge.messagesList[0].rowId)
        assertEquals("msg-2", stateAfterMerge.messagesList[1].rowId)
        assertEquals("msg-3", stateAfterMerge.messagesList[2].rowId)

        // 3. Merge older page with duplicates (e.g. msg-2 is already in state)
        val duplicatedOlder = listOf(
            ChatMessage("user", "first message", rowId = "msg-1"),
            ChatMessage("user", "hello", rowId = "msg-2") // Duplicate
        )
        val pageWithDup = ConversationResult("", duplicatedOlder, false, false)
        val stateAfterMergeDup = ConversationPaging.mergeOlder(state, pageWithDup)
        assertEquals(3, stateAfterMergeDup.messagesList.size)
        assertEquals("msg-1", stateAfterMergeDup.messagesList[0].rowId)
        assertEquals("msg-2", stateAfterMergeDup.messagesList[1].rowId)
        assertEquals("msg-3", stateAfterMergeDup.messagesList[2].rowId)
    }
}
