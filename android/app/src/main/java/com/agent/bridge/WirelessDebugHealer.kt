package com.agent.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

// Kablosuz hata ayıklama iyileştiricisi (tablet için): Android 11+ "Kablosuz hata
// ayıklama" özelliği ağ değişince KENDİNİ KAPATIR ve cihaz ADB'den düşer; kablosuz
// tamiri de elle Geliştirici seçeneklerine girmeyi gerektirir. Uygulama
// WRITE_SECURE_SETTINGS iznine sahipse (adb'den bir kez: `pm grant com.agent.bridge
// android.permission.WRITE_SECURE_SETTINGS`) ayarı Wi-Fi geri geldiğinde kendisi
// açar; PC tarafındaki köprü keepalive'ı da portu (tcpip 5555) yeniden kurar.
// İzin verilmemiş cihazda (telefon) sessiz no-op. Süreç ölürse kalıcı
// WirelessDebugWorker uygun sistem koşullarında yaklaşık 15 dakikalık
// periyotlarla kontrolü yeniden çalıştırır.
//
// YALNIZ GÜVENLİ AĞLARDA (22.08.2026). Eskiden bağlanılan her Wi-Fi'de ayar
// açılıyordu; sistem her açılışta izin sorduğu için ev dışındaki ağlarda
// bitmeyen bir soru döngüsü oluşuyordu (kullanıcı bildirdi). Artık ağ
// `SafeNetworkStore`'daki listede değilse ayara hiç dokunulmuyor — liste boşsa
// özellik fiilen kapalıdır. Liste uygulamadan yönetiliyor
// (Ayarlar > Kablosuz hata ayıklama).
internal interface WirelessDebugAccess {
    fun hasWifi(): Boolean
    fun isNetworkSafe(): Boolean
    fun isEnabled(): Boolean
    fun enable(): Boolean
}

internal enum class WirelessDebugHealOutcome {
    NO_WIFI,
    UNSAFE_NETWORK,
    ALREADY_ENABLED,
    ENABLED,
    WRITE_REJECTED,
}

internal fun runWirelessDebugHeal(access: WirelessDebugAccess): WirelessDebugHealOutcome {
    if (!access.hasWifi()) return WirelessDebugHealOutcome.NO_WIFI
    // Güvenli değilse ayar OKUNMAZ ve YAZILMAZ. Kullanıcı orada kablosuz hata
    // ayıklamayı kendi eliyle açtıysa ona da dokunmuyoruz; kapatmak bizim işimiz
    // değil, sadece kendi kendine açmayı bırakıyoruz.
    if (!access.isNetworkSafe()) return WirelessDebugHealOutcome.UNSAFE_NETWORK
    if (access.isEnabled()) return WirelessDebugHealOutcome.ALREADY_ENABLED
    if (!access.enable()) return WirelessDebugHealOutcome.WRITE_REJECTED
    return if (access.isEnabled()) {
        WirelessDebugHealOutcome.ENABLED
    } else {
        WirelessDebugHealOutcome.WRITE_REJECTED
    }
}

/**
 * "Hayır" cevabını sezip dakikalık denemeyi susturan geri çekilme.
 *
 * Güvenli ağda bile kullanıcı sistemin sorusuna "Hayır" diyebilir; ayar 0'a
 * döner, healer bir dakika sonra tekrar açar ve soru geri gelir. Bunu ayrı bir
 * sinyalle anlayamıyoruz — sistem diyaloğu bize haber vermiyor — ama SONUÇ
 * DİZİSİ yeterince ayırt edici: kullanıcı "İzin ver" derse bir sonraki tur
 * ALREADY_ENABLED olur; "Hayır" derse ayar yine kapalı bulunur ve tur yine
 * ENABLED üretir. Yani AYNI AĞDA arka arkaya gelen ENABLED, ayarı birinin
 * sürekli kapattığı anlamına gelir.
 *
 * Ağ değişimi bu sayacı bozmaz: healer'ın asıl derdi olan "ağ değişince kapandı"
 * durumunda kimlik de değiştiği için sayaç sıfırlanır.
 */
