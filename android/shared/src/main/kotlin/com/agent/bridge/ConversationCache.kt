package com.agent.bridge

/**
 * Konuşma cache'i için platform arayüzü (plan 4. madde).
 *
 * - Android gerçeklemesi: Room (OfflineConversationCache.kt — app modülünde)
 * - Desktop gerçeklemesi (Faz 2): JSONL dosyaları (~/.agentbridge/cache/)
 *
 * Delegate'ler (ConversationDelegate vb.) yalnız bu arayüzü bilir; gerçekleştirme
 * RemoteStore/RemoteViewModel oluşturulurken enjekte edilir.
 */
interface ConversationCache {
    suspend fun load(backend: String, sessionId: String): OfflineConversation?
    suspend fun save(backend: String, sessionId: String, result: ConversationResult)

    /**
     * Silinen oturumların cache satırlarını kaldırır.
     *
     * Cache'te yalnız `find`/`upsert` vardı: oturum köprüden ve diskten silinse
     * bile konuşma telefonda kalmaya devam ediyordu. Ölçüldü — 1 Ağu 2026'da
     * silinen bir oturumun metni telefondaki `offline-conversations.db` içinde
     * duruyordu (4,4 MB dosyada 10 eşleşme).
     *
     * `backend` sütununa bakılmaz: aynı oturum "cowork:claude-app" gibi bileşik
     * anahtarla da yazılmış olabilir, oturum kimliği zaten tekil.
     */
    suspend fun delete(sessionIds: Set<String>)
}
