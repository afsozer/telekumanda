package com.agent.bridge.ui2.chat

import com.agent.bridge.ChatDisplayRow
import com.agent.bridge.ChatMessage

/**
 * Sohbette kaydırılan yerin oturum başına hatırlanması (kullanıcı isteği
 * 05.08.2026: "yan sessiondan veya merkezden geri geldiğimde kaydırdığım yeri
 * hatırlasın").
 *
 * Neden ham lazy index'i saklamıyoruz: liste `reverseLayout` ve index 0 = EN
 * YENİ mesaj. Sen Merkez'e bakarken ajan üç mesaj daha yazarsa bütün index'ler
 * üçer kayar ve eski index bambaşka bir satırı gösterir. Bu yüzden çapa olarak
 * satırın KİMLİĞİ (rowId) saklanır, dönüşte kimlik yeniden index'e çevrilir.
 *
 * Bellek Ui2Root'ta yaşar: NavHost hedefi değişince ChatRootScreen bileşimden
 * düşüyor, ona bağlı bir `remember` hatırlamaya yetmiyordu.
 */
class ChatScrollMemory {
    private val anchors = HashMap<String, ChatScrollAnchor>()

    /** null = o oturumda dipteydik; dönüşte yine dibe (takip moduna) inilir. */
    fun anchor(sessionKey: String): ChatScrollAnchor? = anchors[sessionKey]

    fun remember(sessionKey: String, anchor: ChatScrollAnchor?) {
        if (sessionKey.isBlank()) return
        if (anchor == null) anchors.remove(sessionKey) else anchors[sessionKey] = anchor
    }
}

data class ChatScrollAnchor(val rowId: String, val offset: Int)

/**
 * Mesaj satırının anahtarı — lazy `key` lambda'sıyla AYNI kural (rowId boşsa
 * kronolojik index'ten türetilir), yoksa çapa dönüşte eşleşmez.
 */
fun chatRowKey(messages: List<ChatMessage>, messageIndex: Int): String {
    val m = messages.getOrNull(messageIndex) ?: return ""
    return m.rowId.ifBlank { "idx_$messageIndex" }
}

/**
 * Görünen ilk lazy öğesinden çapa kimliği.
 *
 * [tailExtra] mesaj satırlarından ÖNCE emit edilen dip kartlarının sayısı
 * (onay/soru/plan/bekleme); lazy index onlarla kayar. Dip kartının kendisi
 * çapa olmaz (geçicidirler) — null döner.
 */
fun chatAnchorRowId(
    messages: List<ChatMessage>,
    displayRows: List<ChatDisplayRow>,
    lazyIndex: Int,
    tailExtra: Int,
): String? {
    val pos = lazyIndex - tailExtra
    if (pos < 0 || pos >= displayRows.size) return null
    val row = displayRows[displayRows.size - 1 - pos]
    val messageIndex = when (row) {
        is ChatDisplayRow.Single -> row.index
        is ChatDisplayRow.ToolGroup -> row.indices.first()
    }
    return chatRowKey(messages, messageIndex).takeIf { it.isNotBlank() }
}

/** Çapa kimliğinden lazy index; satır artık listede yoksa null. */
fun chatLazyIndexForRowId(
    messages: List<ChatMessage>,
    displayRows: List<ChatDisplayRow>,
    rowId: String,
    tailExtra: Int,
): Int? {
    if (rowId.isBlank()) return null
    val messageIndex = messages.indices.firstOrNull { chatRowKey(messages, it) == rowId } ?: return null
    val dispIndex = displayRows.indexOfFirst { row ->
        when (row) {
            is ChatDisplayRow.Single -> row.index == messageIndex
            is ChatDisplayRow.ToolGroup -> messageIndex in row.indices
        }
    }
    if (dispIndex < 0) return null
    return tailExtra + (displayRows.size - 1 - dispIndex)
}
