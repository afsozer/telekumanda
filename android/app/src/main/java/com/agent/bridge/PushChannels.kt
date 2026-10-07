package com.agent.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings as SysSettings

// Bildirim kanalları (v2, SESLİ): Android var olan kanalın önemini/sesini sonradan
// değiştirmeye izin vermediğinden eski sessiz kanallar ("bridge_events",
// "bridge_approvals") silinip yerine yüksek önemli + varsayılan bildirim sesli
// v2 kanalları kurulur. PushNotifications ve BridgeMonitorService bunları kullanır.
object PushChannels {
    const val EVENTS = "bridge_events_v2"
    const val APPROVALS = "bridge_approvals_v2"

    // Ekran görüntüsünden not kanalı. v1 IMPORTANCE_LOW'du; kâğıt üstünde
    // "durum çubuğunda görünür ama ses çıkarmaz" demek, pratikte bildirim
    // gölgesinin "Sessiz" bölümüne düşüyor ve Honor'da durum çubuğu simgesi
    // gösterilmiyordu — kullanıcı notların oluştuğunu FARK ETMİYORDU (07.08).
    // v2 IMPORTANCE_DEFAULT: simge ve rozet görünür, gölgede normal bölümde
    // durur; sessizliği önemden değil, kanalın sesinin null olmasından alır.
    // Var olan kanalın önemi koddan değiştirilemediği için yeni kimlik şart.
    const val NOTES = "bridge_notes_v2"

    // Kapsül (Android 16 Live Updates) kanalı. Faz 8'de açılan `bridge_capsule`
    // kanalı cihazda kalmış olabilir ve önemi bilinmiyor — IMPORTANCE_MIN ise
    // terfiyi (promoted-ongoing) engeller, var olan kanalın önemi de koddan
    // değiştirilemez. O yüzden yeni kimlik + eskisinin silinmesi.
    // IMPORTANCE_LOW bilinçli: MIN terfi şartını düşürür, DEFAULT gölgede
    // normal bölüme çıkar ve kalıcı bir satır olarak rahatsız eder. Ses,
    // titreşim ve rozet yok: kapsül bir uyarı değil, ortam göstergesi.
    const val CAPSULE = "bridge_capsule_v2"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Eski sessiz kanallar kalmasın (ayarlar listesinde kafa karıştırıyordu).
        runCatching { manager.deleteNotificationChannel("bridge_events") }
        runCatching { manager.deleteNotificationChannel("bridge_approvals") }
        runCatching { manager.deleteNotificationChannel("bridge_notes_v1") }
        runCatching { manager.deleteNotificationChannel("bridge_capsule") }
        val sound = SysSettings.System.DEFAULT_NOTIFICATION_URI
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(EVENTS, "Görev olayları", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(sound, attrs)
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(APPROVALS, "İzin istekleri", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(sound, attrs)
                enableVibration(true)
            },
        )
        // IMPORTANCE_DEFAULT + ses null: simge ve rozet görünür, heads-up olarak
        // ekrana ATLAMAZ (o HIGH'ın işi), ses ve titreşim yok. Görünürlüğü
        // önemden, sessizliği sesin yokluğundan alan bilinçli bir bileşim.
        manager.createNotificationChannel(
            NotificationChannel(NOTES, "Ekran görüntüsü notları", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CAPSULE, "Kapsül (tur göstergesi)", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }
}
