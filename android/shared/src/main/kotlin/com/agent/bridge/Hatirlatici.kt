package com.agent.bridge

import org.json.JSONArray
import org.json.JSONObject

// Cihaz-yerel hatırlatma (21.10.2026). Köprü bildirim göndermiyor; uygulama
// yaklaşan hatırlatmaların listesini alıp her biri için Android alarmı kurar,
// vakti gelince ağa çıkmadan yerel bildirim atar. Liste iki yoldan gelir:
// uygulama açılışında GET /reminders, ya da köprünün liste değişince ADB ile
// ittiği broadcast (bkz. bridge/hatirlatici-adb.mjs). Burası Android'siz saf
// mantık; alarm/bildirim kurulumu app modülündeki `Hatirlaticilar`ın işi.

data class HatirlaticiKaydi(
    val noteId: String,
    val title: String,
    val summary: String,
    /** Epoch milisaniye. */
    val at: Long,
) {
    /** Zaman değişirse yeni kayıt sayılır: ertelenen hatırlatma yeniden çalar. */
    val anahtar: String get() = "$noteId@$at"
}

fun parseHatirlaticilar(array: JSONArray?): List<HatirlaticiKaydi> = buildList {
    if (array == null) return@buildList
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val noteId = item.optString("noteId")
        val at = item.optLong("at", 0L)
        if (noteId.isBlank() || at <= 0L) continue
        add(HatirlaticiKaydi(noteId, item.optString("title"), item.optString("summary"), at))
    }
}

fun List<HatirlaticiKaydi>.toJsonArray(): JSONArray = JSONArray().also { arr ->
    forEach {
        arr.put(JSONObject().put("noteId", it.noteId).put("title", it.title).put("summary", it.summary).put("at", it.at))
    }
}

suspend fun BridgeClient.hatirlaticilar(settings: BridgeSettings): List<HatirlaticiKaydi> =
    parseHatirlaticilar(getJson(settings, "/reminders").optJSONArray("reminders"))

data class HatirlaticiPlani(
    /** Gelecekte; alarmı kurulacak. */
    val kurulacak: List<HatirlaticiKaydi>,
    /** Vakti geçmiş ama bu cihazda gösterilmemiş; hemen gösterilecek. */
    val gosterilecek: List<HatirlaticiKaydi>,
    /** Kurulu ama artık listede olmayan (silinmiş/ertelenmiş) alarmlar. */
    val iptal: Set<String>,
)

const val KACMIS_HATIRLATICI_PENCERESI_MS = 3L * 24 * 60 * 60 * 1000

/**
 * [baslangic]: bu cihazda yerel hatırlatmanın ilk çalıştığı an. Ondan önceki
 * geçmiş kayıtlar eski sistemle zaten gösterilmişti; ilk senkronda hepsi birden
 * çalmasın diye kaçmış sayılmaz.
 */
fun hatirlaticiPlani(
    liste: List<HatirlaticiKaydi>,
    kurulu: Set<String>,
    gosterilmis: Set<String>,
    simdi: Long,
    baslangic: Long,
    kacmisPenceresi: Long = KACMIS_HATIRLATICI_PENCERESI_MS,
): HatirlaticiPlani {
    val bekleyen = liste.filter { it.anahtar !in gosterilmis }.distinctBy { it.anahtar }
    val kurulacak = bekleyen.filter { it.at > simdi }
    val gosterilecek = bekleyen.filter { it.at in (maxOf(baslangic, simdi - kacmisPenceresi) + 1)..simdi }
    val iptal = kurulu - kurulacak.map { it.anahtar }.toSet()
    return HatirlaticiPlani(kurulacak, gosterilecek, iptal)
}
