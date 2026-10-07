package com.agent.bridge

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Onay bildirimlerinin ortak parçaları: kilit ekranı gizliliği ve "İzin ver" /
 * "Reddet" tuşları. Üç üretici (PushNotifications, eski yol BridgeMonitorService,
 * kapsül KapsulDenetleyici) aynı sözleşmeyi buradan alıyor; biri unutulursa
 * kilit ekranında tek dokunuşla onay kapısı açık kalırdı.
 */
internal object OnayBildirimi {
    const val ACTION_APPROVE = "com.agent.bridge.ACTION_APPROVE"

    /**
     * Onay tuşlarını ekler. Biçim kararı [onayEylemKipi]'nde:
     * - API 31+ ve kimlik biliniyorsa doğrudan onay, ama `setAuthenticationRequired`
     *   ile — kilitliyken önce kilit açılır.
     * - API 31 altında ya da kimlik yoksa tuş onay VERMEZ, uygulamayı o oturumun
     *   onay kartına açar (kilit açmayı Android etkinliği başlatırken ister).
     *
     * PendingIntent istek kodunda `requestId` de var: her onayın tuşu kendi
     * kimliğini taşısın, yeni bildirimin FLAG_UPDATE_CURRENT'i eski bildirimin
     * tuşuna yeni kimliği yazmasın.
     */
    fun eylemleriEkle(
        builder: NotificationCompat.Builder,
        context: Context,
        backend: String,
        sessionId: String,
        requestId: String,
        istekAnahtari: String,
        bildirimEtiketi: String,
        bildirimId: Int,
    ) {
        when (onayEylemKipi(Build.VERSION.SDK_INT, requestId)) {
            OnayEylemKipi.DOGRUDAN -> for ((izin, etiket) in listOf(true to "İzin ver", false to "Reddet")) {
                val niyet = Intent(context, ApprovalReceiver::class.java)
                    .setAction(ACTION_APPROVE)
                    .putExtra("sessionId", sessionId)
                    .putExtra("backend", backend)
                    .putExtra("allow", izin)
                    .putExtra("requestId", requestId)
                    .putExtra("notificationTag", bildirimEtiketi)
                    .putExtra("notificationId", bildirimId)
                val bekleyen = PendingIntent.getBroadcast(
                    context, "$istekAnahtari:$requestId:$izin".hashCode(), niyet,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(android.R.drawable.ic_dialog_alert, etiket, bekleyen)
                        .setAuthenticationRequired(true)
                        .build(),
                )
            }
            OnayEylemKipi.UYGULAMADA_AC -> {
                val bekleyen = PendingIntent.getActivity(
                    context, "$istekAnahtari:ac".hashCode(), onayEkraniNiyeti(context, backend, sessionId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(android.R.drawable.ic_dialog_alert, "Onay ekranını aç", bekleyen).build(),
                )
            }
        }
    }

    /** MainActivity'nin onay oturumunu açan extra sözleşmesi (handleApprovalIntent). */
    fun onayEkraniNiyeti(context: Context, backend: String, sessionId: String): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("approvalBackend", backend)
            .putExtra("approvalSessionId", sessionId)
}

/**
 * Bildirimi kilit ekranında içeriksiz gösterir: asıl başlık/özet (oturum adı,
 * komut özeti, not başlığı) ancak kilit açılınca görünür. Kilit ekranında
 * uygulama adı ve [kilitEkraniMetni]'ndeki genel cümle kalır.
 */
internal fun NotificationCompat.Builder.kilitEkranindaGizle(
    context: Context,
    kanal: String,
    simge: Int,
    kind: String,
): NotificationCompat.Builder = setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    .setPublicVersion(kilitEkraniSurumu(context, kanal, simge, kilitEkraniMetni(kind)))

internal fun kilitEkraniSurumu(context: Context, kanal: String, simge: Int, metin: String): Notification =
    NotificationCompat.Builder(context, kanal)
        .setSmallIcon(simge)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(metin)
        .build()
