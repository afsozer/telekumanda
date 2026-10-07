package com.agent.bridge.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.agent.bridge.BridgeClient
import com.agent.bridge.BridgeSettings
import com.agent.bridge.BuildConfig
import com.agent.bridge.R
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

// Ana ekran kota kartı (4×2): solda Claude Code kalan limiti, sağda Nano-GPT
// bakiyesi. Veri köprünün /usage ucundan — telefon Anthropic'e ya da
// Nano-GPT'ye KENDİSİ GİTMEZ: Claude refresh token'ı her kullanımda dönüyor,
// telefon yenilerse PC/Mac'teki CLI oturumu düşer; uçların hız sınırı da
// köprünün önbelleğinde kalır.
//
// Akış: WorkManager 15 dakikada bir (Android'in izin verdiği en kısa periyot)
// /usage çeker, ham yanıtı saklar, kartları yeniden çizer. Karttaki ↻ tek
// seferlik işi ?force=1 ile kuyruğa atar (uygulamadaki "Yenile" ile aynı yol).
// Köprüye ulaşılamazsa son iyi veri kalır; 30 dakikadan eskiyse başlıkta
// turuncu üçgen yanar (Mac kartıyla aynı eşik).

class KotaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        KotaWidgetRenderer.render(context, appWidgetIds)
        KotaWidgetWork.schedulePeriodic(context)
        KotaWidgetWork.refreshNow(context, force = false)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        // Boyut değişti: bitmap yeni ölçüyle yeniden çizilmeli.
        KotaWidgetRenderer.render(context, intArrayOf(appWidgetId))
    }

    override fun onEnabled(context: Context) {
        KotaWidgetWork.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        KotaWidgetWork.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            KotaWidgetStore.markRefreshing(context)
            KotaWidgetRenderer.render(context)
            KotaWidgetWork.refreshNow(context, force = true)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_REFRESH = "com.agent.bridge.ACTION_KOTA_WIDGET_REFRESH"
    }
}

class KotaWidgetWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (KotaWidgetRenderer.widgetIds(ctx).isEmpty()) return Result.success()
        val force = inputData.getBoolean(KEY_FORCE, false)
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("url", null).orEmpty()
        if (baseUrl.isBlank()) {
            KotaWidgetStore.saveError(ctx, "Köprü ayarı yok — AgentBridge'i aç")
        } else {
            val settings = BridgeSettings(baseUrl, prefs.getString("token", null).orEmpty())
            try {
                val json = BridgeClient(if (BuildConfig.IS_LITE) "lite" else "")
                    .getJson(settings, if (force) "/usage?force=1" else "/usage")
                KotaWidgetStore.saveResponse(ctx, json)
            } catch (e: Exception) {
                Log.w("KotaWidget", "kullanım çekilemedi", e)
                KotaWidgetStore.saveError(ctx, "Köprüye ulaşılamadı")
            }
        }
        KotaWidgetRenderer.render(ctx)
        // Başarısızlıkta yeniden deneme yok: periyodik iş 15 dk sonra zaten geliyor.
        return Result.success()
    }

    companion object {
        const val KEY_FORCE = "force"
    }
}

internal object KotaWidgetWork {
    private const val PERIODIC = "kota-widget-periodic"
    private const val NOW = "kota-widget-now"

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<KotaWidgetWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Ağ kısıtı yok: çevrimdışıysa iş hemen düşsün ve kart "ulaşılamadı" desin, beklemesin. */
    fun refreshNow(context: Context, force: Boolean) {
        val request = OneTimeWorkRequestBuilder<KotaWidgetWorker>()
            .setInputData(workDataOf(KotaWidgetWorker.KEY_FORCE to force))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(NOW)
    }
}

/** Son iyi /usage yanıtı + son hata. Ham JSON saklanıyor; ayrıştırma çizimde. */
internal object KotaWidgetStore {
    private const val PREFS = "kota_widget"
    private const val RAW = "raw"
    private const val RECEIVED_AT = "received_at"
    private const val ERROR = "error"
    private const val REFRESHING_SINCE = "refreshing_since"
    /** ↻ basılıyken simge vurgulu; iş takılırsa vurgu sonsuza kadar kalmasın. */
    private const val REFRESHING_TIMEOUT_MS = 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveResponse(ctx: Context, json: JSONObject) {
        prefs(ctx).edit()
            .putString(RAW, json.toString())
            .putLong(RECEIVED_AT, System.currentTimeMillis())
            .remove(ERROR)
            .remove(REFRESHING_SINCE)
            .apply()
    }

    fun saveError(ctx: Context, message: String) {
        prefs(ctx).edit().putString(ERROR, message).remove(REFRESHING_SINCE).apply()
    }

    fun markRefreshing(ctx: Context) {
        prefs(ctx).edit().putLong(REFRESHING_SINCE, System.currentTimeMillis()).apply()
    }

