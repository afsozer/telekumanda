package com.agent.bridge.ui2.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// Hatırlatıcı seçimi ve biçimlendirme (docs/ekran-goruntusu-hatirlatici-plani.md,
// E5.3). Yeni bir sheet bileşeni EKLENMEDİ: hızlı seçenekler mevcut
// SelectorSheet üzerinde yaşıyor (anayasa §5 — sete girmemiş bileşen icat etme),
// yalnız "Özel tarih" dalında Material3 takvim/saat diyaloğu açılıyor.
//
// Saat dilimi sabit +03:00: Türkiye 2016'dan beri DST kullanmıyor ve bridge
// frontmatter'a hep bu offset'i yazıyor. Cihaz saat dilimi başka olsa bile
// hatırlatıcı Türkiye saatiyle kurulur — kullanıcı avukat, duruşma saati yerel.
private const val TZ_ID = "Europe/Istanbul"

private val TR = Locale("tr", "TR")
private val AYLAR = arrayOf(
    "Oca", "Şub", "Mar", "Nis", "May", "Haz", "Tem", "Ağu", "Eyl", "Eki", "Kas", "Ara",
)

private fun cal(): Calendar = Calendar.getInstance(TimeZone.getTimeZone(TZ_ID), TR)

private fun Calendar.atTime(hour: Int, minute: Int): Calendar = (clone() as Calendar).apply {
    set(Calendar.HOUR_OF_DAY, hour)
    set(Calendar.MINUTE, minute)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

private fun Calendar.plusDays(days: Int): Calendar =
    (clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, days) }

// `2026-08-12T14:00:00+03:00` — bridge'in beklediği biçim.
fun reminderIso(c: Calendar): String = String.format(
    Locale.US,
    "%04d-%02d-%02dT%02d:%02d:%02d+03:00",
    c.get(Calendar.YEAR),
    c.get(Calendar.MONTH) + 1,
    c.get(Calendar.DAY_OF_MONTH),
    c.get(Calendar.HOUR_OF_DAY),
    c.get(Calendar.MINUTE),
    c.get(Calendar.SECOND),
)

// Rozet/satır metni: bugün ve yarın gün adı yerine kelimeyle, diğerleri
// "12 Ağu 14:00". Yıl farklıysa yıl da yazılır.
fun formatReminder(iso: String?): String {
    val c = parseReminder(iso) ?: return ""
    val now = cal()
    val sameDay = { a: Calendar, b: Calendar ->
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
    val saat = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    return when {
        sameDay(c, now) -> "bugün $saat"
        sameDay(c, now.plusDays(1)) -> "yarın $saat"
        c.get(Calendar.YEAR) != now.get(Calendar.YEAR) ->
            "${c.get(Calendar.DAY_OF_MONTH)} ${AYLAR[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)} $saat"
        else -> "${c.get(Calendar.DAY_OF_MONTH)} ${AYLAR[c.get(Calendar.MONTH)]} $saat"
    }
}

// ISO'yu ayrıştırır. Elle düzenlenmiş notlarda offset eksik/bozuk olabilir;
// o durumda null döner ve arayüz hatırlatıcı yokmuş gibi davranır (çökmez).
fun parseReminder(iso: String?): Calendar? {
    val s = iso?.trim().orEmpty()
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2})(?::(\d{2}))?""").find(s) ?: return null
    val (y, mo, d, h, mi) = m.destructured.toList().take(5).map { it.toInt() }
    val sec = m.groupValues.getOrNull(6)?.toIntOrNull() ?: 0
    return cal().apply {
        clear()
        set(y, mo - 1, d, h, mi, sec)
    }
}

// Hızlı seçenekler; değer = ISO string. Özel tarih ve kaldırma sentinel değerler.
const val REMINDER_CUSTOM = "__ozel__"
const val REMINDER_CLEAR = "__kaldir__"

// current dolu ise listenin başına "Kaldır" gelir: kurulu hatırlatıcıyı silmek
// için ayrı bir düğmeye gerek kalmıyor, aynı sheet iki işi de görüyor.
fun reminderQuickOptions(current: String? = null): List<SelectorOption<String>> {
    val now = cal()
    val out = mutableListOf<SelectorOption<String>>()
    val currentText = formatReminder(current)
    if (currentText.isNotBlank()) {
        out += SelectorOption(
            value = REMINDER_CLEAR,
            label = "Hatırlatıcıyı kaldır",
            detail = "şu an: $currentText",
        )
    }
    fun add(label: String, c: Calendar) {
        if (c.timeInMillis <= now.timeInMillis) return // geçmişe hatırlatıcı kurma
        out += SelectorOption(value = reminderIso(c), label = label, detail = formatReminder(reminderIso(c)))
    }
    add("1 saat sonra", (now.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.SECOND, 0) })
    add("Bugün 18:00", now.atTime(18, 0))
    add("Yarın 09:00", now.plusDays(1).atTime(9, 0))
    add("Yarın 14:00", now.plusDays(1).atTime(14, 0))
    add("Gelecek hafta", now.plusDays(7).atTime(9, 0))
    out += SelectorOption(value = REMINDER_CUSTOM, label = "Özel tarih…", detail = "takvimden seç")
    return out
}

// Takvim + saat: iki adımlı diyalog. Material3 bileşenleri marka temasının
// içinde çiziliyor (dinamik renk kapalı — anayasa §4.1), ayrı palet gelmiyor.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderCustomPicker(
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickedDateMs by remember { mutableStateOf<Long?>(null) }

    if (pickedDateMs == null) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = { pickedDateMs = state.selectedDateMillis },
                ) { Text("İleri") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
        ) { DatePicker(state = state) }
        return
    }

    // Saat adımı AlertDialog içinde: Material3 1.2'de TimePickerDialog yok,
    // DatePickerDialog'u saat için kullanmak yanlış kabuk olurdu.
    val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saat") },
        confirmButton = {
            TextButton(onClick = {
                // DatePicker UTC gün başı veriyor; günü UTC alanlarından okuyup
                // Türkiye saatiyle yeniden kur (yerel saatle okursak gün kayar).
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = pickedDateMs ?: 0L
                }
                val picked = cal().apply {
                    clear()
                    set(
                        utc.get(Calendar.YEAR),
                        utc.get(Calendar.MONTH),
                        utc.get(Calendar.DAY_OF_MONTH),
                        timeState.hour,
                        timeState.minute,
                        0,
                    )
                }
                onPicked(reminderIso(picked))
            }) { Text("Kur") }
        },
        dismissButton = { TextButton(onClick = { pickedDateMs = null }) { Text("Geri") } },
        text = { TimePicker(state = timeState) },
    )
}
