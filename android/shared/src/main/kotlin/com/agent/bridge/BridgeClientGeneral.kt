package com.agent.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

// Genel (cross-cutting, backend-dışı) API — BridgeClient'tan ayrılan extension
// fonksiyonları (madde 12.2). bridge/routes/general.mjs ile eşleşir:
// /health, /active-state, /file, /download, /dirs, /bridge/restart, /slash,
// /context/file, /upload, /delete, /rename, /move, /usage + process admin.
// Çağıran kod değişmez; fonksiyonlar aynı imzayı korur, artık BridgeClient receiver'lı
// extension. Davranış değişikliği yok.

suspend fun BridgeClient.health(settings: BridgeSettings): HealthResult {
    val json = getJson(settings, "/health")
    return HealthResult(json.optBoolean("ok"), json.optInt("protocolVersion"), json.optInt("protocolMinClient"))
}

// Bridge'in yayınladığı versiyonlu backend yetenek kataloğu (Faz 4). Android
// kontrolleri buradan üretir; erişilemezse gömülü enum yedeği devreye girer.
suspend fun BridgeClient.backendCatalog(settings: BridgeSettings): BackendCatalogInfo {
    val json = getJson(settings, "/backends")
    val arr = json.optJSONArray("backends") ?: return BackendCatalogInfo()
    val entries = buildMap {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val caps = o.optJSONObject("capabilities")
            put(id, BackendCapabilities(
                approvals = caps?.optBoolean("approvals", false) ?: false,
                userInput = caps?.optBoolean("userInput", false) ?: false,
                userInputSteer = caps?.optBoolean("userInputSteer", false) ?: false,
                userInputQueue = caps?.optBoolean("userInputQueue", false) ?: false,
                permissionModes = caps?.optBoolean("permissionModes", false) ?: false,
                context = caps?.optBoolean("context", true) ?: true,
                plan = caps?.optBoolean("plan", false) ?: false,
                outputs = caps?.optBoolean("outputs", false) ?: false,
            ))
        }
    }
    return BackendCatalogInfo(json.optInt("contractVersion"), entries)
}

suspend fun BridgeClient.restartBridge(settings: BridgeSettings): String {
    val json = postJson(settings, "/bridge/restart", JSONObject())
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Bridge restart failed"))
    return json.optString("path")
}

suspend fun BridgeClient.activeState(settings: BridgeSettings): ActiveStateResult {
    val json = getJson(settings, "/active-state")
    val modulesObj = json.optJSONObject("modules")
    val modulesMap = buildMap {
        if (modulesObj != null) {
            val keys = modulesObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val m = modulesObj.optJSONObject(key)
                if (m != null) {
                    put(key, ModuleHealth(
                        status = m.optString("status"),
                        pid = if (m.isNull("pid")) null else m.optInt("pid"),
                        sessionCount = m.optInt("sessionCount")
                    ))
                }
            }
        }
    }
    val sysObj = json.optJSONObject("system")
    val systemMetrics = if (sysObj != null) {
        SystemMetrics(
            cpu = sysObj.optInt("cpu"),
            memory = sysObj.optInt("memory"),
            platform = sysObj.optString("platform"),
            uptime = sysObj.optLong("uptime")
        )
    } else null

    return ActiveStateResult(
        running = json.optBoolean("running"),
        backend = json.optString("backend"),
        sessionId = json.optString("sessionId"),
        title = json.optString("title"),
        modules = modulesMap,
        system = systemMetrics
    )
}

suspend fun BridgeClient.operations(settings: BridgeSettings, limit: Int = 100): OperationsResult {
    val json = getJson(settings, "/operations?limit=${limit.coerceIn(1, 250)}")
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Operations failed"))
    val operations = buildList {
        val arr = json.optJSONArray("operations")
        if (arr != null) for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            add(OperationItem(
                id = item.optString("id"), backend = item.optString("backend"), backendLabel = item.optString("backendLabel"),
                sessionId = item.optString("sessionId"), cwd = item.optString("cwd"), model = item.optString("model"),
                title = item.optString("title"), summary = item.optString("summary"), status = item.optString("status"),
                needsAttention = item.optBoolean("needsAttention"), updatedAt = item.optString("updatedAt"),
                diskId = item.optString("diskId"),
            ))
        }
    }
    val events = parseOperationEvents(json.optJSONArray("events"))
    val counts = json.optJSONObject("counts")
    return OperationsResult(
        operations = operations,
        events = events,
        counts = OperationCounts(counts?.optInt("running") ?: 0, counts?.optInt("waiting") ?: 0, counts?.optInt("failed") ?: 0),
    )
}

private fun parseOperationEvents(arr: org.json.JSONArray?): List<OperationEvent> = buildList {
    if (arr != null) for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        add(OperationEvent(
            id = item.optString("id"), kind = item.optString("kind"), status = item.optString("status"),
            backend = item.optString("backend"), backendLabel = item.optString("backendLabel"),
            sessionId = item.optString("sessionId"), cwd = item.optString("cwd"), model = item.optString("model"),
            title = item.optString("title"), summary = item.optString("summary"), at = item.optString("at"),
            diskId = item.optString("diskId"),
        ))
    }
}

