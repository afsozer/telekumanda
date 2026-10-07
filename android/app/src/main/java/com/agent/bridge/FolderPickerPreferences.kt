package com.agent.bridge

import android.content.Context
import org.json.JSONArray

/**
 * Local folder picker preferences: recent (last 12) and favorites.
 * Stored in versioned JSON under SharedPreferences.
 * All path comparisons use slash-normalized lowercase keys.
 */
class FolderPickerPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("folder_picker", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_RECENT = "folder_picker_recent_v1"
        private const val KEY_FAVORITES = "folder_picker_favorites_v1"
        const val MAX_RECENT = 12
    }

    fun recent(): List<String> = readArray(KEY_RECENT)
    fun favorites(): List<String> = readArray(KEY_FAVORITES)

    fun addRecent(path: String) {
        val key = folderPathKey(path)
        val list = recent().toMutableList()
        list.removeAll { folderPathKey(it) == key }
        list.add(0, path)
        if (list.size > MAX_RECENT) list.subList(MAX_RECENT, list.size).clear()
        writeArray(KEY_RECENT, list)
    }

    fun removeRecent(path: String) {
        val key = folderPathKey(path)
        val list = recent().filter { folderPathKey(it) != key }
        writeArray(KEY_RECENT, list)
    }

    fun addFavorite(path: String) {
        val key = folderPathKey(path)
        val list = favorites().toMutableList()
        if (list.none { folderPathKey(it) == key }) {
            list.add(path)
            writeArray(KEY_FAVORITES, list)
        }
    }

    fun removeFavorite(path: String) {
        val key = folderPathKey(path)
        val list = favorites().filter { folderPathKey(it) != key }
        writeArray(KEY_FAVORITES, list)
    }

    fun isFavorite(path: String): Boolean {
        val key = folderPathKey(path)
        return favorites().any { folderPathKey(it) == key }
    }

    fun toggleFavorite(path: String): Boolean {
        return if (isFavorite(path)) { removeFavorite(path); false }
        else { addFavorite(path); true }
    }

    private fun readArray(key: String): List<String> = buildList {
        val raw = prefs.getString(key, null) ?: return emptyList<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) add(s)
            }
        } catch (_: Exception) {}
    }

    private fun writeArray(key: String, paths: List<String>) {
        val arr = JSONArray()
        for (p in paths) arr.put(p)
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