internal class HealBackoff(
    private val limit: Int = 3,
    private val cooldownMs: Long = 30 * 60_000L,
) {
    private var key: String? = null
    private var strikes = 0
    private var quietUntil = 0L

    fun allowed(identity: String, now: Long): Boolean = key != identity || now >= quietUntil

    fun record(identity: String, outcome: WirelessDebugHealOutcome, now: Long) {
        if (key != identity) {
            key = identity
            strikes = 0
            quietUntil = 0L
        }
        when (outcome) {
            // Bir kez açmak normaldir (ağ yeni geldi). Üst üste üç kez açmak,
            // arada birinin kapattığı anlamına gelir.
            WirelessDebugHealOutcome.ENABLED,
            // Yazma reddediliyorsa dakikada bir tekrar denemenin karşılığı yok.
            WirelessDebugHealOutcome.WRITE_REJECTED,
            -> {
                strikes += 1
                if (strikes >= limit) {
                    quietUntil = now + cooldownMs
                    strikes = 0
                }
            }
            WirelessDebugHealOutcome.ALREADY_ENABLED -> {
                strikes = 0
                quietUntil = 0L
            }
            else -> Unit
        }
    }
}

object WirelessDebugHealer {
    private var callbackRegistered = false
    private var timerStarted = false
    private val backoff = HealBackoff()

    fun start(context: Context) {
        val app = context.applicationContext
        heal(app)
        WirelessDebugWork.schedule(app)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return

        if (!callbackRegistered) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            runCatching {
                cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // Bu callback zaten Wi-Fi isteği için geldi; callback içinden
                        // senkron network sorgusu yapmak yarışa açık olduğundan doğrudan belirt.
                        heal(app, wifiKnownAvailable = true)
                        // Callback anında `activeNetwork` henüz yeni ağa geçmemiş
                        // olabiliyor; o an ağın kimliği okunamazsa güvenli listeyle
                        // karşılaştırma da yapılamaz. Kısa bir gecikmeyle bir kez
                        // daha dene ki ev ağına dönüşte dakikalık turu beklemeyelim.
                        handler.postDelayed({ heal(app) }, 3_000)
                    }
                })
                callbackRegistered = true
            }.onFailure {
                Log.d("AgHeal", "Wi-Fi callback kaydı başarısız: ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        if (timerStarted) return
        timerStarted = true
        // Pencere modunda aktivite arka planda da RESUMED kalabildiğinden yaşam
        // döngüsü olayları güvenilir tetik değil (tablette gözlendi: öne getirmek
        // ON_START üretmiyor). Süreç yaşadıkça dakikalık sessiz kontrol en sağlam
        // ağ: maliyeti tek bir settings okuması.
        val tick = object : Runnable {
            override fun run() {
                heal(app)
                handler.postDelayed(this, 60_000)
            }
        }
        handler.postDelayed(tick, 60_000)
    }

    fun heal(context: Context, wifiKnownAvailable: Boolean = false): Boolean = runCatching {
        val app = context.applicationContext
        // Bu ayrıcalık yalnız tablete bir kez adb ile verildi. Telefonda her
        // dakika SecurityException/log üretmek yerine gerçekten sessiz no-op.
        if (app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        val kimlik = currentNetworkIdentity(app)
        val anahtar = kimlik?.label() ?: "-"
        val simdi = SystemClock.elapsedRealtime()
        if (!backoff.allowed(anahtar, simdi)) return false

        val resolver = context.contentResolver
        val access = object : WirelessDebugAccess {
            override fun hasWifi(): Boolean = wifiKnownAvailable || kimlik != null

            override fun isNetworkSafe(): Boolean =
                kimlik != null && SafeNetworkStore.isSafe(app, kimlik)

            override fun isEnabled(): Boolean =
                Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1

            override fun enable(): Boolean =
                Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        }

        val sonuc = runWirelessDebugHeal(access)
        backoff.record(anahtar, sonuc, simdi)
        when (sonuc) {
            WirelessDebugHealOutcome.ENABLED -> {
                Log.d("AgHeal", "kablosuz hata ayıklama yeniden açıldı ($anahtar)")
                true
            }
            WirelessDebugHealOutcome.WRITE_REJECTED -> {
                Log.d("AgHeal", "kablosuz hata ayıklama yazımı sistemce doğrulanmadı")
                false
            }
            else -> false
        }
    }.getOrElse {
        // İzin/engel teşhisi logcat'ten okunabilsin.
        Log.d("AgHeal", "heal başarısız: ${it.javaClass.simpleName}: ${it.message}")
        false
    }
}
