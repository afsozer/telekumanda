package com.agent.bridge

import org.json.JSONObject

/**
 * Belge başına "kaldığı yer" kaydı — saf (Android'siz) model.
 *
 * [page] her zaman doludur (sayfa görünümü karşılığı, 1'den başlar); [ri]/[ro]
 * yalnız [mode] "r" iken anlamlıdır ve LazyListState'in ham
 * (uyarı şeridi dahil) index/offset çiftidir — kaydeden taraf ne hesapladıysa
 * o, geri yükleyen ek offset matematiği yapmaz.
 */
data class PdfResumePosition(
    val page: Int,
    val ri: Int = 0,
    val ro: Int = 0,
    val mode: String = "p",
    val savedAt: Long = 0L,
)

/** Tek bir SharedPreferences anahtarında tutulan haritanın tavanı. */
internal const val PDF_RESUME_MAX_ENTRIES = 100

/**
 * JSON nesnesini konum haritasına çözer. Anahtar normalize belge yoludur.
 * Bozuk/eksik girdi sessizce atlanır — tek kötü kayıt yüzünden tüm geçmişi
 * kaybetmek konum hatırlamanın amacına aykırı.
 */
fun decodePdfResumeMap(json: String): Map<String, PdfResumePosition> {
    if (json.isBlank()) return emptyMap()
    return runCatching {
        val obj = JSONObject(json)
        buildMap {
            obj.keys().forEach { key ->
                val entry = obj.optJSONObject(key) ?: return@forEach
                put(
                    key,
                    PdfResumePosition(
                        page = entry.optInt("page", 1),
                        ri = entry.optInt("ri", 0),
                        ro = entry.optInt("ro", 0),
                        mode = entry.optString("m", "p"),
                        savedAt = entry.optLong("t", 0L),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())
}

/** Konum haritasını tek bir JSON nesnesine kodlar. */
fun encodePdfResumeMap(map: Map<String, PdfResumePosition>): String {
    val obj = JSONObject()
    map.forEach { (key, pos) ->
        obj.put(
            key,
            JSONObject().apply {
                put("page", pos.page)
                put("ri", pos.ri)
                put("ro", pos.ro)
                put("m", pos.mode)
                put("t", pos.savedAt)
            },
        )
    }
    return obj.toString()
}

/**
 * Bir girdiyi ekler/günceller; harita [maxEntries]'i aşarsa en eski (küçük
 * [PdfResumePosition.savedAt]) kayıtlar atılır.
 *
 * Neden LRU: konum kaydı belge başına büyür ve süresiz saklanırsa yıllar
 * içinde açılan her PDF için tek satırlık ama sonsuz bir liste birikir.
 */
fun putPdfResumePosition(
    map: Map<String, PdfResumePosition>,
    key: String,
    position: PdfResumePosition,
    maxEntries: Int = PDF_RESUME_MAX_ENTRIES,
): Map<String, PdfResumePosition> {
    val updated = map + (key to position)
    if (updated.size <= maxEntries) return updated
    return updated.entries
        .sortedByDescending { it.value.savedAt }
        .take(maxEntries)
        .associate { it.key to it.value }
}
