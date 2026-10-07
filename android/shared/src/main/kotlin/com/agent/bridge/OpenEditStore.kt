package com.agent.bridge

// Cowork "aç-düzenle-geri-yaz" takip kaydı: telefonda harici uygulamada açılan
// dosyanın PC'deki kaynağı + yerel kopya + açılış anındaki baseline (mtime/size).
// syncBack baseline'dan sapmayı "düzenlendi" sayar ve PC'ye geri yazar.
data class OpenEditRecord(
    val remotePath: String,
    val localPath: String,
    val baselineMtime: Long,
    val baselineSize: Long,
    val baselineHash: String = "",
    val openedAt: Long,
    // Eski kayıtlar Cowork akışından geldiği için migration varsayılanı true.
    // false olduğunda genel PC gezginindeki /savefile endpoint'i kullanılır.
    val coworkOnly: Boolean = true,
)

// Kayıt deposu arayüzü — SharedPreferences impl'i üretimde (Android), in-memory
// fake testte, JSONL dosyası desktop'ta. Süreç ölümüne dayanıklılık şart: harici
// editör singleTask aktiviteyi öldürebilir; relaunch'taki ON_START → syncBack
// kayıtları buradan okur. Plan 4. madde: platform arayüzü.
interface OpenEditStore {
    fun put(record: OpenEditRecord)
    fun all(): List<OpenEditRecord>
    fun remove(remotePath: String)
}
