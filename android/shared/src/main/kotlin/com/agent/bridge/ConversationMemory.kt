package com.agent.bridge

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sekme geçişinde "boş ekran + bekleme" yerine son bilinen konuşmayı anında
 * göstermek için oturum başına bellek içi konuşma kopyası (kullanıcı isteği
 * 05.08.2026: "açık sekmeler yüklü dursa"). Disk cache'inden (ConversationCache)
 * farkı: senkron okunur, giriş akışının ilk update'inde kullanılabilir.
 *
 * Anahtar yalnız sessionId — oturum kimlikleri backend'ler arası tekil, ve aynı
 * oturum cowork/doğrudan iki ayrı yoldan açıldığında da tek kayıt kalır.
 *
 * Prefill edilen veri BAYATTIR: staleConversation bayrağı taze veri gelene
 * kadar açık kalır ki bayat görüntü bellek kaydının üstüne geri yazılmasın
 * (recordFrom bayrak açıkken kayıt atlar).
 */
class ConversationMemory(private val capacity: Int = 8) {
    data class Entry(val messages: List<ChatMessage>, val transcript: String)

    // accessOrder=true: get de tazeler; en eski DOKUNULAN kayıt düşer.
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun get(sessionId: String): Entry? = if (sessionId.isBlank()) null else entries[sessionId]

    @Synchronized
    fun put(sessionId: String, messages: List<ChatMessage>, transcript: String) {
        if (sessionId.isBlank() || messages.isEmpty()) return
        entries[sessionId] = Entry(messages, transcript)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    /** Silinen oturumların kaydı geri dönüşte parlamasın. */
    @Synchronized
    fun remove(sessionIds: Set<String>) {
        sessionIds.forEach { entries.remove(it) }
    }

    /**
     * Taze konuşma her uygulandığında (HTTP refresh/poll ve WS snapshot sonrası)
     * çağrılır. Bayat/çevrimdışı görüntü kayda geri yazılmaz.
     */
    fun recordFrom(state: RemoteUiState) {
        if (state.staleConversation || state.offlineConversation) return
        val key = offlineConversationKey(state) ?: return
        put(key.sessionId, state.messagesList, state.transcript)
    }
}

/**
 * Giriş yollarının prefill kapısı: önce bellek (senkron), ıskalarsa disk
 * cache'i (Room) arkada denenir — soğuk açılışta da son konuşma ağ beklemeden
 * görünsün (kullanıcı isteği 05.08.2026, ikinci adım).
 *
 * Disk sonucu YALNIZ şu koşullarda uygulanır: araya başka bir giriş girmemiş
 * (epoch), hâlâ beklenen backend'deyiz, ekran hâlâ boş ve bağlanan oturum (varsa)
 * beklenen oturum. Bu kapı olmasaydı yavaş bir disk okuması, kullanıcının bu
 * arada açtığı BAŞKA oturumun ekranına eski konuşmayı basabilirdi.
 */
class ConversationPrefiller(
    private val memory: ConversationMemory,
    private val cache: ConversationCache,
    private val scope: CoroutineScope,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var epoch = 0

    /**
     * [backendKey] disk cache'in bileşik anahtarı ("backend:provider" — kayıt
     * offlineConversationKey ile aynı kuralla yazılır). Bellek kaydı varsa döner;
     * yoksa null döner ve disk denemesi arkada başlar.
     */
    fun warm(backendKey: String, sessionId: String): ConversationMemory.Entry? {
        val myEpoch = ++epoch
        if (sessionId.isBlank()) return null
        memory.get(sessionId)?.let { return it }
        val expectedBackend = backendKey.substringBefore(':')
        scope.launch {
            val cached = withContext(ioDispatcher) {
                runCatching { cache.load(backendKey, sessionId) }.getOrNull()
            }
            if (cached == null || cached.messages.isEmpty()) return@launch
            update { current ->
                val boundSid = offlineConversationKey(current)?.sessionId
                val applicable = epoch == myEpoch &&
                    current.backend == expectedBackend &&
                    current.messagesList.isEmpty() &&
                    !current.staleConversation &&
                    (boundSid == null || boundSid == sessionId)
                if (!applicable) current
                else current.copy(
                    messagesList = cached.messages,
                    transcript = cached.transcript,
                    staleConversation = true,
                )
            }
        }
        return null
    }
}

/**
 * SessionStreamManager'ın soket geçişinde kenara koyduğu (lastSeq + satırlar)
 * durağı. Aynı oturuma dönüşte `since=lastSeq` ile bağlanılır ve köprü yalnız
 * kaçan deltaları yollar (delta ring; yetmezse sunucu kendiliğinden tam
 * snapshot'a düşer — davranış bozulmaz).
 */
internal class StreamSessionStash<T>(private val capacity: Int = 8) {
    private val entries = LinkedHashMap<String, T>(16, 0.75f, true)

    /** Kaydı alır ve DÜŞÜRÜR: canlı akış listeyi mutasyonla sürdürecek. */
    fun take(key: String): T? = entries.remove(key)

    fun put(key: String, value: T) {
        if (key.isBlank()) return
        entries[key] = value
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun remove(keys: Set<String>) {
        keys.forEach { entries.remove(it) }
    }
}