private fun parseProjectSummary(item: JSONObject): ProjectSummary {
    val quickStart = item.optJSONObject("quickStart")
    val provider = quickStart?.optString("provider").orEmpty()
    val model = if (provider.isNotEmpty()) quickStart?.optJSONObject("modelByProvider")?.optString(provider).orEmpty() else ""
    val permissionMode = if (provider.isNotEmpty()) quickStart?.optJSONObject("permissionByProvider")?.optString(provider).orEmpty() else ""
    val effort = if (provider.isNotEmpty()) quickStart?.optJSONObject("effortByProvider")?.optString(provider).orEmpty() else ""

    return ProjectSummary(
        id = item.optString("id"), path = item.optString("path"), name = item.optString("name"),
        displayName = item.optString("displayName", item.optString("name")), exists = item.optBoolean("exists", true),
        sessionCount = item.optInt("sessionCount"), runningCount = item.optInt("runningCount"), outputCount = item.optInt("outputCount"), newOutputCount = item.optInt("newOutputCount"),
        providers = buildList {
            val arr = item.optJSONArray("providers")
            if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
        },
        pinned = item.optBoolean("pinned", false),
        lastOpenedAt = item.optString("lastOpenedAt", ""),
        lastActivityAt = item.optString("lastActivityAt", ""),
        quickStartProvider = provider,
        quickStartModel = model,
        quickStartPermissionMode = permissionMode,
        quickStartEffort = effort
    )
}

suspend fun BridgeClient.projects(settings: BridgeSettings): List<ProjectSummary> {
    val json = getJson(settings, "/projects")
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Projects failed"))
    val arr = json.optJSONArray("projects") ?: return emptyList()
    return buildList { for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(parseProjectSummary(it)) } }
}

suspend fun BridgeClient.projectDetail(settings: BridgeSettings, id: String): ProjectDetail {
    val json = getJson(settings, "/projects/detail?id=${URLEncoder.encode(id, "UTF-8")}")
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Project detail failed"))
    val projectJson = json.optJSONObject("project")
    val sessions = buildList {
        val arr = json.optJSONArray("sessions")
        if (arr != null) for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            add(ProjectSession(
                backend = item.optString("backend"),
                backendLabel = item.optString("backendLabel"),
                sessionId = item.optString("sessionId"),
                model = item.optString("model"),
                status = item.optString("status"),
                summary = item.optString("summary"),
                title = item.optString("title"),
                nativeSessionId = item.optString("nativeSessionId"),
                mtime = item.optLong("mtime"),
                live = item.optBoolean("live", true),
                pinned = item.optBoolean("pinned", false),
                archived = item.optBoolean("archived", false),
                container = item.optString("container", "direct"),
                threadId = item.optString("threadId")
            ))
        }
    }
    val outputs = buildList {
        val arr = json.optJSONArray("outputs")
        if (arr != null) for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            add(ProjectDelivery(item.optString("name"), item.optString("path"), item.optLong("size"), item.optDouble("mtime"), item.optBoolean("isNew")))
        }
    }
    val artifacts = json.optJSONObject("artifacts")
    fun parseArtifacts(name: String, titleKeys: List<String>): List<ProjectArtifact> = buildList {
        val arr = artifacts?.optJSONArray(name)
        if (arr != null) for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = titleKeys.firstNotNullOfOrNull { key -> item.optString(key).takeIf(String::isNotBlank) }.orEmpty()
            add(ProjectArtifact(item.optString("backend"), title, item.optString("status", item.optString("summary"))))
        }
    }
    fun stringList(name: String, parent: JSONObject?): List<String> = buildList {
        val arr = parent?.optJSONArray(name)
        if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
    }
    val securityJson = json.optJSONObject("security")
    val customPolicyJson = securityJson?.optJSONObject("customPolicy")
    val customPermissions = buildMap {
        val values = customPolicyJson?.optJSONObject("permissions")
        if (values != null) for (key in values.keys()) put(key, values.optString(key))
    }
    val audit = buildList {
        val arr = json.optJSONArray("audit")
        if (arr != null) for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            add(ProjectAuditEvent(item.optString("id"), item.optString("action"), item.optString("actor"), item.optJSONObject("detail")?.toString().orEmpty(), item.optString("at")))
        }
    }
    val mcpProfileJson = json.optJSONObject("mcpProfile")
    return ProjectDetail(
        project = projectJson?.let(::parseProjectSummary), sessions = sessions, outputs = outputs,
        changes = parseArtifacts("changes", listOf("path", "summary")),
        commands = parseArtifacts("commands", listOf("command", "text", "summary")),
        plan = parseArtifacts("plan", listOf("text", "summary")),
        security = ProjectSecurity(
            profile = securityJson?.optString("profile", "standard") ?: "standard",
            readRoots = stringList("readRoots", securityJson),
            writeRoots = stringList("writeRoots", securityJson),
            customPermissions = customPermissions,
        ),
        audit = audit,
        mcpProfile = ProjectMcpProfile(mcpProfileJson?.optString("provider").orEmpty(), stringList("enabledNames", mcpProfileJson)),
    )
}

suspend fun BridgeClient.setProjectLabel(settings: BridgeSettings, id: String, label: String, path: String? = null) {
    val body = JSONObject().put("id", id).put("label", label)
    if (!path.isNullOrBlank()) body.put("path", path)
    val json = postJson(settings, "/projects/label", body)
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Project label failed"))
}

suspend fun BridgeClient.applyProjectPreferences(
    settings: BridgeSettings,
    id: String,
    pinned: Boolean? = null,
    touch: Boolean? = null,
    quickStartProvider: String? = null,
    quickStartModel: String? = null,
    quickStartPermissionMode: String? = null,
    quickStartEffort: String? = null,
    path: String? = null,
): ProjectSummary {
    val body = JSONObject()
    if (id.isNotBlank()) body.put("id", id)
    if (!path.isNullOrBlank()) body.put("path", path)
    if (pinned != null) body.put("pinned", pinned)
    if (touch != null) body.put("touch", touch)
    if (quickStartProvider != null || quickStartModel != null || quickStartPermissionMode != null || quickStartEffort != null) {
        val qs = JSONObject()
        if (quickStartProvider != null) qs.put("provider", quickStartProvider)
        if (quickStartModel != null) qs.put("model", quickStartModel)
        if (quickStartPermissionMode != null) qs.put("permissionMode", quickStartPermissionMode)
        if (quickStartEffort != null) qs.put("effort", quickStartEffort)
        body.put("quickStart", qs)
    }
    val json = postJson(settings, "/projects/preferences", body)
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Project preferences failed"))
    val projectJson = json.optJSONObject("project") ?: throw IOException("Missing project in response")
    return parseProjectSummary(projectJson)
}

