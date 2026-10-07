package com.agent.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bildirim üstündeki "Sil" tuşu. Ekran görüntüsünden otomatik üretilen notların
 * çoğu çöp; uygulamayı açıp Notlarım'a gidip satırı bulmak, bildirimden tek
 * dokunuşla halledilecek bir iş için fazla yol (kullanıcı isteği 07.08).
 *
 * Başarı sessizdir: kullanıcının kapattığı bildirimin yerine "Not silindi"
 * bildirimi üretmek aynı işi ikinci kez gösteriyordu. Yalnız ağ/köprü hatası
 * olursa kalıcı bir uyarı verilir.
 */
class NoteActionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = BridgeClient()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DELETE) return
        val noteId = intent.getStringExtra("noteId").orEmpty()
        val tag = intent.getStringExtra("notificationTag").orEmpty()
        val id = intent.getIntExtra("notificationId", 0)

        val manager = NotificationManagerCompat.from(context)
        // Önce kapat: dokunuşun karşılığı anında görünsün, ağ turunu bekletmesin.
        runCatching { if (tag.isNotBlank()) manager.cancel(tag, id) else manager.cancel(id) }
        if (noteId.isBlank()) return

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("url", null).orEmpty()
        if (baseUrl.isBlank()) return
        val settings = BridgeSettings(baseUrl, prefs.getString("token", null).orEmpty())

        val pendingResult = goAsync()
        scope.launch {
            try {
                val hata = runCatching { client.coworkDeleteNote(settings, noteId) }.exceptionOrNull()
                    ?: return@launch
                runCatching { PushChannels.ensure(context) }
                runCatching {
                    manager.notify(
                        "$SONUC_TAG:$noteId",
                        "$SONUC_TAG:$noteId".hashCode(),
                        hataBildirimi(context, hata.message),
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun hataBildirimi(context: Context, hata: String?) =
        NotificationCompat.Builder(context, PushChannels.NOTES)
            // Silme hatasi da not bildirimidir: PushNotifications'taki not simgesiyle
            // aynı olsun ki gölgede aynı işin devamı gibi okunsun.
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle("Not silinemedi")
            .setContentText(hata ?: "Bilinmeyen hata")
            .setStyle(NotificationCompat.BigTextStyle().bigText(hata ?: "Bilinmeyen hata"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Hata metni kilit ekranında görünmez; orada yalnız başlık kalır.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(kilitEkraniSurumu(context, PushChannels.NOTES, R.drawable.ic_stat_note, "Not silinemedi"))
            .setAutoCancel(true)
            .build()

    companion object {
        const val ACTION_DELETE = "com.agent.bridge.ACTION_NOTE_DELETE"
        private const val SONUC_TAG = "note-delete"
    }
}