    fun refreshing(ctx: Context): Boolean {
        val since = prefs(ctx).getLong(REFRESHING_SINCE, 0L)
        return since > 0 && System.currentTimeMillis() - since < REFRESHING_TIMEOUT_MS
    }

    fun error(ctx: Context): String? = prefs(ctx).getString(ERROR, null)

    fun snapshot(ctx: Context): KotaSnapshot? {
        val p = prefs(ctx)
        val raw = p.getString(RAW, null) ?: return null
        val receivedAt = Instant.ofEpochMilli(p.getLong(RECEIVED_AT, 0L))
        return runCatching { KotaWidgetData.parse(JSONObject(raw), receivedAt) }.getOrNull()
    }
}

internal object KotaWidgetRenderer {
    /** Kartlar arası boşluk (dp). */
    private const val GAP_DP = 8
    /**
     * Bitmap ekranın kendi yoğunluğunda çiziliyor. İlk sürüm 2,6x'te kırpıyordu;
     * Magic7 Pro 3,5x (560 dpi), başlatıcı bitmap'i büyütünce metin bulanık
     * çıktı (05.10.2026). Bütçe sorun değil: 4 bitmap ≈ 5 MB, sınır ekranın 1,5
     * katı (~21 MB). Tavan yalnız aşırı yoğun ekranlara karşı.
     */
    private const val MAX_RENDER_DENSITY = 4f
    /**
     * Kart en fazla bu en/boy oranında (yükseklik/genişlik). Honor'un 2 satırlık
     * hücresi kendi 2×2 widget'larının altındaki etiket payını da içeriyor;
     * kart hücreyi tümden doldurunca komşularından uzun duruyordu. Kare kart
     * üste hizalanıyor, Hava Durumu/Takvim'le aynı boyda kalıyor.
     */
    private const val MAX_CARD_ASPECT = 1.0f

    fun widgetIds(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, KotaWidgetProvider::class.java))

    fun render(ctx: Context, ids: IntArray = widgetIds(ctx)) {
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(ctx)
        val snapshot = KotaWidgetStore.snapshot(ctx)
        val error = KotaWidgetStore.error(ctx)
        val refreshing = KotaWidgetStore.refreshing(ctx)
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val stale = snapshot != null && KotaWidgetData.isStale(snapshot, now)
        val claudeStale = stale || snapshot?.claude?.stale == true
        val density = min(ctx.resources.displayMetrics.density, MAX_RENDER_DENSITY)

        val claudeEmpty = when {
            snapshot == null -> error ?: "Veri bekleniyor…"
            snapshot.claude == null -> "Köprüde Claude verisi yok"
            else -> "Resmi limit alınamadı"
        }
        val nanoEmpty = when {
            snapshot == null -> error ?: "Veri bekleniyor…"
            else -> "Köprüde Nano-GPT verisi yok"
        }

        for (id in ids) {
            val options = manager.getAppWidgetOptions(id)
            val (widthDp, heightDp) = sizeDp(ctx, options)
            val cardWDp = (widthDp - GAP_DP) / 2f
            val cardW = (cardWDp * density).roundToInt()
            val cardH = (min(heightDp.toFloat(), cardWDp * MAX_CARD_ASPECT) * density).roundToInt()
            val views = RemoteViews(ctx.packageName, R.layout.kota_widget)
            // Açık ve koyu kartın ikisi de çiziliyor; hangisinin görüneceğini
            // düzenin layout/layout-night eşi seçiyor. Böylece tema değişince
            // kart bir sonraki çekimi beklemeden doğru renge geçiyor.
            for (dark in listOf(false, true)) {
                val painter = KotaCardPainter(density, dark)
                val claude = painter.drawClaude(cardW, cardH, snapshot?.claude, claudeStale, refreshing, claudeEmpty, now, zone)
                val nano = painter.drawNano(cardW, cardH, snapshot?.nano, stale, refreshing, nanoEmpty)
                views.setImageViewBitmap(if (dark) R.id.kota_claude_dark else R.id.kota_claude_light, claude)
                views.setImageViewBitmap(if (dark) R.id.kota_nano_dark else R.id.kota_nano_light, nano)
            }
            val refresh = PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, KotaWidgetProvider::class.java).setAction(KotaWidgetProvider.ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.kota_claude_refresh, refresh)
            views.setOnClickPendingIntent(R.id.kota_nano_refresh, refresh)
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
                val open = PendingIntent.getActivity(ctx, 1, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.kota_claude, open)
                views.setOnClickPendingIntent(R.id.kota_nano, open)
            }
            manager.updateAppWidget(id, views)
        }
    }

    /**
     * Başlatıcının bildirdiği boyut. Dikey ekranda genişlik MIN_WIDTH, yükseklik
     * MAX_HEIGHT (platformun kendi kuralı); yataydaysa tersi. Bildirilmemişse
     * 4×2'nin klasik en küçük ölçüsü.
     */
    private fun sizeDp(ctx: Context, options: Bundle): Pair<Int, Int> {
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val w = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return (if (w > 0) w else 320) to (if (h > 0) h else 160)
    }
}
