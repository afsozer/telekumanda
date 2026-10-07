package com.agent.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address

// Güvenli ağ listesi: kablosuz hata ayıklamanın hangi Wi-Fi ağlarında kendi
// kendine açılabileceği.
//
// Gerekçe (kullanıcı, 22.08.2026): healer bağlanılan HER Wi-Fi'de ayarı geri
// açıyordu; sistem her açılışta "izin verilsin mi" diye soruyor, "Hayır"
// denince ayar 0'a dönüyor ve healer bir dakika sonra tekrar açıyordu — ev
// dışındaki ağlarda bitmeyen bir soru döngüsü. Artık liste dışındaki ağlarda
// ayara HİÇ dokunulmuyor: liste boşsa özellik fiilen kapalı.
//
// Ağ kimliği iki parçalı, çünkü Android'de ağ adını okumak izne bağlı:
//  - `ssid` — ACCESS_FINE_LOCATION verilmiş VE cihazda Konum servisi açıksa
//    okunur, aksi halde null. İzin adb'den bir kez veriliyor (WRITE_SECURE_SETTINGS
//    ile aynı yol), kullanıcıya çalışma anında sorulmuyor.
//  - `fingerprint` — hiçbir izin gerektirmeyen yedek: varsayılan ağ geçidi ve
//    cihazın IPv4 alt ağı. Konum kapalıyken tek dayanak bu.

/**
 * Bir Wi-Fi ağının kimliği. İki alan da null olabilir; ikisi birden null ise
 * kimlik kullanılamaz ve hiçbir şeyle eşleşmez.
 */
data class NetworkIdentity(val ssid: String?, val fingerprint: String?) {
    val usable: Boolean
        get() = !ssid.isNullOrBlank() || !fingerprint.isNullOrBlank()

    /**
     * Aynı ağ mı?
     *
     * İki tarafta da ad okunabiliyorsa KARAR ADINDIR ve parmak izine hiç
     * bakılmaz: DHCP alt ağı değişse bile ev ağı ev ağıdır, aynı alt ağı
     * kullanan bir kafe ağı ev ağı değildir (192.168.1.0/24 çok yaygın).
     * Ad taraflardan birinde yoksa (izin verilmemiş ya da Konum kapalı) elde
     * kalan tek şey parmak izidir.
     */
    fun matches(other: NetworkIdentity): Boolean {
        if (!usable || !other.usable) return false
        val a = ssid?.takeIf { it.isNotBlank() }
        val b = other.ssid?.takeIf { it.isNotBlank() }
        if (a != null && b != null) return a == b
        val fa = fingerprint?.takeIf { it.isNotBlank() } ?: return false
        val fb = other.fingerprint?.takeIf { it.isNotBlank() } ?: return false
        return fa == fb
    }

    /** Arayüzde ve günlükte görünen ad. Ad yoksa parmak izinin kendisi. */
    fun label(): String =
        ssid?.takeIf { it.isNotBlank() } ?: fingerprint?.takeIf { it.isNotBlank() } ?: "bilinmeyen ağ"
}

/**
 * Güvenli ağ listesinin kalıcı deposu.
 *
 * Kendi prefs dosyasında ("wireless_debug"), uygulamanın "settings" dosyasından
 * AYRI: o dosya köprü profilleriyle birlikte elle de düzenleniyor ve bu listenin
 * ona karışmasının bir faydası yok.
 */
