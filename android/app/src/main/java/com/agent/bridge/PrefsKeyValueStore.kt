package com.agent.bridge

import android.content.SharedPreferences

// KeyValueStore'un SharedPreferences gerçeklemesi (Android'e özel; shared'daki
// PrefsOpenEditStore deseninin aynısı).
class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