suspend fun BridgeClient.markProjectOutputsSeen(settings: BridgeSettings, id: String) {
    val json = postJson(settings, "/projects/outputs/seen", JSONObject().put("id", id))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Mark outputs seen failed"))
}

suspend fun BridgeClient.archiveProjectOutputs(settings: BridgeSettings, id: String): CoworkArchiveResult {
    val json = postJson(settings, "/projects/outputs/archive", JSONObject().put("id", id))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Archive failed"))
    return CoworkArchiveResult(true, json.optString("path"), json.optString("name"), json.optLong("size"))
}

// Bridge tarafındaki geçici teslimat zip'ini siler (bridge/tmp). Telefon zip'i
// klasör olarak açtıktan sonra çağrılır; best-effort — hata sessiz yutulur.
suspend fun BridgeClient.cleanupProjectOutputsArchive(settings: BridgeSettings, path: String) {
    if (path.isBlank()) return
    postJson(settings, "/projects/outputs/archive/cleanup", JSONObject().put("path", path))
}

suspend fun BridgeClient.applyProjectSecurity(settings: BridgeSettings, id: String, profile: String, policy: JSONObject? = null) {
    val body = JSONObject().put("id", id).put("profile", profile)
    if (policy != null) body.put("policy", policy)
    val json = postJson(settings, "/projects/security/apply", body)
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Project security failed"))
}

suspend fun BridgeClient.startDevicePairing(settings: BridgeSettings): PairingStartResult {
    val json = postJson(settings, "/pairing/start")
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Pairing start failed"))
    return PairingStartResult(json.optString("code"), json.optString("expiresAt"))
}

suspend fun BridgeClient.completeDevicePairing(settings: BridgeSettings, code: String, name: String): DeviceKeyResult {
    val json = postJson(settings, "/pairing/complete", JSONObject().put("code", code).put("name", name))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Pairing failed"))
    return DeviceKeyResult(json.optString("deviceId"), json.optString("key"))
}

suspend fun BridgeClient.rotateDeviceKey(settings: BridgeSettings): DeviceKeyResult {
    val json = postJson(settings, "/devices/rotate")
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Key rotation failed"))
    return DeviceKeyResult(json.optString("deviceId"), json.optString("key"))
}

/** Paylaşımdan üretilen notun sonucu. */
data class ShareNoteResult(
    val ok: Boolean,
    val noteId: String = "",
    val title: String = "",
    val kind: String = "",
    val error: String = "",
)

/**
 * Paylaşılan dosyayı köprüye gönderir, model onu nota çevirir.
 *
 * `executeJsonAi` — çünkü bu çağrı MODELİ BEKLİYOR (~15 sn, yavaş durumda daha
 * fazla). Yükleme istemcisinin 60 sn'lik okuma bütçesi sınırda kalırdı;
 * `aiClient` zaten "tek seferlik not AI işlemi" için ayrılmış 210 sn'lik
 * bütçeye sahip ve bu tam olarak o iş.
 */
suspend fun BridgeClient.createNoteFromShare(
    settings: BridgeSettings,
    filename: String,
    bytes: ByteArray,
): ShareNoteResult = runCatching {
    val json = executeJsonAi(
        buildRequest(settings, "/cowork/note/from-share?filename=" + URLEncoder.encode(filename, "UTF-8"))
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build(),
    )
    ShareNoteResult(
        ok = json.optBoolean("ok"),
        noteId = json.optString("noteId"),
        title = json.optString("baslik"),
        kind = json.optString("tur"),
        error = json.optString("error"),
    )
}.getOrElse { e -> ShareNoteResult(ok = false, error = e.message ?: "bilinmeyen hata") }

suspend fun BridgeClient.projectMcpServers(settings: BridgeSettings, id: String, provider: String): List<McpServer> {
    val json = getJson(settings, "/projects/mcp/servers?id=${URLEncoder.encode(id, "UTF-8")}&provider=${URLEncoder.encode(provider, "UTF-8")}")
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Project MCP list failed"))
    val arr = json.optJSONArray("servers") ?: return emptyList()
    return buildList { for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        add(McpServer(item.optString("name"), item.optBoolean("enabled"), item.optString("type"), item.optString("url"), item.optString("command"), emptyList(), item.optString("status"), item.optBoolean("managed")))
    } }
}

suspend fun BridgeClient.applyProjectMcpProfile(settings: BridgeSettings, id: String, provider: String, enabledNames: Set<String>) {
    val names = org.json.JSONArray().also { arr -> enabledNames.sorted().forEach(arr::put) }
    val json = postJson(settings, "/projects/mcp/apply", JSONObject().put("id", id).put("provider", provider).put("enabledNames", names).put("confirmGlobal", true))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Project MCP apply failed"))
}

// Projeyi izleme listesinden çıkar + tüm backend'lerden oturum geçmişini sil.
// Proje klasörüne dokunmaz. Silinen oturum sayısını döndürür.
suspend fun BridgeClient.deleteProject(settings: BridgeSettings, id: String): Int {
    val json = postJson(settings, "/projects/delete", JSONObject().put("id", id))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Proje silinemedi"))
    return json.optInt("deleted")
}

