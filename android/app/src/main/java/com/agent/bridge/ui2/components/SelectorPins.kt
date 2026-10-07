package com.agent.bridge.ui2.components

import android.content.Context

// Seçici sabitlemesi (yıldız): sık kullanılan model uzun katalogda dibe
// gömülmesin. ESKİ karar (cihaz-yerel SharedPreferences) güncellendi
// (27.08.2026): yıldızlar KÖPRÜDE saklanır (GET/POST /ui-pins), böylece aynı
// köprüye bağlı telefon ve tablet aynı seti görür. Buradaki Preferences yalnız
// ÇEVRİMDIŞI önbellek ve UI'nın anında tepkisi içindir — gerçek kaynak köprü.
//
// Yalnız UI tarafı: backend'lere sıralama etkisi cihazda hesaplanır; köprü kendi
// tarafında hiçbir sıralama yapmaz.

// Sabitliler başa alınır, iki grup da KATALOG sırasını korur. Alfabetik sıralama
// yapmıyoruz: katalog sırası anlamlı (sağlayıcı önerdiği modeli üste koyuyor) ve
// yeniden sıralamak seçiciyi tanınmaz hale getirir.
fun <T> sortByPins(
    options: List<SelectorOption<T>>,
    pinned: Set<String>,
    key: (T) -> String,
): List<SelectorOption<T>> {
    if (pinned.isEmpty()) return options
    val (basa, kalan) = options.partition { key(it.value) in pinned }
    return basa + kalan
}

fun togglePin(pinned: Set<String>, key: String): Set<String> =
    if (key in pinned) pinned - key else pinned + key

/**
 * Yerel→köprü yönünü dinleyen tek kanal. SelectorPinStore.save() her yazışta
 * [notify] çağırır; abone genelde RemoteViewModel'dir ve yazarları köprüye
 * POST eder (son yazan kazanır — bkz. bridge/ui-pins.mjs).
 *
 * Abonesiz çalışma NORMAL: push başarısız olabilir (çevrimdışı, köprü kapalı),
 * o durumda yerel değer doğru kalır ve sıradaki pull hizalar.
 */
interface SelectorPinListener {
    fun onPinsChanged(scope: String, keys: Set<String>)
}

object SelectorPinBridge {
    @Volatile
    var listener: SelectorPinListener? = null

    fun notify(scope: String, keys: Set<String>) {
        listener?.onPinsChanged(scope, keys)
    }
}

// Sabitlemeler kapsam başına ayrı tutulur: her backend'in kendi model listesi
// var.
class SelectorPinStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pinned(scope: String): Set<String> =
        // getStringSet'in döndürdüğü set'i DEĞİŞTİRMEK yasak (belgelenmiş
        // Android tuzağı: içeriği tanımsız hale geliyor) — kopyasını veriyoruz.
        prefs.getStringSet(key(scope), null)?.toSet() ?: emptySet()

    fun save(scope: String, pinned: Set<String>) {
        prefs.edit().putStringSet(key(scope), pinned.toSet()).apply()
        // Köprüye giden yol aboneye bağlı; abone yoksa sessizce lokalde kalır.
        SelectorPinBridge.notify(scope, pinned)
    }

    /**
     * Köprünün TAM tablosunu yerel önbelleğe yükler. Eski yerel anahtarların
     * hepsi silinir: köprü kaynak sayılır, eksik kapsamın fazladan değeri
     * hayatta kalamaz (pull-bağımsız "hayalet yıldız" bırakmayalım).
     */
    fun replaceAll(map: Map<String, List<String>>) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach(editor::remove)
        map.forEach { (scope, keys) ->
            if (keys.isNotEmpty()) editor.putStringSet(key(scope), keys.toSet())
        }
        editor.apply()
    }

    /** Yerelde kayıtlı TÜM kapsamlar — köprüye ilk taşıma (migration) için. */
    fun allScopes(): Map<String, Set<String>> =
        prefs.all.entries.mapNotNull { (name, value) ->
            if (!name.startsWith(KEY_PREFIX)) return@mapNotNull null
            val scope = name.removePrefix(KEY_PREFIX)
            if (scope.isEmpty() || value !is Set<*>) null
            else scope to value.filterIsInstance<String>().toSet()
        }.toMap()

    private fun key(scope: String) = "$KEY_PREFIX$scope"

    companion object {
        const val KEY_PREFIX = "pins_v1_"
        private const val PREFS = "selector_pins"
    }
}
