package com.agent.bridge

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

/**
 * Cihaz-yerel hatırlatma (21.10.2026): köprüden gelen liste Android alarmına
 * çevrilir, vakti gelince ağa çıkmadan yerel bildirim atılır. Arka planda ağ
 * trafiği yok; her hatırlatma tek bir kısa uyanma. Karar mantığı (neyin
 * kurulacağı/gösterileceği/iptal edileceği) shared'daki `hatirlaticiPlani`.
 *
 * Liste kaynakları: uygulama açılışı (`RemoteViewModel.hatirlaticilariSenkronla`),
 * köprünün ADB itmesi (`HatirlaticiAdbReceiver`), açılış/güncelleme sonrası
 * yerel kopyadan yeniden kurma (`HatirlaticiAlarmReceiver`).
 */
object Hatirlaticilar {
    private const val TAG = "Hatirlaticilar"
    private const val PREFS = "hatirlaticilar"
    private const val LISTE = "liste"
    private const val KURULU = "kurulu"
    private const val GOSTERILMIS = "gosterilmis"
    private const val BASLANGIC = "baslangic"
    const val KANAL = "hatirlaticilar_v1"
    const val EYLEM_ALARM = "com.agent.bridge.HATIRLATICI_ALARM"
    const val EKSTRA_ANAHTAR = "anahtar"
    const val EKSTRA_NOT = "reminderNoteId"

    // Eski bildirim servisinin kanalları (servis 21.10.2026'da söküldü).
    private val ESKI_KANALLAR = listOf("bridge_events_v2", "bridge_approvals_v2", "bridge_notes_v2", "bridge_capsule_v2")

    @Synchronized
    fun uygula(context: Context, liste: List<HatirlaticiKaydi>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val simdi = System.currentTimeMillis()
        val baslangic = prefs.getLong(BASLANGIC, 0L).takeIf { it > 0L } ?: simdi.also {
            prefs.edit().putLong(BASLANGIC, it).apply()
        }
        val kurulu = prefs.getStringSet(KURULU, emptySet()).orEmpty()
        val gosterilmis = prefs.getStringSet(GOSTERILMIS, emptySet()).orEmpty()
        val plan = hatirlaticiPlani(liste, kurulu, gosterilmis, simdi, baslangic)

        plan.iptal.forEach { alarmIptal(context, it) }
        plan.kurulacak.forEach { alarmKur(context, it) }
        plan.gosterilecek.forEach { goster(context, it) }

        // Gösterilmiş kümesi yalnız listede hâlâ duranlarla sınırlı: liste son 3
        // günü taşıdığı için küme kendiliğinden budanır.
        val listedeki = liste.map { it.anahtar }.toSet()
        val yeniGosterilmis = (gosterilmis + plan.gosterilecek.map { it.anahtar }).filter { it in listedeki }.toSet()
        prefs.edit()
            .putString(LISTE, liste.toJsonArray().toString())
            .putStringSet(KURULU, plan.kurulacak.map { it.anahtar }.toSet())
            .putStringSet(GOSTERILMIS, yeniGosterilmis)
            .apply()
    }

    /** Telefon açılışı / uygulama güncellemesi: alarmlar silinmiş olur, yerel kopyadan yeniden kur. */
    fun yenidenKur(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val liste = runCatching { parseHatirlaticilar(JSONArray(prefs.getString(LISTE, "[]"))) }.getOrDefault(emptyList())
        // Kurulu kümesi boşaltılır: sistem alarmları zaten silmiş, plan hepsini yeniden kursun.
        prefs.edit().putStringSet(KURULU, emptySet()).apply()
        uygula(context, liste)
    }

    /** ADB broadcast'inin `kayitlar` ekstrası: base64(JSON dizi). */
    fun adbYukunuUygula(context: Context, base64: String) {
        val json = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
        uygula(context, parseHatirlaticilar(JSONArray(json)))
    }

