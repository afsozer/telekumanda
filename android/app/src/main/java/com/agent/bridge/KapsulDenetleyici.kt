package com.agent.bridge

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

// Kapsül (Android 16 "Live Updates" → Honor Magic Capsule) denetleyicisi.
// Plan: docs/kapsul-live-updates-plani.md. Ölçümler: capsule-test/live/SONUC.md
// (kapsül hangi bildirimde açılıyor) ve SONUC2.md (kapsülün davranışı).
//
// Tasarımın ölçüme dayanan çekirdeği:
//  - TEK sabit bildirim id'si + `setOnlyAlertOnce(true)`: aynı id ile güncelleme
//    yerinde oluyor, id değiştirmek kapsülü kapatıp yeniden açıyor (SONUC2 §3).
//  - Ön plan servisi YOK: bildirim NotificationManagerService'te yaşıyor,
//    uygulama süreci ölse de kapsül duruyor (SONUC2 §1).
//  - TUR'da `setShortCriticalText` HİÇ ÇAĞRILMAZ (null geçmek değil): metin
//    varsa kronometreyi eziyor, yoksa kapsülde canlı sayaç akıyor (SONUC2 §2).
//    ONAY'da tersi: "onay" rozeti sayacı bilinçli eziyor, onay önceliklidir.
//  - Uygulama kendi önündeyken kapsülü Honor zaten gizliyor (SONUC2 §4), o
//    yüzden ui3 adasıyla çakışma yok ve ada koduna dokunulmuyor.
//
// Durum SharedPreferences'ta ("capsule_state") tutulur, çünkü push'lar süreç
// ölü iken de geliyor (explicit broadcast süreci uyandırıyor) ve bellekteki
// harita o anda boş olurdu. Durum BİRİKTİRİLMEZ, oturum haritasından TÜRETİLİR
// (`kapsulDurumu`, Faz 8 dersi).
object KapsulDenetleyici {
    // 40/42 BridgeMonitorService ve onay bildiriminde dolu.
    const val KAPSUL_ID = 44
    const val TAG = "Kapsul"

    private const val PREFS = "capsule_state"
    private const val ANAHTAR = "sessions"

    // Asılı kapsül sigortası: `completed` push'u gelmezse (bağlantı kopuk)
    // kapsül sonsuza kadar "tur" derdi. Sistem 2 saat sonra kendisi düşürür.
    private const val ZAMAN_ASIMI_MS = 2L * 60 * 60 * 1000

    // Android 16'da Notification.FLAG_PROMOTED_ONGOING. Sabit android-36
    // android.jar'da var ama NotificationCompat üzerinden görünmüyor; yalnız
    // hata ayıklama logunda kullanılıyor.
    private const val FLAG_PROMOTED_ONGOING = 0x00040000

    /**
     * Gelen push'u kapsüle işler. `PushNotifications.post` içinden, makbuz
     * kontrolünden SONRA (tekrar oynatma kapsülü yanlış duruma sokmasın) ve
     * `AppForeground.suppresses` süzgecinden ÖNCE (uygulama önde olsa da kapsül
     * güncel kalmalı; gizlemeyi sistem yapıyor) çağrılır.
     */
    @Synchronized
    fun isle(
        context: Context,
        kind: String,
        backend: String,
        backendLabel: String,
        sessionId: String,
        baslik: String,
        ozet: String,
        startedAt: String,
        requestId: String = "",
    ) {
        if (!terfiMumkun(context)) return
        if (sessionId.isBlank()) return
        val simdi = System.currentTimeMillis()
        val zaman = if (kind == "started") kapsulZamani(startedAt, simdi) else simdi
        val eski = oku(context)
        val yeni = kapsulOlayiUygula(
            eski, backend = backend, sessionId = sessionId, kind = kind, zamanMs = zaman,
            baslik = baslik, ozet = ozet, backendLabel = backendLabel, requestId = requestId,
        )
        if (yeni === eski) return
        yaz(context, yeni)
        ciz(context, kapsulDurumu(yeni))
    }

