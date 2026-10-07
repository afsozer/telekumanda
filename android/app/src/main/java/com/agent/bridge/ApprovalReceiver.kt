package com.agent.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ApprovalReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = BridgeClient()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.agent.bridge.ACTION_APPROVE") {
            val sessionId = intent.getStringExtra("sessionId").orEmpty()
            val backend = intent.getStringExtra("backend").orEmpty()
            val allow = intent.getBooleanExtra("allow", false)

            // Clear the notification immediately for quick response feeling
            NotificationManagerCompat.from(context).cancel(42) // APPROVAL_ID

            if (sessionId.isBlank() || backend.isBlank()) return

            // Kapsülü İYİMSER çevir: cevap verildiği anda o oturum artık
            // kullanıcıyı beklemiyor. Köprünün gerçeği (`started` push'u, ya da
            // tur bittiyse `completed`) 0-2 sn içinde üzerine yazar; bu yalnız
            // aradaki "hâlâ onay bekliyor" yalanını kapatır. Reddetmede de
            // aynısı: reddedilen tur ya devam eder (waiting → running =
            // `started`) ya biter (`completed`), ikisinde de "onay" yanlıştır.
            KapsulDenetleyici.onayCozuldu(context, backend, sessionId)

            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString("url", null).orEmpty()
            if (baseUrl.isBlank()) return
            val settings = BridgeSettings(baseUrl, prefs.getString("token", null).orEmpty())

            val pendingResult = goAsync()
            scope.launch {
                try {
                    client.approve(settings, backend, sessionId, allow)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
