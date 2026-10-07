package com.agent.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Kapsül kartındaki "Durdur" tuşu. `ApprovalReceiver` kalıbının eşi:
// goAsync() + IO scope + BridgeClient. Eşleme tek yerde
// (`BridgeClient.turDurdur`, shared) — ekrandaki Durdur tuşu ile bildirimdeki
// aynı uca gitsin diye.
//
// Bildirim BURADA iptal edilmiyor: kapsülün kapanması köprüden gelecek
// `completed` push'unun işi. Durdurma başarısız olursa kapsül duruyor kalır ve
// bu doğrudur — tur gerçekten duruyor demektir.
class TurDurdurReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = BridgeClient()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val sessionId = intent.getStringExtra("sessionId").orEmpty()
        val backend = intent.getStringExtra("backend").orEmpty()
        if (sessionId.isBlank() || backend.isBlank()) return

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("url", null).orEmpty()
        if (baseUrl.isBlank()) return
        val settings = BridgeSettings(baseUrl, prefs.getString("token", null).orEmpty())

        val pendingResult = goAsync()
        scope.launch {
            try {
                client.turDurdur(settings, backend, sessionId)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.agent.bridge.ACTION_TUR_DURDUR"
    }
}