suspend fun BridgeClient.deleteProjectCompletely(settings: BridgeSettings, id: String): CompleteProjectDeleteResult {
    val json = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val request = buildRequest(settings, "/projects/delete-completely")
            .post(JSONObject().put("id", id).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).await()
        response.use {
            JSONObject(it.body?.string().orEmpty().ifBlank { "{}" })
        }
    }
    val ids = buildSet {
        val array = json.optJSONArray("deletedSessionIds")
        if (array != null) for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
    }
    val removedIds = buildList {
        val array = json.optJSONArray("removedProjectIds")
        if (array != null) for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
    }
    val sessionResults = buildList {
        val array = json.optJSONArray("sessionResults")
        if (array != null) for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(DeleteSessionResult(
                backend = item.optString("backend"),
                sessionId = item.optString("sessionId"),
                ok = item.optBoolean("ok"),
                error = item.optString("error"),
            ))
        }
    }
    return CompleteProjectDeleteResult(
        ok = json.optBoolean("ok"),
        path = json.optString("path"),
        projectId = json.optString("projectId", id),
        phase = json.optString("phase"),
        folderDeleted = json.optBoolean("folderDeleted"),
        registryDeleted = json.optBoolean("registryDeleted"),
        deletedSessionCount = json.optInt("deletedSessionCount", json.optInt("deleted")),
        deletedSessionIds = ids,
        removedProjectIds = removedIds,
        sessionResults = sessionResults,
        error = json.optString("error"),
        retryable = json.optBoolean("retryable"),
    )
}

suspend fun BridgeClient.bulkSessionAction(
    settings: BridgeSettings,
    projectId: String,
    action: String,
    sessions: List<ProjectSession>,
): BulkSessionActionResponse {
    val sessionsArray = org.json.JSONArray()
    for (s in sessions) {
        val sObj = JSONObject()
        sObj.put("backend", s.backend)
        sObj.put("sessionId", s.sessionId)
        sObj.put("nativeSessionId", s.nativeSessionId)
        sObj.put("container", s.container)
        sessionsArray.put(sObj)
    }
    val reqBody = JSONObject()
    reqBody.put("id", projectId)
    reqBody.put("action", action)
    reqBody.put("sessions", sessionsArray)

    // Read body even on 400 so detailed per-session errors reach BulkResultSheet.
    val json = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val request = buildRequest(settings, "/projects/sessions/bulk")
            .post(reqBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).await()
        response.use {
            JSONObject(it.body?.string().orEmpty().ifBlank { "{}" })
        }
    }

    val resultsList = mutableListOf<BulkSessionResult>()
    val resultsArray = json.optJSONArray("results")
    if (resultsArray != null) {
        for (i in 0 until resultsArray.length()) {
            val rObj = resultsArray.getJSONObject(i)
            resultsList.add(
                BulkSessionResult(
                    backend = rObj.optString("backend"),
                    sessionId = rObj.optString("sessionId"),
                    ok = rObj.optBoolean("ok"),
                    error = rObj.optString("error")
                )
            )
        }
    }
    return BulkSessionActionResponse(
        ok = json.optBoolean("ok") || json.optInt("succeeded") > 0,
        action = json.optString("action"),
        succeeded = json.optInt("succeeded"),
        failed = json.optInt("failed"),
        results = resultsList
    )
}

suspend fun BridgeClient.pollNotifications(
    settings: BridgeSettings,
    lastPending: Boolean,
    lastSessionId: String,
    lastEventId: String,
    deviceId: String = "",
): NotificationPollResult {
    val query = "?pending=$lastPending" +
        "&sessionId=${URLEncoder.encode(lastSessionId, "UTF-8")}" +
        "&eventId=${URLEncoder.encode(lastEventId, "UTF-8")}" +
        "&deviceId=${URLEncoder.encode(deviceId, "UTF-8")}"
    val json = getJsonLongPoll(settings, "/notifications/poll$query")
    return NotificationPollResult(
        pending = json.optBoolean("pending"),
        backend = json.optString("backend"),
        sessionId = json.optString("sessionId"),
        summary = json.optString("summary"),
        eventId = json.optString("eventId"),
        events = parseOperationEvents(json.optJSONArray("events")),
        pushEvents = parsePushEvents(json.optJSONArray("pushEvents")),
    )
}

suspend fun BridgeClient.approve(settings: BridgeSettings, backend: String, sessionId: String, allow: Boolean) {
    val body = JSONObject().put("sessionId", sessionId).put("allow", allow)
    val path = when (backend.trim().lowercase()) {
        "codex-app" -> "/codex-app/approve"
        // Bildirim kartindaki onay tusu: dal olmadan v2 oturumunun onayi
        // `else` dalindan CLAUDE'a gidiyordu.
        "opencode2-app" -> "/opencode2-app/approve"
        "omp" -> "/omp/approve"
        else -> "/claude-app/approve"
    }
    postJson(settings, path, body)
}

fun BridgeClient.openBackendStream(
    settings: BridgeSettings,
    backend: String,
    sessionId: String,
    since: Long?,
    listener: WebSocketListener,
): WebSocket {
    val session = URLEncoder.encode(sessionId, "UTF-8")
    val sincePart = since?.let { "&since=$it" } ?: ""
    val url = settings.baseUrl.trimEnd('/')
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://") + "/$backend/stream?session=$session&delta=1$sincePart"
    val builder = Request.Builder().url(url)
    if (settings.token.isNotBlank()) {
        builder.header("Authorization", "Bearer ${settings.token}")
    }
    val request = builder.build()
    return client.newWebSocket(request, listener)
}

suspend fun BridgeClient.slash(settings: BridgeSettings, backend: String = "antigravity"): SlashResult {
    val json = getJson(settings, "/slash?backend=${backend}")
    val array = json.optJSONArray("commands")
    val commands = buildList {
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(SlashCommand(item.optString("name"), item.optString("desc")))
            }
        }
    }
    return SlashResult(commands)
}

