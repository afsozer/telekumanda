package com.agent.bridge

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

// MCP sunucu yönetimi — BridgeClient'tan ayrılan extension fonksiyonları (madde 12.1).
// bridge/routes/mcp.mjs ile eşleşir (genel /mcp/*) + backend MCP (/claude-app/mcp/*,
// /<prefix>/mcp/* — backend route'unda kayıtlı ama Android'de tek MCP sorumluluk birimi).
// Çağıran kod (McpDelegate) değişmez; fonksiyonlar aynı imzayı korur, artık BridgeClient
// receiver'lı extension. Davranış değişikliği yok.

// --- MCP server registry (Antigravity) ---
suspend fun BridgeClient.mcpServers(settings: BridgeSettings): List<McpServer> {
    val json = getJson(settings, "/mcp/servers")
    val array = json.optJSONArray("servers") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val argsArr = item.optJSONArray("args")
            val args = buildList { if (argsArr != null) for (j in 0 until argsArr.length()) add(argsArr.optString(j)) }
            add(McpServer(
                name = item.optString("name"),
                enabled = item.optBoolean("enabled", true),
                type = item.optString("type"),
                url = item.optString("url"),
                command = item.optString("command"),
                args = args,
            ))
        }
    }
}

suspend fun BridgeClient.mcpSave(settings: BridgeSettings, name: String, type: String, url: String, command: String) {
    val body = JSONObject().put("name", name).put("type", type)
    if (type == "remote") body.put("url", url) else body.put("command", command)
    val json = postJson(settings, "/mcp/server", body)
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "MCP kaydedilemedi"))
}

suspend fun BridgeClient.mcpRemove(settings: BridgeSettings, name: String) {
    val json = postJson(settings, "/mcp/remove", JSONObject().put("name", name))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "MCP silinemedi"))
}

suspend fun BridgeClient.mcpToggle(settings: BridgeSettings, name: String, enabled: Boolean) {
    val json = postJson(settings, "/mcp/toggle", JSONObject().put("name", name).put("enabled", enabled))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "MCP durumu değişmedi"))
}

// Tell Antigravity to re-read its MCP config and reconnect servers (gRPC RefreshMcpServers).
suspend fun BridgeClient.mcpRefresh(settings: BridgeSettings) {
    postJson(settings, "/mcp/refresh", JSONObject())
}

// --- Claude App MCP (hesaba duyarlı; <configDir>/.claude.json) ---
suspend fun BridgeClient.claudeAppMcpServers(settings: BridgeSettings): List<McpServer> {
    val json = getJson(settings, "/claude-app/mcp/servers")
    val array = json.optJSONArray("servers") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val argsArr = item.optJSONArray("args")
            val args = buildList { if (argsArr != null) for (j in 0 until argsArr.length()) add(argsArr.optString(j)) }
            add(McpServer(
                name = item.optString("name"),
                enabled = item.optBoolean("enabled", true),
                type = item.optString("type"),
                url = item.optString("url"),
                command = item.optString("command"),
                args = args,
                status = item.optString("status"),
                managed = item.optBoolean("managed", false),
            ))
        }
    }
}

suspend fun BridgeClient.claudeAppMcpSave(settings: BridgeSettings, name: String, type: String, url: String, command: String) {
    val body = JSONObject().put("name", name).put("type", type)
    if (type == "remote") body.put("url", url) else body.put("command", command)
    val json = postJson(settings, "/claude-app/mcp/server", body)
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "MCP kaydedilemedi"))
}

suspend fun BridgeClient.claudeAppMcpRemove(settings: BridgeSettings, name: String) {
    val json = postJson(settings, "/claude-app/mcp/remove", JSONObject().put("name", name))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "MCP silinemedi"))
}

suspend fun BridgeClient.claudeAppMcpToggle(settings: BridgeSettings, name: String, enabled: Boolean) {
    val json = postJson(settings, "/claude-app/mcp/toggle", JSONObject().put("name", name).put("enabled", enabled))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "MCP durumu değişmedi"))
}

// --- Backend-genel MCP (codex-app / opencode-app; /<prefix>/mcp/*) ---
// Her backend kendi CLI config'ini yönetir (codex: config.toml, opencode:
// opencode.json). Cevap şekli claude-app MCP ile aynı.
private fun parseMcpServers(json: JSONObject): List<McpServer> {
    val array = json.optJSONArray("servers") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val argsArr = item.optJSONArray("args")
            val args = buildList { if (argsArr != null) for (j in 0 until argsArr.length()) add(argsArr.optString(j)) }
            add(McpServer(
                name = item.optString("name"),
                enabled = item.optBoolean("enabled", true),
                type = item.optString("type"),
                url = item.optString("url"),
                command = item.optString("command"),
                args = args,
                status = item.optString("status"),
                managed = item.optBoolean("managed", false),
            ))
        }
    }
}

suspend fun BridgeClient.backendMcpServers(settings: BridgeSettings, prefix: String): List<McpServer> {
    val json = getJson(settings, "/$prefix/mcp/servers")
    if (!json.optBoolean("ok", true)) throw IOException(json.optString("error", "MCP listesi alınamadı"))
    return parseMcpServers(json)
}

suspend fun BridgeClient.backendMcpSave(settings: BridgeSettings, prefix: String, name: String, type: String, url: String, command: String) {
    val body = JSONObject().put("name", name).put("type", type)
    if (type == "remote") body.put("url", url) else body.put("command", command)
    val json = postJson(settings, "/$prefix/mcp/server", body)
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "MCP kaydedilemedi"))
}

suspend fun BridgeClient.backendMcpRemove(settings: BridgeSettings, prefix: String, name: String) {
    val json = postJson(settings, "/$prefix/mcp/remove", JSONObject().put("name", name))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "MCP silinemedi"))
}

suspend fun BridgeClient.backendMcpToggle(settings: BridgeSettings, prefix: String, name: String, enabled: Boolean) {
    val json = postJson(settings, "/$prefix/mcp/toggle", JSONObject().put("name", name).put("enabled", enabled))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "MCP durumu değişmedi"))
}
