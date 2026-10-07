package com.agent.bridge

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Bildirim susturma/temizleme için tek doğruluk kaynağı: uygulama önde mi ve
// hangi sohbet açık. BridgeMonitorService, PushNotifications ve sohbet ekranı aynı
// süreçte çalıştığı için basit bir object yeter; kalıcılığa gerek yok — süreç
// ölmüşse zaten ön planda değiliz.
object AppForeground {
    // StateFlow, çünkü periyodik ağ döngüleri (ısıtma + sekme aynası) arka
    // planda `first { it }` ile bunun üstünde uyuyor — 28-29 Ağu gecesi bu
    // döngüler ekrana bakmadan 45 sn'de bir LTE'yi uyandırıp gecede %16 pil
    // yaktı. Volatile boolean bekletmeyi kuramazdı.
    private val foregroundFlow = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> get() = foregroundFlow
    private val foreground: Boolean get() = foregroundFlow.value
    @Volatile private var openKey = ""

    fun setForeground(value: Boolean) {
        foregroundFlow.value = value
    }

    fun openChat(backend: String, sessionId: String) {
        openKey = if (sessionId.isBlank()) "" else notificationKey(backend, sessionId)
    }

    fun closeChat(backend: String, sessionId: String) {
        if (openKey == notificationKey(backend, sessionId)) openKey = ""
    }

    fun suppresses(backend: String, sessionId: String): Boolean =
        suppressesNotification(foreground, openKey, backend, sessionId)

    // Sohbete girilince o oturuma ait bekleyen bildirimleri düşür. Yalnız
    // eşleşen tag'ler iptal edilir; başka oturumların bildirimleri kalır.
    fun clearFor(context: Context, backend: String, sessionId: String) {
        if (sessionId.isBlank()) return
        val tag = notificationKey(backend, sessionId)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            val compat = NotificationManagerCompat.from(context)
            manager.activeNotifications
                .filter { it.tag == tag }
                .forEach { compat.cancel(tag, it.id) }
        }
    }
}
