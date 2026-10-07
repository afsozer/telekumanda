package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Sekme geçişi prefill belleği (kullanıcı isteği 05.08.2026: geçişte boş ekran +
// bekleme yerine son bilinen konuşma anında görünsün, taze veri arkadan gelsin).
class ConversationMemoryTest {

    private fun msgs(vararg texts: String) = texts.map { ChatMessage(role = "agent", text = it) }

    @Test
    fun kayitVeGeriOkuma() {
        val memory = ConversationMemory()
        memory.put("s1", msgs("merhaba"), "t1")
        assertEquals("merhaba", memory.get("s1")?.messages?.single()?.text)
        assertEquals("t1", memory.get("s1")?.transcript)
    }

    @Test
    fun bosKimlikVeBosListeKaydedilmez() {
        val memory = ConversationMemory()
        memory.put("", msgs("x"), "t")
        memory.put("s1", emptyList(), "t")
        assertNull(memory.get(""))
        assertNull(memory.get("s1"))
    }

    @Test
    fun kapasiteAsimindaEnEskiDokunulanDuser() {
        val memory = ConversationMemory(capacity = 2)
        memory.put("a", msgs("a"), "")
        memory.put("b", msgs("b"), "")
        memory.get("a") // a'ya dokunuldu; artık en eski b
        memory.put("c", msgs("c"), "")
        assertNull(memory.get("b"))
        assertEquals("a", memory.get("a")?.messages?.single()?.text)
        assertEquals("c", memory.get("c")?.messages?.single()?.text)
    }

    @Test
    fun silinenOturumGeriDonusteParlamaz() {
        val memory = ConversationMemory()
        memory.put("s1", msgs("x"), "")
        memory.remove(setOf("s1", "yok"))
        assertNull(memory.get("s1"))
    }

    @Test
    fun recordFromTazeKonusmayiYazar() {
        val memory = ConversationMemory()
        val state = RemoteUiState().copy(
            backend = "claude-app",
            claude = ClaudeUiState(sessionId = "s1"),
            messagesList = msgs("taze"),
            transcript = "t",
        )
        memory.recordFrom(state)
        assertEquals("taze", memory.get("s1")?.messages?.single()?.text)
    }

    // Kritik döngü koruması: prefill edilen BAYAT görüntü kayda geri yazılsaydı
    // araya gelen taze mesajlar bir sonraki geçişte kaybolmuş görünürdü.
    @Test
    fun recordFromBayatVeCevrimdisiGoruntuyuYazmaz() {
        val memory = ConversationMemory()
        memory.put("s1", msgs("eski"), "")
        val base = RemoteUiState().copy(
            backend = "claude-app",
            claude = ClaudeUiState(sessionId = "s1"),
            messagesList = msgs("prefill-kopyasi"),
        )
        memory.recordFrom(base.copy(staleConversation = true))
        memory.recordFrom(base.copy(offlineConversation = true))
        assertEquals("eski", memory.get("s1")?.messages?.single()?.text)
    }

    @Test
    fun recordFromOturumsuzDurumdaSessizGecer() {
        val memory = ConversationMemory()
        memory.recordFrom(RemoteUiState().copy(backend = "claude-app", messagesList = msgs("x")))
        assertNull(memory.get(""))
    }

    // Cowork'te anahtar yalnız sessionId: aynı oturum cowork'ten de doğrudan da
    // açılsa tek kayıt üzerinden prefill edilir.
    @Test
    fun coworkVeDogrudanAcilisAyniKaydiPaylasir() {
        val memory = ConversationMemory()
        val cowork = RemoteUiState().copy(
            backend = "cowork",
            cowork = CoworkUiState(provider = "claude-app"),
            claude = ClaudeUiState(sessionId = "s1"),
            messagesList = msgs("cowork-turu"),
        )
        memory.recordFrom(cowork)
        assertEquals("cowork-turu", memory.get("s1")?.messages?.single()?.text)
    }
}

// Soğuk açılış disk fallback'i: bellek ıskalarsa Room kaydı arkada yüklenir,
// ama YALNIZ hâlâ aynı hedefe bakılıyorsa ve ekran hâlâ boşsa uygulanır.
class ConversationPrefillerTest {

    private class FakeCache(var stored: OfflineConversation? = null) : ConversationCache {
        var loadCount = 0
        override suspend fun load(backend: String, sessionId: String): OfflineConversation? {
            loadCount++
            return stored
        }
        override suspend fun save(backend: String, sessionId: String, result: ConversationResult) {}
        override suspend fun delete(sessionIds: Set<String>) {}
    }

    private fun msgs(vararg texts: String) = texts.map { ChatMessage(role = "agent", text = it) }

    private class Harness(
        memoryEntry: Pair<String, List<ChatMessage>>? = null,
        diskEntry: OfflineConversation? = null,
        initial: RemoteUiState = RemoteUiState().copy(backend = "claude-app"),
    ) {
        val memory = ConversationMemory().apply { memoryEntry?.let { put(it.first, it.second, "") } }
        val cache = FakeCache(diskEntry)
        var state = initial
        val prefiller = ConversationPrefiller(
            memory = memory,
            cache = cache,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            update = { reducer -> state = reducer(state) },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
    }

    @Test
    fun bellekVarkenDiskeGidilmez() {
        val h = Harness(memoryEntry = "s1" to msgs("bellek"))
        val warm = h.prefiller.warm("claude-app:claude-app", "s1")
        assertEquals("bellek", warm?.messages?.single()?.text)
        assertEquals(0, h.cache.loadCount)
    }

    @Test
    fun bellekIskalarsaDisktenPrefillEdilir() {
        val h = Harness(diskEntry = OfflineConversation("t", msgs("diskten")))
        assertNull(h.prefiller.warm("claude-app:claude-app", "s1"))
        assertEquals("diskten", h.state.messagesList.single().text)
        assertEquals("t", h.state.transcript)
        assertTrue(h.state.staleConversation)
    }

    @Test
    fun tazeVeriGelmisseDiskSonucuUygulanmaz() {
        val h = Harness(diskEntry = OfflineConversation("t", msgs("bayat")))
        // Unconfined'da launch warm dönmeden koşar; "taze veri geldi" durumunu
        // başlangıç state'ine koyarak temsil ediyoruz.
        h.state = h.state.copy(messagesList = msgs("taze"))
        h.prefiller.warm("claude-app:claude-app", "s1")
        assertEquals("taze", h.state.messagesList.single().text)
        assertFalse(h.state.staleConversation)
    }

    @Test
    fun baskaBackendeGecilmisseUygulanmaz() {
        val h = Harness(diskEntry = OfflineConversation("t", msgs("bayat")), initial = RemoteUiState())
        h.prefiller.warm("claude-app:claude-app", "s1")
        assertTrue(h.state.messagesList.isEmpty())
    }

    @Test
    fun baskaOturumBaglanmissaUygulanmaz() {
        val h = Harness(
            diskEntry = OfflineConversation("t", msgs("bayat")),
            initial = RemoteUiState().copy(backend = "claude-app", claude = ClaudeUiState(sessionId = "BASKA")),
        )
        h.prefiller.warm("claude-app:claude-app", "s1")
        assertTrue(h.state.messagesList.isEmpty())
    }

    @Test
    fun bosOturumKimligiDiskeGitmez() {
        val h = Harness(diskEntry = OfflineConversation("t", msgs("x")))
        assertNull(h.prefiller.warm("claude-app:claude-app", ""))
        assertEquals(0, h.cache.loadCount)
    }
}

class StreamSessionStashTest {

    @Test
    fun takeKaydiAlirVeDusurur() {
        val stash = StreamSessionStash<String>()
        stash.put("claude-app:s1", "v")
        assertEquals("v", stash.take("claude-app:s1"))
        assertNull(stash.take("claude-app:s1"))
    }

    @Test
    fun kapasiteAsimindaEnEskiDuser() {
        val stash = StreamSessionStash<String>(capacity = 2)
        stash.put("a", "1")
        stash.put("b", "2")
        stash.put("c", "3")
        assertNull(stash.take("a"))
        assertEquals("2", stash.take("b"))
        assertEquals("3", stash.take("c"))
    }

    @Test
    fun bosAnahtarYokSayilir() {
        val stash = StreamSessionStash<String>()
        stash.put("", "v")
        assertNull(stash.take(""))
    }
}