// force = kullanıcı "Yenile"ye bastı: köprü tarafındaki önbellekler (grup cache'i +
// hesap başına 5 dk'lık limit cache'i) atlanır. Otomatik yüklemeler force'suz gider.
suspend fun BridgeClient.usage(settings: BridgeSettings, force: Boolean = false): UsageResult {
    val json = getJson(settings, if (force) "/usage?force=1" else "/usage")
    val array = json.optJSONArray("groups")
    val groups = buildList {
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val bucketsArray = item.optJSONArray("buckets")
                val buckets = buildList {
                    if (bucketsArray != null) {
                        for (j in 0 until bucketsArray.length()) {
                            val bucket = bucketsArray.optJSONObject(j) ?: continue
                            add(
                                UsageBucket(
                                    id = bucket.optString("id"),
                                    label = bucket.optString("label"),
                                    window = bucket.optString("window"),
                                    description = bucket.optString("description"),
                                    remainingFraction = bucket.optDouble("remainingFraction", 0.0),
                                    resetTime = bucket.optString("resetTime"),
                                    value = bucket.optString("value"),
                                    metered = bucket.optBoolean("metered", true),
                                    creditUsd = if (bucket.isNull("creditUsd")) null else bucket.optDouble("creditUsd").takeIf { it.isFinite() },
                                ),
                            )
                        }
                    }
                }
                add(UsageGroup(item.optString("name"), item.optString("description"), buckets, item.optString("source"), item.optBoolean("stale")))
            }
        }
    }
    return UsageResult(groups, json.optString("note"))
}

suspend fun BridgeClient.readFile(settings: BridgeSettings, path: String): FileResult {
    val query = URLEncoder.encode(path, "UTF-8")
    val json = getJson(settings, "/file?path=$query")
    return FileResult(
        ok = json.optBoolean("ok"),
        name = json.optString("name", path),
        content = json.optString("content"),
        truncated = json.optBoolean("truncated"),
        error = json.optString("error"),
        path = json.optString("path", path),
        size = json.optLong("size"),
        mtime = json.optLong("mtime"),
        hash = json.optString("hash"),
    )
}

/**
 * PDF'in metin katmanını okuma modu için markdown olarak getirir.
 *
 * Çıkarım köprüde: dosya orada zaten var, telefona metin olarak inen bir kopya
 * olmuyor. Taranmış belgede `ok=false, reason="taranmis"` döner — bu bir hata
 * değil, "bu belgede metin katmanı yok" cevabıdır.
 */
suspend fun BridgeClient.readPdfMarkdown(settings: BridgeSettings, path: String): PdfReadResult {
    val query = URLEncoder.encode(path, "UTF-8")
    val json = getJsonDoc(settings, "/pdf-read?path=$query")
    if (!json.optBoolean("ok")) {
        return PdfReadResult(ok = false, reason = json.optString("reason").ifBlank { "çıkarılamadı" })
    }
    val starts = json.optJSONArray("pageStarts")
    return PdfReadResult(
        ok = true,
        markdown = json.optString("markdown"),
        pageStarts = buildList { if (starts != null) for (i in 0 until starts.length()) add(starts.optInt(i)) },
        pageCount = json.optInt("pageCount"),
        truncated = json.optBoolean("truncated"),
        tablesTruncated = json.optBoolean("tablesTruncated"),
    )
}

/**
 * Bir sayfanın metnini dil ve tutarlılık açısından kontrol ettirir.
 *
 * Kontrol edilen metin EKRANDA GÖRÜNENDİR: köprünün kendi bölmesini kullanmak
 * istemcideki sayfa bölmesiyle ayrışabilirdi, o yüzden metin gövdede gider.
 * Yanıt yalnız bulgu taşır; düzeltilmiş metin YOK.
 */
suspend fun BridgeClient.checkPdfLanguage(settings: BridgeSettings, text: String): LangCheckResult {
    val json = postJsonDoc(settings, "/pdf-lang-check", JSONObject().put("text", text))
    if (!json.optBoolean("ok")) {
        return LangCheckResult(ok = false, reason = json.optString("reason").ifBlank { "kontrol edilemedi" })
    }
    val arr = json.optJSONArray("bulgular")
    val findings = buildList {
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                LangFinding(
                    tur = o.optString("tur"),
                    alinti = o.optString("alinti"),
                    oneri = o.optString("oneri"),
                    not = o.optString("not"),
                ),
            )
        }
    }
    return LangCheckResult(ok = true, bulgular = findings)
}

private fun parsePdfPosition(json: JSONObject?): BridgePdfPosition? {
    val page = json?.optInt("page", 0) ?: 0
    if (page < 1) return null
    return BridgePdfPosition(
        page = page,
        ri = json!!.optInt("ri", 0),
        ro = json.optInt("ro", 0),
        mode = json.optString("mode").ifBlank { "p" },
        savedAt = json.optLong("savedAt", 0L),
    )
}

/**
 * Belgenin köprüde kayıtlı son konumu; kayıt yoksa null.
 *
 * Kısa istek: belge AÇILIRKEN, ilk sayfa çizilmeden önce beklenir — pager'ın
 * `initialPage`'i o an belli olmalı. Köprü yoksa çağıran zaten belgeyi de
 * indiremiyor demektir; hata sessizce yutulur ve yerel kopyayla devam edilir.
 */
suspend fun BridgeClient.pdfPosition(settings: BridgeSettings, path: String): BridgePdfPosition? {
    val json = getJson(settings, "/pdf-position?path=${URLEncoder.encode(path, "UTF-8")}")
    if (!json.optBoolean("ok")) return null
    return parsePdfPosition(json.optJSONObject("position"))
}

/**
 * Konumu köprüye yazar ve KAZANAN kaydı döndürür: gönderilen kayıt köprüdekinden
 * eskiyse köprüdeki geri gelir (öteki cihaz daha ileri gitmiş demektir).
 */
