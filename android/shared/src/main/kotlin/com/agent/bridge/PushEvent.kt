package com.agent.bridge

import org.json.JSONArray

data class PushEvent(
    val deliveryId: String,
    val kind: String,
    val title: String = "",
    val summary: String = "",
    val noteId: String = "",
    val backend: String = "",
    val backendLabel: String = "",
    val sessionId: String = "",
    // Turun basladigi an, ISO 8601 metin (kopru `now().toISOString()` gonderiyor,
    // `bridge/operations.mjs:98`). Kapsulun kronometresi bunu ms'ye cevirir
    // (`kapsulZamani`). Sayi degil: tasima her extra'yi `--es` ile metin yolluyor.
    val startedAt: String = "",
)

fun parsePushEvents(array: JSONArray?): List<PushEvent> = buildList {
    if (array == null) return@buildList
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val id = item.optString("deliveryId")
        val kind = item.optString("kind")
        // "started" 16.09.2026'da eklendi: kapsulu acan/guncelleyen olay
        // (`bridge/server.mjs` OPERATION_PUSH_KINDS). Golgeye satir ATMAZ,
        // yalniz kapsulu gunceller - o karar `PushNotifications`'ta.
        if (id.isBlank() || kind !in setOf("started", "attention", "completed", "failed", "system", "reminder", "note")) continue
        add(PushEvent(id, kind, item.optString("title"), item.optString("summary"),
            item.optString("noteId"), item.optString("backend"),
            item.optString("backendLabel"), item.optString("sessionId"),
            item.optString("startedAt")))
    }
}
