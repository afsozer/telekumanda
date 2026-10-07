package com.agent.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Uygulama süreci Android tarafından öldürülse bile saklanan periyodik kontrol.
 *
 * WorkManager işi kendi veritabanında ve JobScheduler'da tutar; zamanı gelince
 * AgentBridge sürecini yalnız bu kısa kontrol için yeniden başlatabilir. Android'in
 * izin verdiği en kısa periyodik aralık 15 dakikadır; pil optimizasyonları çalışmayı
 * geciktirebilir. Uygulama açıkkenki dakikalık healer bunun hızlı katmanı olarak
 * ayrıca çalışmaya devam eder.
 */
class WirelessDebugWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val changed = WirelessDebugHealer.heal(applicationContext)
        if (changed) Log.d("AgHeal", "kalıcı arka plan kontrolü ayarı açtı")
        return Result.success()
    }
}

object WirelessDebugWork {
    internal const val UNIQUE_NAME = "wireless-debug-healer"
    private const val INTERVAL_MINUTES = 15L

    /**
     * Güvenli ağ listesi değişince de çağrılır (ayar ekranından): liste boşalırsa
     * iş iptal edilir, ilk ağ eklenince geri kurulur.
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        // Telefon gibi izin verilmemiş cihazlarda 15 dakikalık gereksiz uyanma yaratma.
        // Güvenli ağ listesi boşken de aynısı geçerli: healer hiçbir şey yapmayacak,
        // 15 dakikada bir süreç uyandırmanın karşılığı yok.
        if (app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED ||
            SafeNetworkStore.list(app).isEmpty()
        ) {
            WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequest.Builder(
            WirelessDebugWorker::class.java,
            INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .addTag(UNIQUE_NAME)
            .build()

        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