suspend fun BridgeClient.savePdfPosition(
    settings: BridgeSettings,
    path: String,
    position: BridgePdfPosition,
): BridgePdfPosition? {
    val body = JSONObject()
        .put("path", path)
        .put("page", position.page)
        .put("ri", position.ri)
        .put("ro", position.ro)
        .put("mode", position.mode)
        .put("savedAt", position.savedAt)
    val json = postJson(settings, "/pdf-position", body)
    if (!json.optBoolean("ok")) return null
    return parsePdfPosition(json.optJSONObject("position"))
}

// --- process / session admin ---
suspend fun BridgeClient.listProcesses(settings: BridgeSettings, backend: String): ProcessListResult {
    val json = getJson(settings, "/$backend/processes")
    val arr = json.optJSONArray("processes") ?: return ProcessListResult(emptyList(), 0)
    val procs = buildList {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            add(ProcInfo(
                pid = item.optInt("pid"),
                name = item.optString("name"),
                startTime = item.optString("startTime", ""),
                elapsedSec = item.optInt("elapsedSec"),
                cmdline = item.optString("cmdline"),
            ))
        }
    }
    return ProcessListResult(procs, json.optInt("count"))
}

suspend fun BridgeClient.warmOpenTabBackends(
    settings: BridgeSettings,
    targets: List<BackendWarmTarget>,
) {
    val array = org.json.JSONArray()
    targets.forEach { target ->
        array.put(
            JSONObject()
                .put("backend", target.backend)
                .put("sessionId", target.sessionId)
                .put("cwd", target.cwd),
        )
    }
    val result = postJson(settings, "/backends/warm", JSONObject().put("targets", array))
    if (!result.optBoolean("ok")) throw IOException(result.optString("error", "Backend warmup failed"))
}

// Mesaja geri dön: hedef kullanıcı mesajından (dahil) sona kadarki kullanıcı-mesajı
// sayısı dropUserTurns olarak gönderilir; backend kendi geri sarma mekanizmasını uygular.
suspend fun BridgeClient.rewindSession(settings: BridgeSettings, backend: String, sessionId: String, dropUserTurns: Int): RewindResult {
    val json = postJson(settings, "/$backend/rewind", JSONObject().put("sessionId", sessionId).put("dropUserTurns", dropUserTurns))
    return RewindResult(
        ok = json.optBoolean("ok"),
        sessionId = json.optString("sessionId"),
        partial = json.optBoolean("partial", false),
        error = json.optString("error"),
    )
}

// Buradan çatalla: rewind ile aynı sayım; orijinal oturum aynen kalır, cevaptaki
// YENİ sessionId ayrı sekmede açılır. Yalnız claude-app/codex-app destekler.
suspend fun BridgeClient.forkFromMessage(settings: BridgeSettings, backend: String, sessionId: String, dropUserTurns: Int): RewindResult {
    val json = postJson(settings, "/$backend/fork-from", JSONObject().put("sessionId", sessionId).put("dropUserTurns", dropUserTurns))
    return RewindResult(
        ok = json.optBoolean("ok"),
        sessionId = json.optString("sessionId"),
        partial = json.optBoolean("partial", false),
        error = json.optString("error"),
    )
}

suspend fun BridgeClient.killAllSessions(settings: BridgeSettings, backend: String): KillResult {
    val json = postJson(settings, "/$backend/kill-all")
    val killedArr = json.optJSONArray("killed")
    val errorsArr = json.optJSONArray("errors")
    return KillResult(
        ok = json.optBoolean("ok"),
        killed = buildList { if (killedArr != null) for (j in 0 until killedArr.length()) add(killedArr.optInt(j)) },
        errors = buildList { if (errorsArr != null) for (j in 0 until errorsArr.length()) add(errorsArr.opt(j) ?: "unknown") },
        cleared = json.optInt("cleared", 0),
    )
}

suspend fun BridgeClient.listBackendSessionsCount(settings: BridgeSettings, backend: String): Int {
    val json = getJson(settings, "/$backend/sessions")
    val arr = json.optJSONArray("sessions") ?: return 0
    return arr.length()
}

// Bellekteki canlı oturumların tam listesi (id/cwd/model/status).
suspend fun BridgeClient.listBackendSessions(settings: BridgeSettings, backend: String): List<LiveSession> {
    val json = getJson(settings, "/$backend/sessions")
    val arr = json.optJSONArray("sessions") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        LiveSession(
            o.optString("id"),
            o.optString("cwd"),
            o.optString("model"),
            o.optString("status"),
            o.optBoolean("awaitingApproval", false),
            o.optString("lastText", ""),
            o.optString("title", ""),
            o.optString("threadId", "")
        )
    }
}

// --- file / dir operations ---
// Directory picker for new-session sheets across all backends. Served by /dirs.
// When includeFiles is true, the response also lists files (with size/type) so the
// file browser can show downloadable files alongside folders.
suspend fun BridgeClient.workerDirs(
    settings: BridgeSettings,
    root: String,
    includeFiles: Boolean = false,
    coworkOnly: Boolean = false,
    liteOnly: Boolean = false,
    // Köprü varsayılanda nokta-dosyaları ve node_modules'ü eler; yalnız gezginin
    // "gizli dosyaları göster" anahtarı bunu açar.
    includeHidden: Boolean = false,
): WorkerDirs {
    val query = URLEncoder.encode(root, "UTF-8")
    val filesParam = if (includeFiles) "&files=true" else ""
    val scopeParam = when {
        liteOnly -> "&scope=lite"
        coworkOnly -> "&scope=cowork"
        else -> ""
    }
    val hiddenParam = if (includeHidden) "&hidden=true" else ""
    val json = getJson(settings, "/dirs?root=$query$filesParam$scopeParam$hiddenParam")
    val array = json.optJSONArray("dirs")
    val dirs = buildList {
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(DirEntry(
                    name = item.optString("name"),
                    path = item.optString("path"),
                    type = item.optString("type", "dir"),
                    size = item.optLong("size", 0),
                    mtime = item.optLong("mtime", 0),
                ))
            }
        }
    }
    return WorkerDirs(json.optBoolean("ok"), json.optString("base"), json.optString("parent"), dirs)
}