    /**
     * Bildirimdeki "İzin ver"/"Reddet" sonrası İYİMSER geçiş: ONAY → TUR.
     * Köprünün `started` push'u gerçeği 0-2 sn içinde getirir ve üzerine yazar;
     * bu yalnız o aradaki "hâlâ onay bekliyor" yalanını kapatır. Oturum
     * haritada yoksa hiçbir şey yapmaz (yeni tur uydurmaz).
     *
     * Kapsül BAŞKA bir onayı bekliyorsa (bayat bildirime basıldı, arada yeni
     * istek geldi) dokunulmaz: o onay hâlâ açık ve kapsül onu göstermeli.
     */
    @Synchronized
    fun onayCozuldu(context: Context, backend: String, sessionId: String, requestId: String) {
        if (!terfiMumkun(context)) return
        if (sessionId.isBlank()) return
        val eski = oku(context)
        val anahtar = notificationKey(backend, sessionId)
        val mevcut = eski[anahtar] ?: return
        if (mevcut.durum != OturumDurumu.ONAY) return
        if (mevcut.requestId.isNotBlank() && mevcut.requestId != requestId) return
        val yeni = eski + (anahtar to mevcut.copy(
            durum = OturumDurumu.TUR,
            baslangicMs = System.currentTimeMillis(),
            requestId = "",
        ))
        yaz(context, yeni)
        ciz(context, kapsulDurumu(yeni))
    }

    /** Silme yanıtındaki kimlikler disk veya canlı kabuk kimliği olabilir. */
    @Synchronized
    fun silinenOturumlariKaldir(context: Context, sessionIds: Set<String>) {
        if (sessionIds.isEmpty()) return
        val eski = oku(context)
        val yeni = eski.filterValues { it.sessionId !in sessionIds }
        if (yeni.size == eski.size) return
        yaz(context, yeni)
        ciz(context, kapsulDurumu(yeni))
    }

    /** Köprünün etkin operasyon listesi, kaçmış bitiş push'larını da temizler. */
    @Synchronized
    fun etkinOturumlarlaUzlastir(context: Context, etkinAnahtarlar: Set<String>) {
        val eski = oku(context)
        val yeni = eski.filterKeys { it in etkinAnahtarlar }
        if (yeni.size == eski.size) return
        yaz(context, yeni)
        ciz(context, kapsulDurumu(yeni))
    }

    // --- kapı ---------------------------------------------------------------

    // Terfi edemeyeceğimiz cihazda HİÇ bildirim atılmaz. Yoksa Tab S9'da (ve
    // API < 36'da) gölgede kalıcı bir "Ajan turu" satırı kalırdı — plan §6.
    @SuppressLint("NewApi")
    private fun terfiMumkun(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { nm.canPostPromotedNotifications() }.getOrDefault(false)
    }

    // --- çizim --------------------------------------------------------------