    @Synchronized
    fun alarmCaldi(context: Context, anahtar: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val liste = runCatching { parseHatirlaticilar(JSONArray(prefs.getString(LISTE, "[]"))) }.getOrDefault(emptyList())
        val kayit = liste.firstOrNull { it.anahtar == anahtar } ?: return
        val gosterilmis = prefs.getStringSet(GOSTERILMIS, emptySet()).orEmpty()
        if (anahtar !in gosterilmis) goster(context, kayit)
        prefs.edit()
            .putStringSet(GOSTERILMIS, gosterilmis + anahtar)
            .putStringSet(KURULU, prefs.getStringSet(KURULU, emptySet()).orEmpty() - anahtar)
            .apply()
    }

    private fun alarmNiyeti(context: Context, anahtar: String, olustur: Boolean): PendingIntent? {
        val intent = Intent(context, HatirlaticiAlarmReceiver::class.java)
            .setAction(EYLEM_ALARM)
            .putExtra(EKSTRA_ANAHTAR, anahtar)
        val bayrak = PendingIntent.FLAG_IMMUTABLE or
            (if (olustur) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, anahtar.hashCode(), intent, bayrak)
    }

    private fun alarmKur(context: Context, kayit: HatirlaticiKaydi) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val niyet = alarmNiyeti(context, kayit.anahtar, olustur = true) ?: return
        // USE_EXACT_ALARM (33+) kurulumda verilir; 31-32'de SCHEDULE_EXACT_ALARM
        // kullanıcı kapatmış olabilir — o zaman Doze dostu yaklaşık alarm.
        val tam = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (tam) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, kayit.at, niyet)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, kayit.at, niyet)
        }.onFailure { Log.w(TAG, "alarm kurulamadı: ${kayit.anahtar}", it) }
    }

    private fun alarmIptal(context: Context, anahtar: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        alarmNiyeti(context, anahtar, olustur = false)?.let { am.cancel(it); it.cancel() }
    }

    fun kanaliHazirla(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ESKI_KANALLAR.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        nm.createNotificationChannel(
            NotificationChannel(KANAL, "Hatırlatmalar", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notlardaki hatırlatıcılar; telefon kendi alarmıyla çalar"
            },
        )
    }

    private fun goster(context: Context, kayit: HatirlaticiKaydi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "bildirim izni yok, hatırlatma gösterilemedi: ${kayit.anahtar}")
            return
        }
        kanaliHazirla(context)
        val dokun = PendingIntent.getActivity(
            context,
            kayit.anahtar.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EKSTRA_NOT, kayit.noteId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val baslik = kayit.title.ifBlank { "Hatırlatma" }
        val bildirim = NotificationCompat.Builder(context, KANAL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(baslik)
            .setContentText(kayit.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(kayit.summary))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(kayit.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(dokun)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(kayit.anahtar, 1, bildirim) }
            .onFailure { Log.w(TAG, "hatırlatma gösterilemedi: ${kayit.anahtar}", it) }
    }
}

/** Alarm vakti, telefon açılışı ve uygulama güncellemesi. Dışarıya kapalı. */
class HatirlaticiAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Hatirlaticilar.EYLEM_ALARM ->
                Hatirlaticilar.alarmCaldi(context, intent.getStringExtra(Hatirlaticilar.EKSTRA_ANAHTAR).orEmpty())
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                Hatirlaticilar.yenidenKur(context)
        }
    }
}

/**
 * Köprünün ADB itmesi (`bridge/hatirlatici-adb.mjs`):
 * `am broadcast -n com.agent.bridge/.HatirlaticiAdbReceiver -a com.agent.bridge.HATIRLATICI_LISTESI --es kayitlar <base64>`.
 * Manifestte `android.permission.DUMP` ister: adb shell'de var, üçüncü taraf
 * uygulamalarda yok — başka bir uygulama sahte hatırlatma kuramaz.
 */
class HatirlaticiAdbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.agent.bridge.HATIRLATICI_LISTESI") return
        val yuk = intent.getStringExtra("kayitlar") ?: return
        runCatching { Hatirlaticilar.adbYukunuUygula(context, yuk) }
            .onFailure { Log.w("Hatirlaticilar", "ADB listesi uygulanamadı", it) }
    }
}