/**
 * PC'nin sürücü kökleri (C:\, D:\ …). Sürücü kökünün üstü olmadığı için gezgin
 * başka bir diske geçemiyordu; listeyi köprü veriyor çünkü hangi disklerin takılı
 * olduğunu yalnız o bilebilir.
 */
suspend fun BridgeClient.driveRoots(settings: BridgeSettings): List<DirEntry> {
    val json = getJson(settings, "/dirs/roots")
    val arr = json.optJSONArray("roots") ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val p = item.optString("path")
            if (p.isNotBlank()) add(DirEntry(name = item.optString("name", p), path = p, type = "dir", size = 0))
        }
    }
}

suspend fun BridgeClient.searchDirs(
    settings: BridgeSettings,
    root: String,
    query: String,
    maxDepth: Int = 6,
    limit: Int = 50,
    liteOnly: Boolean = false,
): List<DirEntry> {
    val q = URLEncoder.encode(query, "UTF-8")
    val r = URLEncoder.encode(root, "UTF-8")
    val scopeParam = if (liteOnly) "&scope=lite" else ""
    val json = getJson(settings, "/dirs/search?root=$r&q=$q&maxDepth=$maxDepth&limit=$limit$scopeParam")
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "dir search failed"))
    val arr = json.optJSONArray("results") ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            add(DirEntry(name = item.optString("name"), path = item.optString("path"), type = "dir", size = 0))
        }
    }
}

suspend fun BridgeClient.searchGlobal(settings: BridgeSettings, query: String, limit: Int = 100): GlobalSearchResult {
    return getJson(settings, "/search/global?q=${URLEncoder.encode(query, "UTF-8")}&limit=${limit.coerceIn(1, 100)}")
        .let { json ->
            if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Search failed"))
            // Uyarılar isabetlerden ÖNCE okunuyor: `hits` boş olsa bile (hiçbir
            // sağlayıcı yetişemediyse olur) uyarı kaybolmasın.
            val uyariDizisi = json.optJSONArray("warnings")
            val uyarilar = buildList {
                for (i in 0 until (uyariDizisi?.length() ?: 0)) {
                    uyariDizisi?.optString(i)?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            val arr = json.optJSONArray("hits") ?: return GlobalSearchResult(warnings = uyarilar)
            val isabetler = buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(GlobalSearchHit(
                        id = item.optString("id"),
                        type = item.optString("type"),
                        projectId = item.optString("projectId"),
                        projectPath = item.optString("projectPath"),
                        projectName = item.optString("projectName"),
                        backend = item.optString("backend"),
                        backendLabel = item.optString("backendLabel"),
                        sessionId = item.optString("sessionId"),
                        container = item.optString("container"),
                        title = item.optString("title"),
                        role = item.optString("role"),
                        snippet = item.optString("snippet"),
                        rowId = item.optString("rowId"),
                        matchOrdinal = item.optInt("matchOrdinal"),
                        mtime = item.optLong("mtime"),
                    ))
                }
            }
            GlobalSearchResult(hits = isabetler, warnings = uyarilar)
        }
}

suspend fun BridgeClient.attachFile(settings: BridgeSettings, bytes: ByteArray, filename: String): UploadedFile {
    // OkHttp non-ASCII header reddeder ("Unexpected char 0x15f in X-Filename");
    // Türkçe dosya adları /upload'daki gibi query'de taşınır.
    // executeJsonUpload: ek de MB'larca olabilir, varsayılan istemcinin 10 sn'lik
    // yazma bütçesi /upload'da olduğu gibi burada da yetmez.
    val json = executeJsonUpload(
        buildRequest(settings, "/context/file?filename=${URLEncoder.encode(filename, "UTF-8")}")
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build(),
    )
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Upload failed"))
    return UploadedFile(
        name = json.optString("name", filename),
        path = json.optString("path"),
    )
}

suspend fun BridgeClient.uploadFile(
    settings: BridgeSettings,
    dir: String,
    filename: String,
    bytes: ByteArray,
    coworkOnly: Boolean = false,
    // Hedef klasör yoksa oluşturulsun mu? Varsayılan HAYIR: gezginden yapılan
    // yüklemede yanlış yazılmış bir yol sessizce klasör yaratmasın, hata versin.
    // Yalnız hedefi kodda sabit olan çağrılar (ekran görüntüsü arşivi) açar.
    createDir: Boolean = false,
): UploadResult =
    withContext(Dispatchers.IO) {
        runCatching {
            val query = "?dir=${URLEncoder.encode(dir, "UTF-8")}" +
                "&filename=${URLEncoder.encode(filename, "UTF-8")}" +
                (if (coworkOnly) "&scope=cowork" else "") +
                (if (createDir) "&mkdir=true" else "")
            // executeJson DEĞİL: varsayılan istemcinin yazma bütçesi 10 sn
            // (OkHttp varsayılanı) ve büyük dosyada yetmiyor — bkz. uploadClient.
            val json = executeJsonUpload(
                buildRequest(settings, "/upload$query")
                    .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                    .build(),
            )
            UploadResult(
                ok = json.optBoolean("ok"),
                name = json.optString("name", filename),
                path = json.optString("path"),
                error = json.optString("error"),
            )
        }.getOrElse { e ->
            UploadResult(ok = false, name = filename, path = "", error = e.message ?: "unknown error")
        }
    }

