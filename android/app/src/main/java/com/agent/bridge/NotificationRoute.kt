package com.agent.bridge

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Kurulum kimliği kalıcıdır; köprü bildirim kuyruğunu bu kimliğe bağlar. */
object NotificationRoute {
    @Synchronized
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getString("notification_device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("notification_device_id", it).commit()
        }
    }

    /**
     * Cihazı köprüye kaydeder. Model, "turu başlatan cihaza bildir" eşlemesi için.
     * false: köprüde uç yok (404, eski köprü) — çağıran eski poller'a düşer.
     */
    suspend fun register(client: BridgeClient, settings: BridgeSettings, context: Context): Boolean {
        val result = try {
            client.postJson(settings, "/notifications/device", JSONObject()
                .put("deviceId", deviceId(context)).put("model", Build.MODEL.orEmpty()))
        } catch (e: BridgeHttpException) {
            if (e.code != 404) throw e
            return false
        }
        check(result.optBoolean("ok")) { "Bildirim kanalı kaydedilemedi" }
        return true
    }

    suspend fun acknowledge(client: BridgeClient, settings: BridgeSettings, context: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        val result = client.postJson(settings, "/notifications/ack", JSONObject()
            .put("deviceId", deviceId(context)).put("ids", JSONArray(ids)))
        check(result.optBoolean("ok")) { "Bildirim teslim onayı kaydedilemedi" }
    }
}
