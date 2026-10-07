package com.agent.bridge

import org.json.JSONArray
import org.json.JSONObject

data class BridgeProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val token: String,
) {
    fun settings(): BridgeSettings = BridgeSettings(baseUrl, token)
}

object BridgeProfilesJson {
    fun encode(profiles: List<BridgeProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("baseUrl", profile.baseUrl)
                    .put("token", profile.token),
            )
        }
        return array.toString()
    }

    fun decode(raw: String): List<BridgeProfile> {
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    BridgeProfile(
                        id = id,
                        name = item.optString("name"),
                        baseUrl = item.optString("baseUrl"),
                        token = item.optString("token"),
                    ),
                )
            }
        }
    }
}
