package com.agent.bridge

/**
 * Sohbet içi arama eşleşme hesabı — saf ve Android bağımsız (test edilebilir).
 *
 * Eşleşmeler tüm YÜKLÜ geçmiş üzerinden hesaplanır (plan §16). Blank rowId'ler için
 * ChatRootScreen'in kaydırma/vurgu mantığıyla aynı `idx_<index>` kimliği kullanılır.
 */
object ChatSearchLogic {
    data class Match(val matchRowIds: List<String>, val selectedIndex: Int)

    /**
     * @param preferredIndex Deep-link'ten gelen matchOrdinal. Geçerli bir eşleşme
     *   indeksiyse o eşleşme seçilir (doğru mesaja kaydırma); değilse ilk eşleşme.
     */
    fun computeMatch(messages: List<ChatMessage>, query: String, preferredIndex: Int): Match {
        if (query.length < 2) return Match(emptyList(), -1)
        val matchIds = messages.mapIndexedNotNull { index, m ->
            if (m.role in listOf("user", "agent") && m.text.contains(query, ignoreCase = true))
                m.rowId.ifBlank { "idx_$index" } else null
        }
        val selected = when {
            matchIds.isEmpty() -> -1
            preferredIndex in matchIds.indices -> preferredIndex
            else -> 0
        }
        return Match(matchIds, selected)
    }
}
