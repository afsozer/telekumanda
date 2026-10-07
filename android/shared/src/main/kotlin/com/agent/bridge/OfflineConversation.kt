package com.agent.bridge

import org.json.JSONArray
import org.json.JSONObject

// Room cache'inden bağımsız tipler ve saf codec/fonksiyonlar. Gerçek Room DB
// (OfflineConversationCache, Dao, Entity) Android app modülünde kalır (Faz 1
// karar: "Room app'te kalır"). Desktop gerçeklemesi JSONL ile olacak (Faz 2).

data class OfflineConversation(val transcript: String, val messages: List<ChatMessage>)
internal data class OfflineConversationKey(val backend: String, val sessionId: String)

internal fun offlineConversationKey(state: RemoteUiState): OfflineConversationKey? {
    val backend = state.backend ?: return null
    val provider = if (backend == "cowork") normalizeCoworkProvider(state.coworkProvider) else backend
    val sessionId = when (provider) {
        "agy" -> state.agySessionId
        "claude-app" -> state.claudeAppSessionId
        "codex-app" -> state.codexAppSessionId
        "opencode2-app" -> state.opencode.sessionId
        "omp" -> state.ompSessionId
        else -> ""
    }
    return sessionId.takeIf(String::isNotBlank)?.let { OfflineConversationKey("$backend:$provider", it) }
}

/** Cache satırındaki mesaj dizisini taşınabilir JSON'a çevirir; ağ modeli değişse de cache kırılmaz. */
object OfflineConversationCodec {
    fun encode(messages: List<ChatMessage>): String = JSONArray().apply {
        messages.forEach { message -> put(JSONObject().apply {
            put("role", message.role)
            put("text", message.text)
            put("time", message.time)
            put("thoughtIndex", message.thoughtIndex)
            put("rowId", message.rowId)
        }) }
    }.toString()

    fun decode(raw: String): List<ChatMessage> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(ChatMessage(
                    role = item.optString("role"),
                    text = item.optString("text"),
                    time = item.optString("time"),
                    thoughtIndex = item.optInt("thoughtIndex", -1),
                    rowId = item.optString("rowId"),
                ))
            }
        }
    }.getOrDefault(emptyList())
}
