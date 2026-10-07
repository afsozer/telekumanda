package com.agent.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val MONITOR_CHANNEL = "bridge_monitor"
// Olay/onay kanalları PushChannels'ta (v2, sesli) — PushNotifications ile ortak.
private val EVENT_CHANNEL = PushChannels.EVENTS
private val APPROVAL_CHANNEL = PushChannels.APPROVALS
private const val FOREGROUND_ID = 40
private const val APPROVAL_ID = 42

class BridgeMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = BridgeClient()
    private var pollJob: Job? = null
    private var lastOperationEventId: String = ""
    private var operationCursorInitialized = false
    private var lastApprovalTag: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        startForeground(FOREGROUND_ID, baseNotification().build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pollJob?.cancel()
        pollJob = scope.launch {
            pollNotificationsLoop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    private suspend fun CoroutineScope.pollNotificationsLoop() {
        var lastPending = false
        var lastSessionId = ""
        while (isActive) {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString("url", null).orEmpty()
            if (baseUrl.isBlank()) {
                delay(5_000)
                continue
            }
            val settings = BridgeSettings(baseUrl, prefs.getString("token", null).orEmpty())

            runCatching {
                // Bildirimlerin tek kanalı bu bağlantı: köprü not, hatırlatma, onay ve
                // görev olaylarını bu cihaz için kuyrukta tutar, onaylanınca düşer.
                val registered = NotificationRoute.register(client, settings, this@BridgeMonitorService)
                val result = client.pollNotifications(settings, lastPending, lastSessionId, lastOperationEventId,
                    NotificationRoute.deviceId(this@BridgeMonitorService))
                if (registered) {
                    val delivered = result.pushEvents.filter { PushNotifications.post(this@BridgeMonitorService, it) }
                    NotificationRoute.acknowledge(client, settings, this@BridgeMonitorService, delivered.map { it.deliveryId })
                    clearApprovalNotification()
                    // Permissions/channel may be disabled: keep the receipt pending without a busy loop.
                    if (delivered.size < result.pushEvents.size) delay(5_000)
                } else {
                    // Eski köprü (kayıt ucu yok): olay/onay akışından yerel bildirim.
                    postNewOperationEvents(result)
                    if (result.pending) showApprovalNotification(result) else clearApprovalNotification()
                }
                lastPending = result.pending
                lastSessionId = result.sessionId
                lastOperationEventId = result.eventId
                // Poll sadece yeni olayları taşır; silinmiş oturumun bitiş olayı
                // kaçmışsa kapsülü köprünün güncel operasyon listesiyle düzelt.
                val etkin = client.operations(settings).operations
                    .filter { it.status == "running" || it.status == "waiting" }
                    .map { notificationKey(it.backend, it.sessionId) }
                    .toSet()
                KapsulDenetleyici.etkinOturumlarlaUzlastir(this@BridgeMonitorService, etkin)
            }
                .onFailure {
                    // network issue or timeout, wait a bit
                    delay(5_000)
                }
        }
    }

    private fun postNewOperationEvents(result: NotificationPollResult) {
        val previous = lastOperationEventId
        // İlk yanıt yalnız cursor kurar. Sonraki ilk olay (önceden hiç olay yoksa bile)
        // bildirilir; cursor pencerenin dışına düştüyse geçmiş topluca oynatılmaz.
        unseenOperationEvents(result.events, previous, operationCursorInitialized).forEach { event ->
            // O sohbet zaten açıksa sonuç ekranda görünüyor; bildirim gürültü.
            if (AppForeground.suppresses(event.backend, event.sessionId)) return@forEach
            when (event.kind) {
                "completed" -> postEvent(event, "Görev tamamlandı · ${event.backendLabel}", event.summary.ifBlank { event.model })
                "failed" -> postEvent(event, "Görev başarısız · ${event.backendLabel}", event.summary.ifBlank { event.model })
            }
        }
        lastOperationEventId = result.eventId.ifBlank { result.events.firstOrNull()?.id.orEmpty() }.ifBlank { previous }
        operationCursorInitialized = true
    }

    private fun showApprovalNotification(result: NotificationPollResult) {
        // O sohbet açıksa onay kartı zaten ekranda. Bildirim atma; daha önce
        // atılmış olan varsa (kullanıcı sohbete yeni girdiyse) düşür.
        if (AppForeground.suppresses(result.backend, result.sessionId)) {
            clearApprovalNotification()
            return
        }
        val intentAllow = Intent(this, ApprovalReceiver::class.java).apply {
            action = "com.agent.bridge.ACTION_APPROVE"
            putExtra("sessionId", result.sessionId)
            putExtra("backend", result.backend)
            putExtra("allow", true)
        }
        val pendingAllow = PendingIntent.getBroadcast(
            this,
            1,
            intentAllow,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val intentDeny = Intent(this, ApprovalReceiver::class.java).apply {
            action = "com.agent.bridge.ACTION_APPROVE"
            putExtra("sessionId", result.sessionId)
            putExtra("backend", result.backend)
            putExtra("allow", false)
        }
        val pendingDeny = PendingIntent.getBroadcast(
            this,
            2,
            intentDeny,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, APPROVAL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("İzin İsteği")
            .setContentText(result.summary)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_play, "İzin ver", pendingAllow)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reddet", pendingDeny)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .putExtra("approvalBackend", result.backend)
                        .putExtra("approvalSessionId", result.sessionId)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

        val tag = notificationKey(result.backend, result.sessionId)
        lastApprovalTag = tag
        NotificationManagerCompat.from(this).notify(tag, APPROVAL_ID, builder.build())
    }

    // Tag'li atıldığı için tag'siz cancel çalışmaz; en son kullanılanı saklıyoruz.
    private fun clearApprovalNotification() {
        val tag = lastApprovalTag ?: return
        NotificationManagerCompat.from(this).cancel(tag, APPROVAL_ID)
        lastApprovalTag = null
    }

    private fun postEvent(event: OperationEvent, title: String, text: String) {
        NotificationManagerCompat.from(this).notify(
            notificationKey(event.backend, event.sessionId),
            event.id.hashCode(),
            baseNotification()
                .setChannelId(EVENT_CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, MONITOR_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AgentBridge")
            .setContentText("Bridge izleniyor")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(MONITOR_CHANNEL, "Bridge monitor", NotificationManager.IMPORTANCE_LOW),
        )
        // Olay/onay kanalları (v2, sesli) PushChannels'tan.
        PushChannels.ensure(this)
    }
}
