package com.agent.bridge

/**
 * Basit anahtar-değer deposu için platform arayüzü (Faz 1 platform-arayüz deseni).
 *
 * - Android gerçeklemesi: PrefsKeyValueStore (SharedPreferences — app modülü)
 * - Desktop gerçeklemesi (Faz 2): dosya/Properties tabanlı
 *
 * TabsDelegate gibi küçük kalıcı-durum tutan delegate'ler yalnız bu arayüzü bilir.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}
