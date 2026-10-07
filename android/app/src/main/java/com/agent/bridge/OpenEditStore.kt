package com.agent.bridge

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// OpenEditRecord + OpenEditStore arayüzü shared modülünde (OpenEditStore.kt).
// Burada yalnız Android'e özgü SharedPreferences gerçeklemesi kalır.

class PrefsOpenEditStore(private val prefs: SharedPreferences) : OpenEditStore {

    override fun put(record: OpenEditRecord) {
        val list = all().filterNot { it.remotePath == record.remotePath } + record
        save(list)
    }

    override fun all(): List<OpenEditRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(OpenEditRecord(
                        remotePath = o.optString("remotePath"),
                        localPath = o.optString("localPath"),
                        baselineMtime = o.optLong("baselineMtime"),
                        baselineSize = o.optLong("baselineSize"),
                        baselineHash = o.optString("baselineHash"),
                        openedAt = o.optLong("openedAt"),
                        coworkOnly = if (o.has("coworkOnly")) o.optBoolean("coworkOnly") else true,
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun remove(remotePath: String) {
        save(all().filterNot { it.remotePath == remotePath })
    }

    private fun save(list: List<OpenEditRecord>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("remotePath", r.remotePath); put("localPath", r.localPath)
                put("baselineMtime", r.baselineMtime); put("baselineSize", r.baselineSize)
                put("baselineHash", r.baselineHash)
                put("openedAt", r.openedAt)
                put("coworkOnly", r.coworkOnly)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object { private const val KEY = "open_edit_records" }
}
