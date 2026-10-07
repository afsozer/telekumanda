package com.agent.bridge

object ConversationPaging {
    private const val PageStep = 100

    data class Target(val provider: String, val sessionId: String, val beforeRowId: String)

    fun target(state: RemoteUiState): Target? {
        val provider = when (state.backend) {
            "claude-app" -> "claude-app"
            "codex-app" -> "codex-app"
            // cowork sağlayıcısı normalize edilmeli: boş/"claude" gibi değerler
            // kodun geri kalanında (poll/refresh/socket) claude-app'e eşlenir, ama
            // burada ham okunuyordu → provider "" olup target null dönüyor, "Daha
            // eskiyi göster" tuşu cowork oturumlarında hiç çıkmıyordu.
            "cowork" -> normalizeCoworkProvider(state.coworkProvider)
            else -> ""
        }
        val sessionId = when (provider) {
            "claude-app" -> state.claudeAppSessionId
            "codex-app" -> state.codexAppSessionId
            else -> ""
        }
        val before = state.messagesList.firstOrNull()?.rowId.orEmpty()
        if (provider !in setOf("claude-app", "codex-app")) return null
        if (sessionId.isBlank() || before.isBlank()) return null
        return Target(provider, sessionId, before)
    }

    fun growLocalPage(state: RemoteUiState): RemoteUiState =
        state.copy(messagePageSize = (state.messagePageSize + PageStep).coerceAtMost(state.messagesList.size.coerceAtLeast(PageStep)))

    /**
     * Sunucudan gelen KUYRUK penceresini yüklü listeye uygular — düz değiştirme
     * DEĞİL. Sunucu penceresi, sayfalanarak büyütülmüş listeden kısa olabilir:
     * HTTP conversation ucu her isteği 500 satıra kırpar (bridge
     * agent-session-core paginateMessages), WS snapshot da yalnız canlı
     * pencereyi taşır. Düz değiştirme, aramanın yüklediği geçmişi 2,5 sn'lik
     * poll'da 500 satıra ÇÖKERTIYORDU: kullanıcı bulunan eski mesajdan kalan
     * listenin en eskisine sıkışıyor, yukarısı diye bir şey kalmıyordu
     * (canlı yaşandı 06.08.2026, "sınır" vakası).
     *
     * Kural: gelenin İLK satırı mevcutta varsa o noktadan sonrası gelenle
     * değiştirilir (akış güncellemeleri ve geri sarma dahil sunucu gerçeği),
     * öncesi korunur. İlk satır mevcutta yoksa liste ayrışmıştır
     * (rewind/clear/yeni oturum) — gelen aynen kullanılır.
     */
    fun mergeTailWindow(current: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
        if (incoming.isEmpty()) return current
        if (current.isEmpty()) return incoming
        val firstId = incoming.first().rowId
        if (firstId.isBlank()) return incoming
        val anchor = current.indexOfFirst { it.rowId == firstId }
        if (anchor <= 0) return incoming
        return current.subList(0, anchor) + incoming
    }

    fun mergeOlder(state: RemoteUiState, page: ConversationResult): RemoteUiState {
        val seen = state.messagesList.mapNotNull { it.rowId.takeIf(String::isNotBlank) }.toMutableSet()
        val older = page.messages.filter { msg ->
            val id = msg.rowId
            id.isBlank() || seen.add(id)
        }
        // Boş sayfa ya da yalnız mükerrer satırlar: geçmiş tükendi. messagePageSize'ı
        // liste boyunun üstüne çekmek "daha eski var" göstergesini kapatır — UI,
        // "Daha eskiyi göster" tuşunu size >= messagePageSize iken gösterir.
        if (older.isEmpty()) return state.copy(messagePageSize = state.messagesList.size + PageStep)
        return state.copy(
            messagesList = older + state.messagesList,
            messagePageSize = (state.messagePageSize + older.size).coerceAtLeast(PageStep),
        )
    }
}