object SafeNetworkStore {
    private const val PREFS = "wireless_debug"
    private const val KEY = "safe_networks"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(context: Context): List<NetworkIdentity> {
        val ham = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val dizi = JSONArray(ham)
            (0 until dizi.length()).mapNotNull { i ->
                val o = dizi.optJSONObject(i) ?: return@mapNotNull null
                val kimlik = NetworkIdentity(
                    ssid = o.optString("ssid").takeIf { it.isNotBlank() },
                    fingerprint = o.optString("fingerprint").takeIf { it.isNotBlank() },
                )
                kimlik.takeIf { it.usable }
            }
        }.getOrDefault(emptyList())
    }

    fun isSafe(context: Context, current: NetworkIdentity): Boolean =
        list(context).any { it.matches(current) }

    /** Zaten eşleşen bir kayıt varsa onu GÜNCELLER (ad sonradan okunabilir olabilir). */
    fun add(context: Context, network: NetworkIdentity) {
        if (!network.usable) return
        val yeni = list(context).filterNot { it.matches(network) } + network
        save(context, yeni)
    }

    fun remove(context: Context, network: NetworkIdentity) {
        save(context, list(context).filterNot { it == network })
    }

    private fun save(context: Context, networks: List<NetworkIdentity>) {
        val dizi = JSONArray()
        networks.forEach { a ->
            dizi.put(
                JSONObject().apply {
                    a.ssid?.let { put("ssid", it) }
                    a.fingerprint?.let { put("fingerprint", it) }
                },
            )
        }
        prefs(context).edit().putString(KEY, dizi.toString()).apply()
    }
}

/**
 * Şu an bağlı olunan Wi-Fi ağının kimliği; Wi-Fi'de değilsek null.
 *
 * Mobil veride null dönüyor — kablosuz hata ayıklama zaten Wi-Fi'ye bağlı.
 */
fun currentNetworkIdentity(context: Context): NetworkIdentity? {
    val app = context.applicationContext
    val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(network) ?: return null
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
    val kimlik = NetworkIdentity(readSsid(app), fingerprintOf(cm, network))
    return kimlik.takeIf { it.usable }
}

/** Ağ adı okunabiliyor mu? Arayüzde "neden isim yerine sayı görüyorum"u açıklamak için. */
fun canReadSsid(context: Context): Boolean =
    context.applicationContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun readSsid(app: Context): String? {
    if (!canReadSsid(app)) return null
    val wm = app.getSystemService(WifiManager::class.java) ?: return null
    // getConnectionInfo API 31'de kullanımdan kaldırıldı ama yerine gelen yol
    // (NetworkCapabilities.transportInfo) eşzamanlı sorguda adı zaten
    // maskeliyor; izin varken çalışan tek yol bu.
    @Suppress("DEPRECATION")
    val ham = wm.connectionInfo?.ssid ?: return null
    val temiz = ham.trim().removeSurrounding("\"")
    // Konum servisi kapalıyken sistem gerçek adı vermez; bu iki değer "ad yok"
    // demektir ve kaydedilirse hiçbir işe yaramaz.
    if (temiz.isBlank() || temiz == "<unknown ssid>" || temiz == "0x") return null
    return temiz
}

/**
 * İzin gerektirmeyen ağ parmak izi: "alt ağ@ağ geçidi", örn. "192.168.1.0/24@192.168.1.1".
 *
 * LinkProperties konum-duyarlı sayılmadığı için izinsiz okunur. Kimliği zayıf
 * olan taraf bu: aynı alt ağı kullanan başka bir router aynı parmak izini
 * üretir; bu yüzden ad okunabildiğinde ada öncelik veriliyor (bkz. matches).
 */
private fun fingerprintOf(cm: ConnectivityManager, network: Network): String? {
    val lp = cm.getLinkProperties(network) ?: return null
    val gecit = lp.routes
        .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
        ?.gateway?.hostAddress
    val altAg = lp.linkAddresses
        .firstOrNull { it.address is Inet4Address }
        ?.let { subnetOf(it.address.address, it.prefixLength) }
    if (gecit == null && altAg == null) return null
    return listOfNotNull(altAg, gecit).joinToString("@")
}

/** 192.168.1.37 + 24 -> "192.168.1.0/24". Cihazın IP'si DHCP ile değişir, alt ağ değişmez. */
internal fun subnetOf(address: ByteArray, prefixLength: Int): String? {
    if (address.size != 4 || prefixLength !in 0..32) return null
    val maskeli = ByteArray(4)
    for (i in 0 until 4) {
        val kalan = prefixLength - i * 8
        val maske = when {
            kalan >= 8 -> 0xFF
            kalan <= 0 -> 0x00
            else -> (0xFF shl (8 - kalan)) and 0xFF
        }
        maskeli[i] = (address[i].toInt() and maske).toByte()
    }
    return maskeli.joinToString(".") { (it.toInt() and 0xFF).toString() } + "/" + prefixLength
}
