package com.agent.bridge

import org.json.JSONArray
import org.json.JSONObject

const val PROMPT_QUEUE_PREF_KEY = "prompt_queue_v1"

private fun RemoteUiState.activeQueueProvider(): String =
    if (backend == Backend.COWORK.id) normalizeCoworkProvider(cowork.provider) else ""

private fun RemoteUiState.activeQueueSessionId(): String = when (backend) {
    Backend.AGY.id -> agy.sessionId
    Backend.CLAUDE_APP.id -> claude.sessionId
    Backend.CODEX_APP.id -> codex.sessionId
    Backend.OPENCODE2_APP.id -> opencode.sessionId
    Backend.COWORK.id -> when (activeQueueProvider()) {
        Backend.CODEX_APP.id -> codex.sessionId
        Backend.OPENCODE2_APP.id -> opencode.sessionId
        else -> claude.sessionId
    }
    else -> ""
}

fun QueuedPrompt.belongsToActiveSession(state: RemoteUiState): Boolean {
    if (bridgeProfileId != state.activeBridgeProfileId) return false
    if (tabId.isNotBlank() && tabId == state.activeTabId) return true
    return backend == state.backend.orEmpty() &&
        provider == state.activeQueueProvider() &&
        sessionId.isNotBlank() &&
        sessionId == state.activeQueueSessionId()
}

fun RemoteUiState.activeQueuedPrompts(): List<QueuedPrompt> =
    promptQueue.filter { it.belongsToActiveSession(this) }

/** Sekme kapatmak o sekmenin henüz gönderilmemiş promptlarını da iptal eder. */
fun discardClosedTabQueuedPrompts(state: RemoteUiState): RemoteUiState {
    val openKeys = state.openTabs.mapTo(mutableSetOf()) { it.bridgeProfileId to it.id }
    val kept = state.promptQueue.filter {
        it.tabId.isBlank() || (it.bridgeProfileId to it.tabId) in openKeys
    }
    return if (kept.size == state.promptQueue.size) state else state.copy(promptQueue = kept)
}

/**
 * Composer taslağını hedef sekme/oturum kimliğiyle kuyruğa taşır. Yalnız ek
 * içeren prompt da geçerlidir; metin ve ekler atomik biçimde birlikte temizlenir.
 */
fun enqueueCurrentPrompt(state: RemoteUiState, id: String): RemoteUiState {
    val text = state.input.trim()
    if (text.isBlank() && state.attachments.isEmpty()) return state
    val queued = QueuedPrompt(
        id = id,
        text = text,
        attachments = state.attachments,
        bridgeProfileId = state.activeBridgeProfileId,
        tabId = state.activeTabId,
        backend = state.backend.orEmpty(),
        provider = state.activeQueueProvider(),
        sessionId = state.activeQueueSessionId(),
    )
    return state.copy(
        promptQueue = state.promptQueue + queued,
        input = "",
        attachments = emptyList(),
    )
}

/**
 * Yalnız aktif sekmeye ait ilk prompt'u composer'a geri taşır. Başka sekmenin
 * kuyruğu, aktif sekmenin turu bitti diye yanlış sohbete gönderilmez.
 */
fun restoreNextQueuedPrompt(state: RemoteUiState): RemoteUiState {
    if (state.running || state.input.isNotBlank() || state.attachments.isNotEmpty()) return state
    val next = state.activeQueuedPrompts().firstOrNull() ?: return state
    return state.copy(
        promptQueue = state.promptQueue.filterNot { it.id == next.id },
        input = next.text,
        attachments = next.attachments,
    )
}

/** SharedPreferences için sürümlü, bozuk girdide boş listeye düşen JSON codec. */
object PromptQueueJson {
    fun encode(items: List<QueuedPrompt>): String {
        val array = JSONArray()
        items.forEach { item ->
            // Ek biçimi taslakla ORTAK (ChatAttachmentJson): iki kopya zamanla sapardı.
            val attachments = ChatAttachmentJson.encode(item.attachments)
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("text", item.text)
                    .put("attachments", attachments)
                    .put("bridgeProfileId", item.bridgeProfileId)
                    .put("tabId", item.tabId)
                    .put("backend", item.backend)
                    .put("provider", item.provider)
                    .put("sessionId", item.sessionId)
            )
        }
        return JSONObject().put("version", 1).put("items", array).toString()
    }

    fun decode(raw: String?): List<QueuedPrompt> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("version") != 1) return@runCatching emptyList()
            val array = root.optJSONArray("items") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    val attachments = ChatAttachmentJson.decode(item.optJSONArray("attachments"))
                    add(
                        QueuedPrompt(
                            id = id,
                            text = item.optString("text"),
                            attachments = attachments,
                            bridgeProfileId = item.optString("bridgeProfileId"),
                            tabId = item.optString("tabId"),
                            backend = item.optString("backend"),
                            provider = item.optString("provider"),
                            sessionId = item.optString("sessionId"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