// Cowork'te harici uygulamada düzenlenen dosyanın PC'deki orijinalin üzerine geri
// yazımı. Yol query'de taşınır (URL-encoded) — OkHttp non-ASCII header reddeder,
// Türkçe yollar header'da taşınamaz. Köprü hedefi Cowork root içinde MEVCUT dosya
// olarak doğrular; ad sanitizasyonu yoktur.
// executeJson kullanılmaz: köprü 404/400'de de JSON hata gövdesi döner ve çağıran
// ("dosya bulunamadı" → kaydı düşür vs. ağ hatası → tekrar dene) ayrımını bu
// gövdedeki error metninden yapar. Ağ hatası IOException olarak fırlar.
suspend fun BridgeClient.saveCoworkFile(settings: BridgeSettings, path: String, bytes: ByteArray): UploadResult =
    withContext(Dispatchers.IO) {
        val fallbackName = path.substringAfterLast("/").substringAfterLast("\\")
        val request = buildRequest(settings, "/cowork/savefile?path=" + URLEncoder.encode(path, "UTF-8"))
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(request).await().use { resp ->
            val json = runCatching { JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" }) }
                .getOrDefault(JSONObject())
            UploadResult(
                ok = resp.isSuccessful && json.optBoolean("ok"),
                name = json.optString("name", fallbackName),
                path = json.optString("path", path),
                error = json.optString("error").ifBlank { if (resp.isSuccessful) "" else "HTTP ${resp.code}" },
            )
        }
    }

// Genel PC dosya yöneticisi ve sohbet bağlantılarından açılan mevcut dosyayı
// atomik olarak geri yazar. Sunucu yalnız var olan normal dosyaları kabul eder.
suspend fun BridgeClient.saveFile(settings: BridgeSettings, path: String, bytes: ByteArray): UploadResult =
    saveFileChecked(settings, path, bytes).let {
        UploadResult(it.ok, it.name, it.path, it.error)
    }

suspend fun BridgeClient.saveFileChecked(
    settings: BridgeSettings,
    path: String,
    bytes: ByteArray,
    expectedHash: String = "",
): FileSaveResult = withContext(Dispatchers.IO) {
        val fallbackName = path.substringAfterLast("/").substringAfterLast("\\")
        val query = "/savefile?path=" + URLEncoder.encode(path, "UTF-8") +
            expectedHash.takeIf { it.isNotBlank() }
                ?.let { "&expectedHash=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val request = buildRequest(settings, query)
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(request).await().use { resp ->
            val json = runCatching { JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" }) }
                .getOrDefault(JSONObject())
            FileSaveResult(
                ok = resp.isSuccessful && json.optBoolean("ok"),
                name = json.optString("name", fallbackName),
                path = json.optString("path", path),
                error = json.optString("error").ifBlank { if (resp.isSuccessful) "" else "HTTP ${resp.code}" },
                conflict = resp.code == 409 || json.optBoolean("conflict"),
                size = json.optLong("size"),
                mtime = json.optLong("mtime"),
                hash = json.optString("hash"),
            )
        }
    }

suspend fun BridgeClient.deleteEntry(settings: BridgeSettings, path: String, coworkOnly: Boolean = false): Boolean {
    val body = JSONObject().put("path", path)
    if (coworkOnly) body.put("scope", "cowork")
    val json = postJson(settings, "/delete", body)
    return json.optBoolean("ok")
}

suspend fun BridgeClient.renameEntry(settings: BridgeSettings, path: String, newName: String, coworkOnly: Boolean = false): UploadResult {
    val body = JSONObject().put("path", path).put("newName", newName)
    if (coworkOnly) body.put("scope", "cowork")
    val json = postJson(settings, "/rename", body)
    return UploadResult(
        ok = json.optBoolean("ok"),
        name = path.substringAfterLast("/").substringAfterLast("\\"),
        path = json.optString("path"),
        error = json.optString("error"),
    )
}

suspend fun BridgeClient.moveEntry(settings: BridgeSettings, path: String, destDir: String, coworkOnly: Boolean = false): UploadResult {
    val body = JSONObject().put("path", path).put("destDir", destDir)
    if (coworkOnly) body.put("scope", "cowork")
    val json = postJson(settings, "/move", body)
    return UploadResult(
        ok = json.optBoolean("ok"),
        name = path.substringAfterLast("/").substringAfterLast("\\"),
        path = json.optString("path"),
        error = json.optString("error"),
    )
}

/** Çoklu seçim "Yapıştır" için; /move ile aynı, kaynağı silmez. */
suspend fun BridgeClient.copyEntry(settings: BridgeSettings, path: String, destDir: String, coworkOnly: Boolean = false): UploadResult {
    val body = JSONObject().put("path", path).put("destDir", destDir)
    if (coworkOnly) body.put("scope", "cowork")
    val json = postJson(settings, "/copy", body)
    return UploadResult(
        ok = json.optBoolean("ok"),
        name = path.substringAfterLast("/").substringAfterLast("\\"),
        path = json.optString("path"),
        error = json.optString("error"),
    )
}

/**
 * Stream a workspace file as raw bytes from /download. Returns the open [Response] — the
 * caller MUST close it (use a `use {}` block) after copying the body to its destination.
 * [onProgress] receives a 0..1 fraction as bytes arrive (best-effort, based on
 * Content-Length); it is only invoked once, before the stream begins, since the actual
 * byte copying happens in the caller. For live progress the caller should track bytes
 * written itself.
 */
suspend fun BridgeClient.downloadFile(
    settings: BridgeSettings,
    path: String,
    onProgress: (Float) -> Unit,
): Response = withContext(Dispatchers.IO) {
    val query = URLEncoder.encode(path, "UTF-8")
    val request = buildRequest(settings, "/download?path=$query").get().build()
    onProgress(0f)
    val response = client.newCall(request).await()
    if (!response.isSuccessful) {
        response.close()
        throw IOException("HTTP ${response.code}")
    }
    response
}