    @SuppressLint("NewApi")
    private fun ciz(context: Context, kapsul: Kapsul?) {
        val nmc = NotificationManagerCompat.from(context)
        if (kapsul == null) {
            nmc.cancel(KAPSUL_ID)
            if (BuildConfig.DEBUG) Log.d(TAG, "iptal (oturum kalmadi)")
            return
        }
        PushChannels.ensure(context)
        val etiket = kapsul.backendLabel.ifBlank { kapsul.backend }
        val govde = listOf(kapsul.baslik, kapsul.ozet)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { etiket }
        // Mevcut onay bildirimleriyle AYNI extra sözleşmesi (OnayBildirimi,
        // MainActivity bunları işliyor).
        val tapIntent = OnayBildirimi.onayEkraniNiyeti(context, kapsul.backend, kapsul.sessionId)
        val tap = PendingIntent.getActivity(
            context, KAPSUL_ID, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, PushChannels.CAPSULE)
            .setSmallIcon(if (kapsul.onay) R.drawable.ic_stat_onay else R.drawable.ic_stat_tur)
            // Kapsülde GÖRÜNMEZ ama boş olamaz (terfi şartı). Oturum sayısı
            // burada duruyor: kapsülün sağ slotu turda sayaca ayrıldı.
            .setContentTitle(kapsulBasligi(kapsul.onay, kapsul.sayi))
            .setContentText(govde)
            .setStyle(NotificationCompat.BigTextStyle().bigText(govde))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            // Kilit ekranında içeriksiz sürüm: oturum başlığı ve özet ancak kilit
            // açılınca görünür. Kapsülün başlığı (kaç oturum, onay mı tur mu)
            // hassas değil, kilit ekranı sürümünde de o kalıyor.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                kilitEkraniSurumu(
                    context, PushChannels.CAPSULE,
                    if (kapsul.onay) R.drawable.ic_stat_onay else R.drawable.ic_stat_tur,
                    kapsulBasligi(kapsul.onay, kapsul.sayi),
                ),
            )
            .setRequestPromotedOngoing(true)
            .setTimeoutAfter(ZAMAN_ASIMI_MS)
            .setContentIntent(tap)
        if (kapsul.onay) {
            // Rozet sayacı eziyor; onay bekleyen tur önceliklidir (bilinçli).
            builder.setShortCriticalText(kapsul.kisaMetin)
            builder.setUsesChronometer(false).setShowWhen(false)
            // Kapsül kendi kimliğiyle duruyor; tuşa basılınca iptal edilmez,
            // `onayCozuldu` onu TUR'a çevirir (etiket boş = alıcı dokunmaz).
            OnayBildirimi.eylemleriEkle(
                builder, context,
                backend = kapsul.backend,
                sessionId = kapsul.sessionId,
                requestId = kapsul.requestId,
                istekAnahtari = "kapsul:${kapsul.backend}:${kapsul.sessionId}",
                bildirimEtiketi = "",
                bildirimId = KAPSUL_ID,
            )
        } else {
            // setShortCriticalText HİÇ çağrılmıyor: sağ slot boş kalınca
            // kapsülde canlı sayaç akıyor (SONUC2 §2a, ölçüldü).
            builder.setWhen(kapsul.baslangicMs).setUsesChronometer(true).setShowWhen(true)
            val durdur = Intent(context, TurDurdurReceiver::class.java)
                .setAction(TurDurdurReceiver.ACTION)
                .putExtra("sessionId", kapsul.sessionId)
                .putExtra("backend", kapsul.backend)
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Durdur",
                PendingIntent.getBroadcast(
                    context, "kapsul-durdur:${kapsul.backend}:${kapsul.sessionId}".hashCode(), durdur,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        val bildirim = builder.build()
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "notify id=$KAPSUL_ID onay=${kapsul.onay} kisaMetin=${kapsul.kisaMetin} " +
                    "sayi=${kapsul.sayi} baslangicMs=${kapsul.baslangicMs} " +
                    "hasPromotableCharacteristics=${runCatching { bildirim.hasPromotableCharacteristics() }.getOrNull()}",
            )
        }
        runCatching { nmc.notify(KAPSUL_ID, bildirim) }
            .onFailure { if (BuildConfig.DEBUG) Log.d(TAG, "notify basarisiz: $it") }
        if (BuildConfig.DEBUG) bayrakLogu(context)
    }

    // Terfi GERÇEKTEN oldu mu: bildirim atıldıktan sonra sistemdeki kayda bak.
    // "Çağırdım" demek yetmez (plan §5) — bayrak sistemin kararıdır.
    private fun bayrakLogu(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val kayit = runCatching { nm.activeNotifications }.getOrNull()
            ?.firstOrNull { it.id == KAPSUL_ID }
        if (kayit == null) {
            Log.d(TAG, "activeNotifications icinde id=$KAPSUL_ID YOK")
            return
        }
        val bayraklar = kayit.notification.flags
        Log.d(
            TAG,
            "flags=0x${Integer.toHexString(bayraklar)} " +
                "PROMOTED_ONGOING=${(bayraklar and FLAG_PROMOTED_ONGOING) != 0}",
        )
    }

    // --- kalıcılık ----------------------------------------------------------

    private fun oku(context: Context): Map<String, KapsulOturumu> {
        val ham = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ANAHTAR, "[]").orEmpty()
        return runCatching {
            val dizi = JSONArray(ham)
            buildMap {
                for (i in 0 until dizi.length()) {
                    val o = dizi.optJSONObject(i) ?: continue
                    val backend = o.optString("backend")
                    val sessionId = o.optString("sessionId")
                    if (sessionId.isBlank()) continue
                    put(
                        notificationKey(backend, sessionId),
                        KapsulOturumu(
                            backend = backend,
                            sessionId = sessionId,
                            durum = if (o.optString("durum") == "ONAY") OturumDurumu.ONAY else OturumDurumu.TUR,
                            baslangicMs = o.optLong("baslangicMs"),
                            baslik = o.optString("baslik"),
                            ozet = o.optString("ozet"),
                            backendLabel = o.optString("backendLabel"),
                            requestId = o.optString("requestId"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun yaz(context: Context, oturumlar: Map<String, KapsulOturumu>) {
        val dizi = JSONArray()
        for (o in oturumlar.values) {
            dizi.put(
                JSONObject()
                    .put("backend", o.backend)
                    .put("sessionId", o.sessionId)
                    .put("durum", o.durum.name)
                    .put("baslangicMs", o.baslangicMs)
                    .put("baslik", o.baslik)
                    .put("ozet", o.ozet)
                    .put("backendLabel", o.backendLabel)
                    .put("requestId", o.requestId),
            )
        }
        // commit(): push alıcısı goAsync olmadan dönebiliyor, apply() yazmadan
        // süreç dondurulursa durum kaybolurdu (makbuz kaydında da aynı karar).
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ANAHTAR, dizi.toString()).commit()
    }
}
