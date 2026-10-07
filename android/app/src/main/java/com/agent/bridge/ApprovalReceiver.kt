package com.agent.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ApprovalReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = BridgeClient()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == OnayBildirimi.ACTION_APPROVE) {
            val sessionId = intent.getStringExtra("sessionId").orEmpty()
            val backend = intent.getStringExtra("backend").orEmpty()
            val allow = intent.getBooleanExtra("allow", false)
            // Bildirimin ÜRETİLDİĞİ andaki onayın kimliği. Köprü artık başka bir
            // onay bekliyorsa 409 döner; bayat bildirim yeni isteği onaylamaz.
            val requestId = intent.getStringExtra("requestId").orEmpty()
            val tag = intent.getStringExtra("notificationTag").orEmpty()
            val notificationId = intent.getIntExtra("notificationId", APPROVAL_ID)

            // Clear the notification immediately for quick response feeling
            val manager = NotificationManagerCompat.from(context)
            runCatching { if (tag.isNotBlank()) manager.cancel(tag, notificationId) else manager.cancel(APPROVAL_ID) }

            if (sessionId.isBlank() || backend.isBlank()) return
            // Kimliksiz tuş (güncellemeden önce atılmış bildirim) neyi onayladığını
            // bilmiyor: göndermek, arada gelmiş yeni bir isteği onaylayabilirdi.
            if (requestId.isBlank()) {
                kisaMesaj(context, "Bu bildirimden onay verilemiyor; uygulamadan onayla")
                return
            }

            // Kapsülü İYİMSER çevir: cevap verildiği anda o oturum artık
            // kullanıcıyı beklemiyor. Köprünün gerçeği (`started` push'u, ya da
            // tur bittiyse `completed`) 0-2 sn içinde üzerine yazar; bu yalnız
            // aradaki "hâlâ onay bekliyor" yalanını kapatır. Reddetmede de
            // aynısı: reddedilen tur ya devam eder (waiting → running =
            // `started`) ya biter (`completed`), ikisinde de "onay" yanlıştır.
            KapsulDenetleyici.onayCozuldu(context, backend, sessionId, requestId)

            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString("url", null).orEmpty()
            if (baseUrl.isBlank()) return
            val settings = BridgeSettings(baseUrl, prefs.getString("token", null).orEmpty())

            val pendingResult = goAsync()
            scope.launch {
                try {
                    client.approve(settings, backend, sessionId, allow, requestId)
                } catch (e: Exception) {
                    if (bayatOnayHatasi(e)) kisaMesaj(context, BAYAT_ONAY_MESAJI)
                    else {
                        e.printStackTrace()
                        kisaMesaj(context, "Onay gönderilemedi")
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    // Alıcının ekranı yok; kısa metin Toast'la ana iş parçacığından gösterilir.
    private fun kisaMesaj(context: Context, metin: String) {
        val uygulama = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(uygulama, metin, Toast.LENGTH_SHORT).show() }
        }
    }

    private companion object {
        // Eski bildirim yolunun (BridgeMonitorService) sabit kimliği.
        const val APPROVAL_ID = 42
    }
}
