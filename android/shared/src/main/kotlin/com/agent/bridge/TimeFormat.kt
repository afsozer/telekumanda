package com.agent.bridge

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bridge'den gelen ham ISO-8601 zaman damgalarını (ör.
 * "2026-07-22T21:10:00.438171+00:00" veya "...Z") kullanıcının yerel saatine
 * çevirip "22.07.2026 21:10" biçiminde döndürür. Kullanıcı şikayeti: mikrosaniye
 * + saat dilimi kuyruğu ekranda "garip şeyler" olarak akıyordu.
 *
 * Ayrıştırılamayan girdi (boş, zaten biçimli, serbest metin) OLDUĞU GİBİ döner —
 * asla exception sızmaz, en kötü ihtimalle eski görünüm korunur.
 *
 * Android ve masaüstünde AYNI kopya iki kez duruyordu; 06.08.2026'da buraya
 * alındı. Biçimi değiştireceksen tek yer burası.
 */
fun formatIsoTime(iso: String): String {
    if (iso.isBlank()) return iso
    return runCatching {
        OffsetDateTime.parse(iso)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    }.getOrDefault(iso)
}
