package com.agent.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

// Backend oturum yaşam döngüsü API'si — BridgeClient'tan ayrılan extension fonksiyonları
// (madde 12.3). bridge/routes/backend.mjs (pfx + '/*' parametrik) ile eşleşir: agy,
// claude-app, cowork, codex, codex-app, omp. Çağıran kod değişmez;
// fonksiyonlar aynı imzayı korur, artık BridgeClient receiver'lı extension.
// parseCoworkSessions/parseCoworkStartResult/parseCoworkOutputs/diskSessionsFrom bu
// dosyada top-level private (yalnızca backend/cowork kullanır). Davranış değişikliği yok.

suspend fun BridgeClient.agyThought(settings: BridgeSettings, sessionId: String, index: Int): String {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val json = getJson(settings, "/agy/thought?sessionId=$session&i=$index")
        return json.optString("text")
    }

    // --- Claude App (kalıcı çift-yönlü) session API ---
suspend fun BridgeClient.claudeAppModels(settings: BridgeSettings): ModelsResult<ClaudeModel> {
        val json = getJson(settings, "/claude-app/models")
        val array = json.optJSONArray("models")
        val models = buildList {
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(ClaudeModel(item.optString("label"), item.optString("id")))
                }
            }
        }
        return ModelsResult(models, json.optString("defaultModel"))
    }

suspend fun BridgeClient.claudeAppNew(settings: BridgeSettings, cwd: String, model: String, permissionMode: String = "", cowork: Boolean = false): String {
        val body = JSONObject().put("cwd", cwd).put("model", model)
        if (permissionMode.isNotBlank()) body.put("permissionMode", permissionMode)
        if (cowork) body.put("cowork", true)
        val json = postJson(settings, "/claude-app/new", body)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Claude App session failed"))
        return json.optString("sessionId")
    }

    // Cowork workspace yönetimi — COWORK, ağ katmanında claude-app uçlarını kullanır.
suspend fun BridgeClient.claudeAppWorkspaces(settings: BridgeSettings): Pair<String, List<com.agent.bridge.CoworkWorkspace>> {
        val json = getJson(settings, "/claude-app/workspaces")
        val root = json.optString("root")
        val arr = json.optJSONArray("workspaces") ?: return root to emptyList()
        val list = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(com.agent.bridge.CoworkWorkspace(item.optString("name"), item.optString("path")))
            }
        }
        return root to list
    }

suspend fun BridgeClient.claudeAppCreateWorkspace(settings: BridgeSettings, name: String): String {
        val json = postJson(settings, "/claude-app/workspace", JSONObject().put("name", name))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Çalışma alanı oluşturulamadı"))
        return json.optString("path")
    }

suspend fun BridgeClient.coworkProjects(settings: BridgeSettings): CoworkProjectsPage {
        val json = getJson(settings, "/cowork/projects")
        val root = json.optString("root")
        val arr = json.optJSONArray("projects") ?: return CoworkProjectsPage(root, emptyList())
        val projects = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(CoworkProject(item.optString("name"), item.optString("path"), item.optString("activeProvider"), item.optString("updatedAt"), item.optString("matter")))
            }
        }
        return CoworkProjectsPage(root, projects)
    }

internal fun parseCoworkNote(item: JSONObject): CoworkNote {
    val projectJson = item.optJSONObject("project")
    fun nullablePath(key: String): String? =
        item.opt(key)
            ?.takeUnless { it == JSONObject.NULL }
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    return CoworkNote(
        id = item.optString("id"),
        title = item.optString("title"),
        project = projectJson?.let {
            CoworkNoteProject(
                name = it.optString("name"),
                path = it.optString("path"),
            )
        },
        mdPath = nullablePath("mdPath"),
        preview = item.optString("preview"),
        sourceScreenshot = nullablePath("sourceScreenshot").orEmpty(),
        reminderAt = nullablePath("reminderAt"),
        reminderState = nullablePath("reminderState"),
        reminderAttempts = item.optInt("reminderAttempts", 0),
        updatedAtMs = item.optLong("updated_at_ms"),
    )
}

// query boşsa tam liste, doluysa köprüde başlık + gövde araması. Arama
// istemcide yapılamaz: kart özeti gövdenin yalnız ilk satırını taşıyor,
// "içerikte ara" için dosyanın tamamını okuyan taraf gerekiyor.
suspend fun BridgeClient.coworkNotes(
    settings: BridgeSettings,
    query: String = "",
): CoworkNotesPage {
    val path = if (query.isBlank()) {
        "/cowork/notes"
    } else {
        "/cowork/notes?q=" + URLEncoder.encode(query, "UTF-8")
    }
    val json = getJson(settings, path)
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Notlar yüklenemedi"))
    val arr = json.optJSONArray("notes")
    val notes = buildList {
        if (arr != null) for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { add(parseCoworkNote(it)) }
        }
    }
    return CoworkNotesPage(root = json.optString("root"), notes = notes)
}

suspend fun BridgeClient.coworkCreateNote(
    settings: BridgeSettings,
    name: String,
    projectPath: String? = null,
): CoworkNote {
    val body = JSONObject().put("name", name)
    if (!projectPath.isNullOrBlank()) body.put("projectPath", projectPath)
    val json = postJson(settings, "/cowork/note", body)
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Not oluşturulamadı"))
    val note = json.optJSONObject("note") ?: throw IOException("Not cevabı eksik")
    return parseCoworkNote(note)
}

suspend fun BridgeClient.coworkAttachNote(
    settings: BridgeSettings,
    noteId: String,
    projectPath: String,
): CoworkNote {
    val json = postJson(
        settings,
        "/cowork/note/attach",
        JSONObject().put("noteId", noteId).put("projectPath", projectPath),
    )
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Not projeye bağlanamadı"))
    val note = json.optJSONObject("note") ?: throw IOException("Not cevabı eksik")
    return parseCoworkNote(note)
}

// Var olan nota hatırlatıcı kurar/kaldırır. atIso null ise kaldırır.
// Cevapta not nesnesi YOK (bridge yalnız yolu döndürüyor); çağıran listeyi
// yeniden yükler.
suspend fun BridgeClient.coworkSetNoteReminder(
    settings: BridgeSettings,
    noteId: String,
    atIso: String?,
) {
    val body = JSONObject().put("noteId", noteId)
    if (atIso.isNullOrBlank()) body.put("at", JSONObject.NULL) else body.put("at", atIso)
    val json = postJson(settings, "/cowork/note/reminder", body)
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Hatırlatıcı ayarlanamadı"))
}

suspend fun BridgeClient.coworkRenameNote(
    settings: BridgeSettings,
    noteId: String,
    name: String,
): CoworkNote {
    val json = postJson(
        settings,
        "/cowork/note/rename",
        JSONObject().put("noteId", noteId).put("name", name),
    )
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Not yeniden adlandırılamadı"))
    val note = json.optJSONObject("note") ?: throw IOException("Not cevabı eksik")
    return parseCoworkNote(note)
}

suspend fun BridgeClient.coworkDeleteNote(
    settings: BridgeSettings,
    noteId: String,
) {
    val json = postJson(
        settings,
        "/cowork/note/delete",
        JSONObject().put("noteId", noteId),
    )
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Not silinemedi"))
}

suspend fun BridgeClient.coworkNoteAi(
    settings: BridgeSettings,
    noteId: String,
    action: String,
    instruction: String = "",
): CoworkNoteAiPreview {
    val body = JSONObject()
        .put("noteId", noteId)
        .put("action", action)
    if (instruction.isNotBlank()) body.put("instruction", instruction)
    val json = postJsonAi(settings, "/cowork/note/ai", body)
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "AI önerisi oluşturulamadı"))
    return CoworkNoteAiPreview(
        noteId = json.optString("noteId", noteId),
        action = json.optString("action", action),
        title = json.optString("title"),
        mdPath = json.optString("mdPath"),
        original = json.optString("original"),
        proposed = json.optString("proposed"),
        baseHash = json.optString("base_hash"),
        provider = json.optString("provider"),
    )
}

suspend fun BridgeClient.coworkApplyNoteAi(
    settings: BridgeSettings,
    preview: CoworkNoteAiPreview,
) {
    val json = postJson(
        settings,
        "/cowork/note/ai/apply",
        JSONObject()
            .put("noteId", preview.noteId)
            .put("action", preview.action)
            .put("base_hash", preview.baseHash)
            .put("proposed", preview.proposed),
    )
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "AI önerisi kaydedilemedi"))
}

suspend fun BridgeClient.coworkProviderCatalog(settings: BridgeSettings): List<CoworkProviderCatalog> {
        val json = getJson(settings, "/cowork/providers")
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Cowork provider catalog failed"))
        val arr = json.optJSONArray("providers") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val modelsJson = item.optJSONArray("models")
                val models = buildList {
                    if (modelsJson != null) for (j in 0 until modelsJson.length()) {
                        val model = modelsJson.optJSONObject(j) ?: continue
                        add(BackendModel(model.optString("label"), model.optString("id")))
                    }
                }
                add(CoworkProviderCatalog(item.optString("id"), item.optString("label"), item.optString("defaultModel"), models))
            }
        }
    }

suspend fun BridgeClient.coworkCreateProject(settings: BridgeSettings, name: String, template: String = ""): CoworkProject {
        val body = JSONObject().put("name", name)
        if (template.isNotBlank()) body.put("template", template)
        val json = postJson(settings, "/cowork/project", body)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Project olusturulamadi"))
        val item = json.optJSONObject("project") ?: json
        return CoworkProject(
            item.optString("name", json.optString("name")),
            item.optString("path", json.optString("path")),
            item.optString("activeProvider", "claude-app"),
            item.optString("updatedAt"),
            item.optString("matter")
        )
    }

suspend fun BridgeClient.coworkArchive(settings: BridgeSettings, projectPath: String): CoworkArchiveResult {
        val json = postJson(settings, "/cowork/archive", JSONObject().put("projectPath", projectPath))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Archive failed"))
        return CoworkArchiveResult(true, json.optString("path"), json.optString("name"), json.optLong("size"))
    }

// Projeyi hem app listesinden hem diskten tamamen sil (workspace klasörü uçar).
suspend fun BridgeClient.coworkDeleteProject(settings: BridgeSettings, projectPath: String): CompleteProjectDeleteResult {
    val json = postJson(settings, "/cowork/project/delete", JSONObject().put("projectPath", projectPath))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Proje silinemedi"))
    val ids = buildSet {
        val array = json.optJSONArray("deletedSessionIds")
        if (array != null) for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
    }
    return CompleteProjectDeleteResult(
        path = json.optString("path", projectPath),
        deletedSessionCount = json.optInt("deleted"),
        deletedSessionIds = ids,
    )
}

// Cowork oturum kaydını sil: .cowork/providers kaydı + sağlayıcıdaki karşılığı
// (canlı child + transcript). id bridge-uuid ya da native threadId olabilir.
suspend fun BridgeClient.coworkDeleteSession(settings: BridgeSettings, projectPath: String, id: String) {
        val json = postJson(settings, "/cowork/delete-session",
            JSONObject().put("projectPath", projectPath).put("id", id))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Oturum silinemedi"))
    }

suspend fun BridgeClient.coworkSetMatter(settings: BridgeSettings, projectPath: String, matter: String) {
        val json = postJson(settings, "/cowork/project/matter",
            JSONObject().put("projectPath", projectPath).put("matter", matter))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Matter update failed"))
    }

suspend fun BridgeClient.coworkProject(settings: BridgeSettings, projectPath: String): CoworkProjectDetails {
        val path = URLEncoder.encode(projectPath, "UTF-8")
        val json = getJson(settings, "/cowork/project?path=$path")
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Cowork project failed"))
        val p = json.optJSONObject("project")
        return CoworkProjectDetails(
            project = p?.let { CoworkProject(it.optString("name"), it.optString("path"), it.optString("activeProvider"), it.optString("updatedAt"), it.optString("matter")) },
            sessions = parseCoworkSessions(json.optJSONArray("sessions")),
            outputs = parseCoworkOutputs(json.optJSONArray("outputs")),
        )
    }

    // .cowork/providers/<provider>/sessions/*.json kayıtlarını çeker (drawer veri kaynağı).
suspend fun BridgeClient.coworkSessions(settings: BridgeSettings, projectPath: String): List<CoworkSessionRecord> {
        val path = URLEncoder.encode(projectPath, "UTF-8")
        val json = getJson(settings, "/cowork/sessions?projectPath=$path")
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Cowork sessions failed"))
        return parseCoworkSessions(json.optJSONArray("sessions"))
    }

    // PC'den workspace'e içe aktarma: mutlak yol listesi (dosya/klasör karışık olabilir).
suspend fun BridgeClient.coworkImport(settings: BridgeSettings, projectPath: String, sources: List<String>): CoworkImportResult {
        val body = JSONObject().put("projectPath", projectPath).put("sources", org.json.JSONArray(sources))
        val json = postJson(settings, "/cowork/import", body)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Import failed"))
        val errArr = json.optJSONArray("errors")
        return CoworkImportResult(
            ok = true,
            copied = json.optJSONArray("copied")?.length() ?: 0,
            errors = buildList {
                if (errArr != null) for (i in 0 until errArr.length()) {
                    val e = errArr.optJSONObject(i) ?: continue
                    add("${e.optString("source").substringAfterLast('\\').substringAfterLast('/')}: ${e.optString("error")}")
                }
            },
        )
    }

suspend fun BridgeClient.coworkStartSession(settings: BridgeSettings, projectPath: String, provider: String, model: String?, sessionId: String? = null, permissionMode: String? = null, forceNew: Boolean = false): CoworkStartResult {
        val body = JSONObject().put("projectPath", projectPath).put("provider", provider)
        if (!model.isNullOrBlank()) body.put("model", model)
        if (!sessionId.isNullOrBlank()) body.put("sessionId", sessionId)
        if (!permissionMode.isNullOrBlank()) body.put("permissionMode", permissionMode)
        if (forceNew) body.put("forceNew", true)
        val json = postJson(settings, "/cowork/session/start", body)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Cowork session failed"))
        return parseCoworkStartResult(json)
    }

suspend fun BridgeClient.coworkSwitchSession(settings: BridgeSettings, projectPath: String, provider: String, model: String?, sessionId: String? = null, permissionMode: String? = null): CoworkStartResult {
        val body = JSONObject().put("projectPath", projectPath).put("provider", provider)
        if (!model.isNullOrBlank()) body.put("model", model)
        if (!sessionId.isNullOrBlank()) body.put("sessionId", sessionId)
        if (!permissionMode.isNullOrBlank()) body.put("permissionMode", permissionMode)
        val json = postJson(settings, "/cowork/session/switch", body)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Cowork provider switch failed"))
        return parseCoworkStartResult(json)
    }

// postJson KULLANILMAZ: kilit reddi 409 ile gelir, executeJson ise non-2xx'te
// gövdeyi atıp "HTTP 409" fırlatıyor. Kilidi KIMIN tuttuğu (lease.provider) o
// gövdede; atılınca kullanıcıya anlamsız bir kod kalıyordu.
suspend fun BridgeClient.coworkAcquireLease(settings: BridgeSettings, projectPath: String, provider: String, sessionId: String) {
        val request = buildRequest(settings, "/cowork/lease/acquire")
            .post(
                JSONObject().put("projectPath", projectPath).put("provider", provider).put("sessionId", sessionId)
                    .toString().toRequestBody(jsonType)
            )
            .build()
        val json = client.newCall(request).await().use { resp ->
            runCatching { JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" }) }
                .getOrDefault(JSONObject())
                .also { if (!it.has("error") && !resp.isSuccessful) it.put("error", "HTTP ${resp.code}") }
        }
        if (!json.optBoolean("ok")) {
            val owner = json.optJSONObject("lease")?.optString("provider").orEmpty()
            throw IOException(coworkLeaseBusyMessage(owner, json.optString("error")))
        }
    }

suspend fun BridgeClient.coworkReleaseLease(settings: BridgeSettings, projectPath: String, provider: String, sessionId: String) {
        val json = postJson(settings, "/cowork/lease/release",
            JSONObject().put("projectPath", projectPath).put("provider", provider).put("sessionId", sessionId))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Çalışma alanı kilidi bırakılamadı"))
    }

suspend fun BridgeClient.coworkCreateHandoff(
    settings: BridgeSettings,
    projectPath: String,
    fromProvider: String,
    sessionId: String,
    toProvider: String,
): CoworkHandoffResult {
        val json = postJson(settings, "/cowork/handoff", JSONObject()
            .put("projectPath", projectPath)
            .put("fromProvider", fromProvider)
            .put("sessionId", sessionId)
            .put("toProvider", toProvider))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Devir notu oluşturulamadı"))
        return CoworkHandoffResult(
            path = json.optString("path"),
            fromProvider = json.optString("fromProvider"),
            toProvider = json.optString("toProvider"),
            messageCount = json.optInt("messageCount"),
        )
    }

private fun parseCoworkSessions(arr: org.json.JSONArray?): List<CoworkSessionRecord> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(CoworkSessionRecord(
                    provider = item.optString("provider"),
                    sessionId = item.optString("sessionId"),
                    threadId = item.optString("threadId"),
                    cwd = item.optString("cwd"),
                    model = item.optString("model"),
                    permissionMode = item.optString("permissionMode"),
                    permissionModeExplicit = item.optBoolean("permissionModeExplicit", false),
                    title = item.optString("title"),
                    lastText = item.optString("lastText"),
                    createdAt = item.optString("createdAt"),
                    lastUsedAt = item.optString("lastUsedAt"),
                ))
            }
        }
    }

private fun parseCoworkStartResult(json: JSONObject): CoworkStartResult {
        val p = json.optJSONObject("project")
        return CoworkStartResult(
            provider = json.optString("provider"),
            apiBackend = json.optString("apiBackend"),
            sessionId = json.optString("sessionId"),
            model = json.optString("model"),
            project = p?.let { CoworkProject(it.optString("name"), it.optString("path"), it.optString("activeProvider"), it.optString("updatedAt")) },
            outputs = parseCoworkOutputs(json.optJSONArray("outputs")),
        )
    }

private fun parseCoworkOutputs(arr: org.json.JSONArray?): List<CoworkOutput> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(CoworkOutput(item.optString("name"), item.optString("path"), item.optLong("size"), item.optDouble("mtime")))
            }
        }
    }

suspend fun BridgeClient.claudeAppPrompt(settings: BridgeSettings, sessionId: String, text: String, model: String?, permissionMode: String = ""): JSONObject {
        val body = JSONObject()
            .put("sessionId", sessionId)
            .put("text", text)
            .put("requestId", java.util.UUID.randomUUID().toString())
        if (!model.isNullOrBlank()) body.put("model", model)
        if (permissionMode.isNotBlank()) body.put("permissionMode", permissionMode)
        val json = postJson(settings, "/claude-app/prompt", body)
        if (!json.optBoolean("ok", true)) throw IOException(json.optString("error", "Claude App prompt failed"))
        return json
    }

suspend fun BridgeClient.claudeAppConversation(settings: BridgeSettings, sessionId: String, before: String = "", limit: Int = 0): ConversationResult {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val beforePart = before.takeIf { it.isNotBlank() }?.let { "&before=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        val limitPart = if (limit > 0) "&limit=$limit" else ""
        val json = getJson(settings, "/claude-app/conversation?sessionId=$session$beforePart$limitPart")
        return ConversationResult(
            text = json.optString("text"),
            messages = parseMessages(json),
            running = json.optBoolean("running"),
            awaitingApproval = json.optBoolean("awaitingApproval"),
            approval = parseApproval(json.optJSONObject("approval")),
            awaitingFirstOutput = json.optBoolean("awaitingFirstOutput"),
            choices = parseStringArray(json.optJSONArray("choices")),
            contextTokens = json.optInt("contextTokens"),
            contextWindow = json.optInt("contextWindow"),
            permissionMode = json.optString("permissionMode"),
            effort = json.optString("effort"),
            cost = json.optDouble("cost", 0.0),
            planDraft = json.optString("planDraft", ""),
            interruptStuck = json.optBoolean("interruptStuck", false),
        )
    }

suspend fun BridgeClient.claudeAppThought(settings: BridgeSettings, sessionId: String, index: Int): String {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val json = getJson(settings, "/claude-app/thought?sessionId=$session&i=$index")
        return json.optString("text")
    }

suspend fun BridgeClient.claudeAppStop(settings: BridgeSettings, sessionId: String) {
        postJson(settings, "/claude-app/stop", JSONObject().put("sessionId", sessionId))
    }

suspend fun BridgeClient.claudeAppApprove(settings: BridgeSettings, sessionId: String, allow: Boolean, answers: List<ApprovalAnswer> = emptyList(), requestId: String = "") {
        val body = JSONObject().put("sessionId", sessionId).put("allow", allow).onayKimligiEkle(requestId)
        if (answers.isNotEmpty()) {
            val arr = org.json.JSONArray()
            answers.forEach { answer ->
                arr.put(JSONObject().put("id", answer.questionId).put("optionId", answer.optionId).put("label", answer.label))
            }
            body.put("answers", arr)
        }
        postJson(settings, "/claude-app/approve", body)
    }

suspend fun BridgeClient.claudeAppInterrupt(settings: BridgeSettings, sessionId: String) {
        postJson(settings, "/claude-app/interrupt", JSONObject().put("sessionId", sessionId))
    }

suspend fun BridgeClient.claudeAppInfo(settings: BridgeSettings, sessionId: String): ClaudeAppInfo {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val json = getJson(settings, "/claude-app/info?sessionId=$session")
        return ClaudeAppInfo(
            model = json.optString("model"),
            permissionMode = json.optString("permissionMode"),
            version = json.optString("version"),
            tools = parseNamedArray(json.optJSONArray("tools")),
            mcpServers = parseNamedArray(json.optJSONArray("mcpServers"), includeStatus = true),
            slashCommands = parseNamedArray(json.optJSONArray("slashCommands")),
            agents = parseNamedArray(json.optJSONArray("agents")),
            skills = parseNamedArray(json.optJSONArray("skills")),
            skillDetails = parseSkillInfoArray(json.optJSONArray("skillDetails")),
            plugins = parseNamedArray(json.optJSONArray("plugins")),
            memoryPaths = parseStringArray(json.optJSONArray("memoryPaths")),
            models = buildList {
                val arr = json.optJSONArray("models")
                if (arr != null) for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(ClaudeModel(item.optString("label"), item.optString("id")))
                }
            },
            permissionModes = parseStringArray(json.optJSONArray("permissionModes")),
        )
    }

suspend fun BridgeClient.claudeAppSetPermissionMode(settings: BridgeSettings, sessionId: String, mode: String): String {
        val json = postJson(settings, "/claude-app/permission-mode", JSONObject().put("sessionId", sessionId).put("mode", mode))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Permission mode failed"))
        return json.optString("permissionMode")
    }

suspend fun BridgeClient.claudeAppSetModel(settings: BridgeSettings, sessionId: String, model: String): String {
        val json = postJson(settings, "/claude-app/model", JSONObject().put("sessionId", sessionId).put("model", model))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Model değiştirilemedi"))
        return json.optString("model")
    }

    // Reasoning effort (çaba seviyesi) — low/medium/high/xhigh/max. CLI --effort flag'ı;
    // aktif turda değil bir sonraki turda (respawn) geçerli olur.
suspend fun BridgeClient.claudeAppEfforts(settings: BridgeSettings): List<String> {
        val json = getJson(settings, "/claude-app/efforts")
        return parseStringArray(json.optJSONArray("efforts"))
    }

suspend fun BridgeClient.claudeAppSetEffort(settings: BridgeSettings, sessionId: String, effort: String): String {
        val json = postJson(settings, "/claude-app/effort", JSONObject().put("sessionId", sessionId).put("effort", effort))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Effort değiştirilemedi"))
        return json.optString("effort", effort)
    }

    // --- Claude App disk sessions ---
    // Masaüstü uygulamasının oturum listesiyle birebir aynı havuz.
suspend fun BridgeClient.claudeAppDiskSessions(settings: BridgeSettings, query: String = "", archived: Boolean = false): List<ClaudeDiskSession> {
        val q = URLEncoder.encode(query, "UTF-8")
        val path = "/claude-app/disk-sessions-search?archived=$archived&q=$q"
        return diskSessionsFrom(settings, path)
    }

    // Yalnızca COWORK_ROOT altındaki oturumlar (cowork oturum listesi).
suspend fun BridgeClient.coworkDiskSessions(settings: BridgeSettings): List<ClaudeDiskSession> =
        diskSessionsFrom(settings, "/claude-app/cowork-disk-sessions")

private suspend fun BridgeClient.diskSessionsFrom(settings: BridgeSettings, path: String): List<ClaudeDiskSession> {
        val json = getJson(settings, path)
        val arr = json.optJSONArray("sessions")
        return buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(ClaudeDiskSession(
                        id = item.optString("id"),
                        cwd = item.optString("cwd"),
                        title = item.optString("title"),
                        lastText = item.optString("lastText"),
                        turns = item.optInt("turns"),
                        mtime = item.optLong("mtime"),
                        pinned = item.optBoolean("pinned", false),
                        archived = item.optBoolean("archived", false),
                        forks = item.optInt("forks", 1)
                    ))
                }
            }
        }
    }

suspend fun BridgeClient.claudeAppPin(settings: BridgeSettings, id: String) {
        postJson(settings, "/claude-app/pin", JSONObject().put("id", id))
    }

suspend fun BridgeClient.claudeAppUnpin(settings: BridgeSettings, id: String) {
        postJson(settings, "/claude-app/unpin", JSONObject().put("id", id))
    }

suspend fun BridgeClient.claudeAppRename(settings: BridgeSettings, id: String, title: String) {
        postJson(settings, "/claude-app/rename", JSONObject().put("id", id).put("title", title))
    }

suspend fun BridgeClient.claudeAppArchive(settings: BridgeSettings, id: String) {
        postJson(settings, "/claude-app/archive", JSONObject().put("id", id))
    }

suspend fun BridgeClient.claudeAppUnarchive(settings: BridgeSettings, id: String) {
        postJson(settings, "/claude-app/unarchive", JSONObject().put("id", id))
    }

    // Oturumu PC'de görünür bir terminalde `claude --resume` ile açtırır. Masaüstü
    // Claude uygulaması köprü oturumlarını listelemediği için PC'de sürdürmenin
    // desteklenen yolu budur.
suspend fun BridgeClient.claudeAppOpenOnPc(settings: BridgeSettings, id: String) {
        val json = postJson(settings, "/claude-app/open-on-pc", JSONObject().put("id", id))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "PC'de açılamadı"))
    }

    // Oturumu ilgili backend'in app listesinden ve diskten tamamen sil. backend =
    // "claude-app" | "codex-app" | "omp" | "agy". Desteklemeyen backend'de
    // bridge 400 döner → IOException.
/**
 * Oturumu diskten siler. Dönen değer kapatılacak sekmelerin id'leridir.
 *
 * Silme isteği DİSK id'siyle gidiyor (çekmece onu listeliyor) ama açık sekme
 * köprünün kendi oturum id'sine bağlı olabiliyor (ör. omp'de bir UUID).
 * İkisi birden dönmezse sekme açık kalıyor ve sohbeti köprü belleğinden
 * yüklemeye devam ediyor.
 */
suspend fun BridgeClient.deleteDiskSession(settings: BridgeSettings, backend: String, id: String): Set<String> {
        val json = postJson(settings, "/$backend/delete-session", JSONObject().put("id", id))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Oturum silinemedi"))
        return setOf(id, json.optString("bridgeSessionId"), json.optString("nativeId"))
            .filter { it.isNotBlank() }
            .toSet()
    }

suspend fun BridgeClient.claudeAppAdopt(settings: BridgeSettings, id: String, cwd: String, cowork: Boolean = false): String {
        val body = JSONObject().put("id", id).put("cwd", cwd)
        if (cowork) body.put("cowork", true)
        val json = postJson(settings, "/claude-app/adopt", body)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Claude App adopt failed"))
        return json.optString("sessionId")
    }

fun BridgeClient.openClaudeAppStream(settings: BridgeSettings, sessionId: String, listener: WebSocketListener): WebSocket {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val wsUrl = normalizeBase(settings.baseUrl)
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/claude-app/stream?session=$session"
        val builder = Request.Builder().url(wsUrl)
        if (settings.token.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.token}")
        }
        val request = builder.build()
        return client.newWebSocket(request, listener)
    }

suspend fun BridgeClient.codexModels(settings: BridgeSettings): List<CodexModel> {
        val json = getJson(settings, "/codex/models")
        val array = json.optJSONArray("models")
        return buildList {
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(CodexModel(item.optString("label"), item.optString("id")))
                }
            }
        }
    }

suspend fun BridgeClient.codexAppModels(settings: BridgeSettings): ModelsResult<CodexModel> {
        val json = getJson(settings, "/codex-app/models")
        val array = json.optJSONArray("models")
        val models = buildList {
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(CodexModel(item.optString("label"), item.optString("id")))
                }
            }
        }
        return ModelsResult(models, json.optString("defaultModel"))
    }

suspend fun BridgeClient.codexNew(settings: BridgeSettings, cwd: String, model: String): String {
        val json = postJson(settings, "/codex/new", JSONObject().put("cwd", cwd).put("model", model))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Codex session failed"))
        return json.optString("sessionId")
    }

suspend fun BridgeClient.codexAppNew(settings: BridgeSettings, cwd: String, model: String, permissionMode: String = ""): String {
        val body = JSONObject().put("cwd", cwd).put("model", model)
        if (permissionMode.isNotBlank()) body.put("permissionMode", permissionMode)
        val json = postJson(settings, "/codex-app/new", body)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Codex App session failed"))
        return json.optString("sessionId")
    }

suspend fun BridgeClient.codexDiskSessions(settings: BridgeSettings): List<CodexDiskSession> {
        val json = getJson(settings, "/codex/disk-sessions")
        val array = json.optJSONArray("sessions") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(CodexDiskSession(
                    id = item.optString("id"),
                    cwd = item.optString("cwd"),
                    title = item.optString("title"),
                    lastText = item.optString("lastText"),
                    turns = item.optInt("turns"),
                    mtime = item.optLong("mtime"),
                ))
            }
        }
    }

suspend fun BridgeClient.codexAppDiskSessions(settings: BridgeSettings, query: String = "", archived: Boolean = false): List<CodexDiskSession> {
        val q = URLEncoder.encode(query, "UTF-8")
        val path = "/codex-app/disk-sessions-search?archived=$archived&q=$q"
        val json = getJson(settings, path)
        val array = json.optJSONArray("sessions") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(CodexDiskSession(
                    id = item.optString("id"),
                    cwd = item.optString("cwd"),
                    title = item.optString("title"),
                    lastText = item.optString("lastText"),
                    turns = item.optInt("turns"),
                    mtime = item.optLong("mtime"),
                    pinned = item.optBoolean("pinned", false),
                    archived = item.optBoolean("archived", false)
                ))
            }
        }
    }

suspend fun BridgeClient.codexAdopt(settings: BridgeSettings, id: String, cwd: String): String {
        val json = postJson(settings, "/codex/adopt", JSONObject().put("id", id).put("cwd", cwd))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Adopt failed"))
        return json.optString("sessionId")
    }

suspend fun BridgeClient.codexAppAdopt(settings: BridgeSettings, id: String, cwd: String): String {
        val json = postJson(settings, "/codex-app/adopt", JSONObject().put("id", id).put("cwd", cwd))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Adopt failed"))
        return json.optString("sessionId")
    }

suspend fun BridgeClient.codexPrompt(settings: BridgeSettings, sessionId: String, text: String, model: String?) {
        val body = JSONObject()
            .put("sessionId", sessionId)
            .put("text", text)
        if (!model.isNullOrBlank()) body.put("model", model)
        val json = postJson(settings, "/codex/prompt", body)
        if (!json.optBoolean("ok", true)) throw IOException(json.optString("error", "Codex prompt failed"))
    }

suspend fun BridgeClient.codexAppPrompt(settings: BridgeSettings, sessionId: String, text: String, model: String?) {
        val body = JSONObject()
            .put("sessionId", sessionId)
            .put("text", text)
            .put("requestId", java.util.UUID.randomUUID().toString())
        if (!model.isNullOrBlank()) body.put("model", model)
        val json = postJson(settings, "/codex-app/prompt", body)
        if (!json.optBoolean("ok", true)) throw IOException(json.optString("error", "Codex App prompt failed"))
    }

suspend fun BridgeClient.codexConversation(settings: BridgeSettings, sessionId: String): ConversationResult {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val json = getJson(settings, "/codex/conversation?sessionId=$session")
        return ConversationResult(
            text = json.optString("text"),
            messages = parseMessages(json),
            running = json.optBoolean("running"),
            awaitingApproval = json.optBoolean("awaitingApproval"),
            approval = parseApproval(json.optJSONObject("approval")),
            awaitingFirstOutput = json.optBoolean("awaitingFirstOutput"),
            choices = parseStringArray(json.optJSONArray("choices")),
            contextTokens = json.optInt("contextTokens"),
            contextWindow = json.optInt("contextWindow"),
            planDraft = json.optString("planDraft", ""),
            interruptStuck = json.optBoolean("interruptStuck", false),
        )
    }

suspend fun BridgeClient.codexAppConversation(settings: BridgeSettings, sessionId: String, before: String = "", limit: Int = 0): ConversationResult {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val beforePart = before.takeIf { it.isNotBlank() }?.let { "&before=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        val limitPart = if (limit > 0) "&limit=$limit" else ""
        val json = getJson(settings, "/codex-app/conversation?sessionId=$session$beforePart$limitPart")
        return ConversationResult(
            text = json.optString("text"),
            messages = parseMessages(json),
            running = json.optBoolean("running"),
            awaitingApproval = json.optBoolean("awaitingApproval"),
            approval = parseApproval(json.optJSONObject("approval")),
            awaitingFirstOutput = json.optBoolean("awaitingFirstOutput"),
            choices = parseStringArray(json.optJSONArray("choices")),
            contextTokens = json.optInt("contextTokens"),
            contextWindow = json.optInt("contextWindow"),
            effort = json.optString("effort"),
            // Hedef poll yolunda uc-durumlu: alan hic yoksa (eski kopru) dokunma;
            // varsa (null bile olsa) yaz. goalPresent bu ayrimi tasir.
            goalPresent = json.has("goal"),
            goal = CodexGoal.fromJson(json.optJSONObject("goal")),
        )
    }

suspend fun BridgeClient.codexThought(settings: BridgeSettings, sessionId: String, index: Int): String {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val json = getJson(settings, "/codex/thought?sessionId=$session&i=$index")
        return json.optString("text")
    }

suspend fun BridgeClient.codexAppThought(settings: BridgeSettings, sessionId: String, index: Int): String {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val json = getJson(settings, "/codex-app/thought?sessionId=$session&i=$index")
        return json.optString("text")
    }

suspend fun BridgeClient.codexStop(settings: BridgeSettings, sessionId: String) {
        postJson(settings, "/codex/stop", JSONObject().put("sessionId", sessionId))
    }

suspend fun BridgeClient.codexAppStop(settings: BridgeSettings, sessionId: String) {
        postJson(settings, "/codex-app/stop", JSONObject().put("sessionId", sessionId))
    }

suspend fun BridgeClient.codexAppApprove(settings: BridgeSettings, sessionId: String, allow: Boolean, decision: String = "", scope: String = "", answers: List<ApprovalAnswer> = emptyList(), requestId: String = "") {
        val body = JSONObject().put("sessionId", sessionId).put("allow", allow).onayKimligiEkle(requestId)
        if (decision.isNotBlank()) body.put("decision", decision)
        if (scope.isNotBlank()) body.put("scope", scope)
        if (answers.isNotEmpty()) {
            val arr = org.json.JSONArray()
            answers.forEach { a ->
                arr.put(JSONObject().put("id", a.questionId).put("optionId", a.optionId).put("label", a.label))
            }
            body.put("answers", arr)
        }
        postJson(settings, "/codex-app/approve", body)
    }

suspend fun BridgeClient.codexAppRespondUserInput(settings: BridgeSettings, sessionId: String, text: String) {
        val body = JSONObject().put("sessionId", sessionId).put("text", text)
        val json = postJson(settings, "/codex-app/respond-user-input", body)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "User input failed"))
    }

suspend fun BridgeClient.codexAppCompact(settings: BridgeSettings, sessionId: String) {
        val json = postJson(settings, "/codex-app/compact", JSONObject().put("sessionId", sessionId))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Compact failed"))
    }

    // Feature 10: Codex App info
suspend fun BridgeClient.codexAppInfo(settings: BridgeSettings): CodexAppInfo {
        val json = getJson(settings, "/codex-app/info")
        val feats = json.optJSONObject("features")
        val features = buildMap {
            if (feats != null) for (key in feats.keys()) put(key, feats.optBoolean(key))
        }
        return CodexAppInfo(
            codexVersion = json.optString("codexVersion"),
            appServer = json.optBoolean("appServer"),
            features = features,
            skills = buildList {
                val arr = json.optJSONArray("skills")
                if (arr != null) for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            },
            skillDetails = parseSkillInfoArray(json.optJSONArray("skillDetails")),
        )
    }

    // Feature 1: Thread fork
suspend fun BridgeClient.codexAppFork(settings: BridgeSettings, sessionId: String, cwd: String = ""): CodexForkResult {
        val body = JSONObject().put("sessionId", sessionId)
        if (cwd.isNotBlank()) body.put("cwd", cwd)
        val json = postJson(settings, "/codex-app/fork", body)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Fork failed"))
        return CodexForkResult(
            ok = true,
            sessionId = json.optString("sessionId"),
            threadId = json.optString("threadId"),
            cwd = json.optString("cwd"),
            model = json.optString("model"),
        )
    }

    // Feature 2: Turn steer
suspend fun BridgeClient.codexAppSteer(settings: BridgeSettings, sessionId: String, text: String) {
        val json = postJson(settings, "/codex-app/steer", JSONObject().put("sessionId", sessionId).put("text", text))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Steer failed"))
    }

    // Feature 5: Command timeline
suspend fun BridgeClient.codexAppCommands(settings: BridgeSettings, sessionId: String): List<CodexCommand> {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val json = getJson(settings, "/codex-app/commands?sessionId=$session")
        val array = json.optJSONArray("commands") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(CodexCommand(
                    id = item.optString("id"),
                    command = item.optString("command"),
                    cwd = item.optString("cwd"),
                    status = item.optString("status"),
                    stdout = item.optString("stdout"),
                    stderr = item.optString("stderr"),
                    exitCode = item.optInt("exitCode"),
                    startedAt = item.optLong("startedAt"),
                    completedAt = item.optLong("completedAt"),
                ))
            }
        }
    }

    // Feature 8: Context fill percentage
suspend fun BridgeClient.codexAppContextPercent(settings: BridgeSettings, sessionId: String): Int {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val json = getJson(settings, "/codex-app/context-percent?sessionId=$session")
        return json.optInt("percent", 0)
    }

    // Feature 6: Permission mode list + set
suspend fun BridgeClient.codexAppPermissionModes(settings: BridgeSettings): List<PermissionModeItem> {
        val json = getJson(settings, "/codex-app/permission-modes")
        val array = json.optJSONArray("modes") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(PermissionModeItem(item.optString("id"), item.optString("label")))
            }
        }
    }

suspend fun BridgeClient.codexAppSetPermissionMode(settings: BridgeSettings, sessionId: String, mode: String): String {
        val json = postJson(settings, "/codex-app/permission-mode", JSONObject().put("sessionId", sessionId).put("permissionMode", mode))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Permission mode failed"))
        return json.optString("permissionMode", mode)
    }

    // Reasoning effort — TurnStartParams.effort; bir sonraki turda geçerli. Desteklenen
    // küme modele göre değişir; bridge model/list'ten effortsByModel haritasını verir.
suspend fun BridgeClient.codexAppEfforts(settings: BridgeSettings): CodexAppEffortInfo {
        val json = getJson(settings, "/codex-app/efforts")
        val byModel = mutableMapOf<String, List<String>>()
        json.optJSONObject("effortsByModel")?.let { obj ->
            for (key in obj.keys()) byModel[key] = parseStringArray(obj.optJSONArray(key))
        }
        val modelDefaults = mutableMapOf<String, String>()
        json.optJSONObject("modelDefaultEfforts")?.let { obj ->
            for (key in obj.keys()) modelDefaults[key] = obj.optString(key, "")
        }
        return CodexAppEffortInfo(
            levels = parseStringArray(json.optJSONArray("efforts")),
            defaultEffort = json.optString("defaultEffort", ""),
            effortsByModel = byModel,
            modelDefaultEfforts = modelDefaults,
        )
    }

suspend fun BridgeClient.codexAppSetEffort(settings: BridgeSettings, sessionId: String, effort: String): String {
        val json = postJson(settings, "/codex-app/effort", JSONObject().put("sessionId", sessionId).put("effort", effort))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Effort değiştirilemedi"))
        return json.optString("effort", effort)
    }

    // Feature 7: Archive/pin/search
suspend fun BridgeClient.codexAppDiskSessionsSearch(settings: BridgeSettings, query: String = "", archived: Boolean? = null, pinned: Boolean? = null): List<CodexDiskSession> {
        val params = StringBuilder("/codex-app/disk-sessions-search?")
        if (query.isNotBlank()) params.append("q=${URLEncoder.encode(query, "UTF-8")}&")
        if (archived != null) params.append("archived=$archived&")
        if (pinned != null) params.append("pinned=$pinned&")
        val json = getJson(settings, params.toString().trimEnd('&'))
        val array = json.optJSONArray("sessions") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(CodexDiskSession(
                    id = item.optString("id"),
                    cwd = item.optString("cwd"),
                    title = item.optString("title"),
                    lastText = item.optString("lastText"),
                    turns = item.optInt("turns"),
                    mtime = item.optLong("mtime"),
                ))
            }
        }
    }

suspend fun BridgeClient.codexAppArchive(settings: BridgeSettings, id: String) {
        postJson(settings, "/codex-app/archive", JSONObject().put("id", id))
    }

suspend fun BridgeClient.codexAppUnarchive(settings: BridgeSettings, id: String) {
        postJson(settings, "/codex-app/unarchive", JSONObject().put("id", id))
    }

suspend fun BridgeClient.codexAppPin(settings: BridgeSettings, id: String) {
        postJson(settings, "/codex-app/pin", JSONObject().put("id", id))
    }

suspend fun BridgeClient.codexAppUnpin(settings: BridgeSettings, id: String) {
        postJson(settings, "/codex-app/unpin", JSONObject().put("id", id))
    }

suspend fun BridgeClient.codexAppRename(settings: BridgeSettings, id: String, title: String) {
        postJson(settings, "/codex-app/rename", JSONObject().put("id", id).put("title", title))
    }

    // Feature 9: Plan items for navigation
suspend fun BridgeClient.codexAppPlanItems(settings: BridgeSettings, sessionId: String): List<PlanItem> {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val json = getJson(settings, "/codex-app/plan-items?sessionId=$session")
        val array = json.optJSONArray("plan") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(PlanItem(
                    text = item.optString("text"),
                    status = item.optString("status", "pending"),
                    itemId = item.optString("itemId", ""),
                    turnId = item.optString("turnId", ""),
                ))
            }
        }
    }

    // Oturum hedefi (/goal). GET null-goal dondurebilir (threadId henuz yoksa). set,
    // status:"active" ile app-server'da hemen bir tur baslatir — "kur ve baslat".
suspend fun BridgeClient.codexGoal(settings: BridgeSettings, sessionId: String): CodexGoal? {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val json = getJson(settings, "/codex-app/goal?sessionId=$session")
        if (!json.optBoolean("ok", true)) throw IOException(json.optString("error", "Hedef alınamadı"))
        return CodexGoal.fromJson(json.optJSONObject("goal"))
    }

suspend fun BridgeClient.codexGoalSet(settings: BridgeSettings, sessionId: String, objective: String): CodexGoal? {
        // status:"active" acikca gonderilir: hedefi kurunca Codex hemen calismaya koyulsun.
        val body = JSONObject().put("sessionId", sessionId).put("objective", objective).put("status", "active")
        val json = postJson(settings, "/codex-app/goal/set", body)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Hedef kurulamadı"))
        return CodexGoal.fromJson(json.optJSONObject("goal"))
    }

// silent: biten hedefin bildirimini kapatirken kullanilir — kopru transcript'e
// "/goal temizle" satiri YAZMAZ. Komutla temizlemede (varsayilan) satir dusler.
suspend fun BridgeClient.codexGoalClear(settings: BridgeSettings, sessionId: String, silent: Boolean = false): Boolean {
        val json = postJson(settings, "/codex-app/goal/clear", JSONObject().put("sessionId", sessionId).put("silent", silent))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Hedef temizlenemedi"))
        return json.optBoolean("cleared", true)
    }

    // OMP native RPC info (skill komutları + runtime MCP envanteri).
    suspend fun BridgeClient.ompInfo(settings: BridgeSettings): OmpAppInfo {
        val json = getJson(settings, "/omp/info")
        return OmpAppInfo(
            serveAlive = json.optBoolean("serveAlive"),
            skills = buildList {
                val arr = json.optJSONArray("skills")
                if (arr != null) for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            },
            skillDetails = parseSkillInfoArray(json.optJSONArray("skillDetails")),
        )
    }

/**
 * Oturumun dosya değişiklikleri ("Değişiklikler" görünümü).
 *
 * Bugün yalnız `/codex-app/diff`. [base] yine parametre: şema backend'den
 * bağımsız, köprü dönüştürüyor.
 *
 * YOKLAMAYA BİNMEZ: kullanıcı görünümü açınca çağrılır. Köprüdeki kaynak akan
 * fileChange item'ları; ölçüm notları codex-app.mjs sessionDiff başlığında.
 */
suspend fun BridgeClient.backendSessionDiff(settings: BridgeSettings, base: String, sessionId: String): BackendSessionDiff {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val json = getJson(settings, "/$base/diff?session=$session")
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Değişiklikler alınamadı"))
        val array = json.optJSONArray("files")
        val files = buildList {
            for (i in 0 until (array?.length() ?: 0)) {
                val item = array?.optJSONObject(i) ?: continue
                val path = item.optString("path")
                if (path.isBlank()) continue
                add(BackendDiffFile(
                    path = path,
                    additions = item.optInt("additions"),
                    deletions = item.optInt("deletions"),
                    status = item.optString("status", "modified"),
                    patch = item.optString("patch", ""),
                    truncated = item.optBoolean("truncated", false),
                ))
            }
        }
        return BackendSessionDiff(
            files = files,
            additions = json.optInt("additions"),
            deletions = json.optInt("deletions"),
            turns = json.optInt("turns"),
            truncated = json.optBoolean("truncated", false),
            // Alanı göndermeyen köprü = geçmiş kaybı yok; yokluğu false demek.
            historyGap = json.optBoolean("historyGap", false),
        )
    }

// --- OMP native RPC backend ---
suspend fun BridgeClient.ompModels(settings: BridgeSettings): ModelsResult<BackendModel> {
    val json = getJson(settings, "/omp/models")
    return ModelsResult(parseBackendModels(json.optJSONArray("models")), json.optString("defaultModel"))
}

suspend fun BridgeClient.ompNew(settings: BridgeSettings, cwd: String, model: String, permissionMode: String = "yolo", effort: String = ""): String {
    val body = JSONObject().put("cwd", cwd).put("model", model).put("permissionMode", permissionMode)
    if (effort.isNotBlank()) body.put("effort", effort)
    val json = postJson(settings, "/omp/new", body)
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "OMP session failed"))
    return json.optString("sessionId")
}

suspend fun BridgeClient.ompPrompt(settings: BridgeSettings, sessionId: String, text: String, model: String?, permissionMode: String = "", effort: String = "") {
    val body = JSONObject().put("sessionId", sessionId).put("text", text).put("requestId", java.util.UUID.randomUUID().toString())
    if (!model.isNullOrBlank()) body.put("model", model)
    if (permissionMode.isNotBlank()) body.put("permissionMode", permissionMode)
    if (effort.isNotBlank()) body.put("variant", effort)
    val json = postJson(settings, "/omp/prompt", body)
    if (!json.optBoolean("ok", true)) throw IOException(json.optString("error", "OMP prompt failed"))
}

suspend fun BridgeClient.ompConversation(settings: BridgeSettings, sessionId: String): ConversationResult {
    val session = URLEncoder.encode(sessionId, "UTF-8")
    val json = getJson(settings, "/omp/conversation?sessionId=$session")
    return ConversationResult(
        text = json.optString("text"), messages = parseMessages(json), running = json.optBoolean("running"),
        awaitingApproval = json.optBoolean("awaitingApproval"), approval = parseApproval(json.optJSONObject("approval")),
        awaitingFirstOutput = json.optBoolean("awaitingFirstOutput"), contextTokens = json.optInt("contextTokens"),
        contextWindow = json.optInt("contextWindow"), permissionMode = json.optString("permissionMode"), effort = json.optString("effort"),
    )
}

suspend fun BridgeClient.ompThought(settings: BridgeSettings, sessionId: String, index: Int): String {
    val sid = URLEncoder.encode(sessionId, "UTF-8")
    return getJson(settings, "/omp/thought?sessionId=$sid&i=$index").optString("text")
}

suspend fun BridgeClient.ompStop(settings: BridgeSettings, sessionId: String) {
    postJson(settings, "/omp/stop", JSONObject().put("sessionId", sessionId))
}

suspend fun BridgeClient.ompCompact(settings: BridgeSettings, sessionId: String): Boolean =
    postJson(settings, "/omp/compact", JSONObject().put("sessionId", sessionId)).optBoolean("ok")

// Arama/arşiv istendiğinde codex-app ile aynı uç kullanılır. Düz /disk-sessions
// arşivlenenleri ELEMEZ diye varsayma — köprü onları oradan çıkarıyor, arşiv
// görünümü yalnız bu uçtan gelir.
suspend fun BridgeClient.ompDiskSessions(
    settings: BridgeSettings,
    query: String = "",
    archived: Boolean = false,
): List<AppDiskSession> {
    val path = if (query.isNotBlank() || archived) {
        "/omp/disk-sessions-search?archived=$archived&q=" + URLEncoder.encode(query, "UTF-8")
    } else "/omp/disk-sessions"
    val array = getJson(settings, path).optJSONArray("sessions") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(AppDiskSession(
                item.optString("id"), item.optString("cwd"), item.optString("title"),
                item.optString("lastText"), item.optInt("turns"), item.optLong("mtime"),
                item.optBoolean("pinned"), item.optBoolean("archived"),
            ))
        }
    }
}

/** Tur sürerken gönderim. steer = süren turu keser, followUp = turdan sonra çalışır. */
suspend fun BridgeClient.ompSteer(settings: BridgeSettings, sessionId: String, text: String) {
    val json = postJson(settings, "/omp/steer", JSONObject().put("sessionId", sessionId).put("text", text))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Yönlendirilemedi"))
}

suspend fun BridgeClient.ompFollowUp(settings: BridgeSettings, sessionId: String, text: String) {
    val json = postJson(settings, "/omp/follow-up", JSONObject().put("sessionId", sessionId).put("text", text))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Sıraya alınamadı"))
}

suspend fun BridgeClient.ompPin(settings: BridgeSettings, id: String) {
    postJson(settings, "/omp/pin", JSONObject().put("id", id))
}
suspend fun BridgeClient.ompUnpin(settings: BridgeSettings, id: String) {
    postJson(settings, "/omp/unpin", JSONObject().put("id", id))
}
suspend fun BridgeClient.ompRename(settings: BridgeSettings, id: String, title: String) {
    postJson(settings, "/omp/rename", JSONObject().put("id", id).put("title", title))
}
suspend fun BridgeClient.ompArchive(settings: BridgeSettings, id: String) {
    postJson(settings, "/omp/archive", JSONObject().put("id", id))
}
suspend fun BridgeClient.ompUnarchive(settings: BridgeSettings, id: String) {
    postJson(settings, "/omp/unarchive", JSONObject().put("id", id))
}

suspend fun BridgeClient.ompAdopt(settings: BridgeSettings, id: String, cwd: String): String {
    val json = postJson(settings, "/omp/adopt", JSONObject().put("id", id).put("cwd", cwd))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "OMP adopt failed"))
    return json.optString("sessionId")
}

suspend fun BridgeClient.ompApprove(settings: BridgeSettings, sessionId: String, allow: Boolean, answers: List<ApprovalAnswer> = emptyList(), requestId: String = "") {
    val body = JSONObject().put("sessionId", sessionId).put("allow", allow).onayKimligiEkle(requestId)
    if (answers.isNotEmpty()) {
        val arr = org.json.JSONArray()
        answers.forEach { arr.put(JSONObject().put("id", it.questionId).put("optionId", it.optionId).put("label", it.label)) }
        body.put("answers", arr)
    }
    postJson(settings, "/omp/approve", body)
}

suspend fun BridgeClient.ompSetModel(settings: BridgeSettings, sessionId: String, model: String): String {
    val json = postJson(settings, "/omp/model", JSONObject().put("sessionId", sessionId).put("model", model))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "OMP model değiştirilemedi"))
    return json.optString("model", model)
}

suspend fun BridgeClient.ompSetPermissionMode(settings: BridgeSettings, sessionId: String, mode: String): String {
    val json = postJson(settings, "/omp/permission-mode", JSONObject().put("sessionId", sessionId).put("permissionMode", mode))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "OMP permission mode failed"))
    return json.optString("permissionMode", mode)
}

suspend fun BridgeClient.ompPermissionModes(settings: BridgeSettings): List<PermissionMode> {
    val json = getJson(settings, "/omp/permission-modes")
    val array = json.optJSONArray("modes") ?: json.optJSONArray("permissionModes") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
            if (item != null) add(PermissionMode(item.optString("id"), item.optString("name", item.optString("id")), item.optString("description")))
            else array.optString(i).takeIf { it.isNotBlank() }?.let { add(PermissionMode(it, it)) }
        }
    }
}

suspend fun BridgeClient.ompEfforts(settings: BridgeSettings): List<String> {
    val array = getJson(settings, "/omp/efforts").optJSONArray("efforts") ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optString(it).ifBlank { null } }
}

suspend fun BridgeClient.ompSetEffort(settings: BridgeSettings, sessionId: String, effort: String): String {
    val json = postJson(settings, "/omp/effort", JSONObject().put("sessionId", sessionId).put("effort", effort))
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "OMP düşünme seviyesi değiştirilemedi"))
    return json.optString("effort", effort)
}

    // --- Antigravity CLI (agy) session API ---
suspend fun BridgeClient.agyModels(settings: BridgeSettings): List<AgyModel> {
        val json = getJson(settings, "/agy/models")
        val array = json.optJSONArray("models")
        return buildList {
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(AgyModel(item.optString("label"), item.optString("id")))
                }
            }
        }
    }

suspend fun BridgeClient.agyNew(settings: BridgeSettings, cwd: String, model: String): String {
        val json = postJson(settings, "/agy/new", JSONObject().put("cwd", cwd).put("model", model))
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Antigravity CLI session failed"))
        return json.optString("sessionId")
    }

suspend fun BridgeClient.agyPrompt(settings: BridgeSettings, sessionId: String, text: String, model: String?) {
        val body = JSONObject()
            .put("sessionId", sessionId)
            .put("text", text)
            .put("requestId", java.util.UUID.randomUUID().toString())
        if (!model.isNullOrBlank()) body.put("model", model)
        val json = postJson(settings, "/agy/prompt", body)
        if (!json.optBoolean("ok", true)) throw IOException(json.optString("error", "Antigravity CLI prompt failed"))
    }

suspend fun BridgeClient.agyConversation(settings: BridgeSettings, sessionId: String): ConversationResult {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val json = getJson(settings, "/agy/conversation?sessionId=$session")
        return ConversationResult(
            text = json.optString("text"),
            messages = parseMessages(json),
            running = json.optBoolean("running"),
            awaitingApproval = json.optBoolean("awaitingApproval"),
            awaitingFirstOutput = json.optBoolean("awaitingFirstOutput"),
            choices = parseStringArray(json.optJSONArray("choices")),
            contextTokens = json.optInt("contextTokens"),
            contextWindow = json.optInt("contextWindow"),
        )
    }

suspend fun BridgeClient.agyDiskSessions(settings: BridgeSettings): List<AgyDiskSession> {
        val json = getJson(settings, "/agy/disk-sessions")
        val array = json.optJSONArray("sessions") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(AgyDiskSession(
                    id = item.optString("id"),
                    cwd = item.optString("cwd"),
                    title = item.optString("title"),
                    lastText = item.optString("lastText"),
                    turns = item.optInt("turns"),
                    mtime = item.optLong("mtime"),
                    source = item.optString("source"),
                    sourceLabel = item.optString("sourceLabel"),
                ))
            }
        }
    }

suspend fun BridgeClient.agyAdopt(settings: BridgeSettings, id: String, cwd: String, source: String = "", title: String = "", model: String = ""): String {
        val body = JSONObject().put("id", id).put("cwd", cwd)
        if (source.isNotBlank()) body.put("source", source)
        if (title.isNotBlank()) body.put("title", title)
        if (model.isNotBlank()) body.put("model", model)
        val json = postJson(settings, "/agy/adopt", body)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Adopt failed"))
        return json.optString("sessionId")
    }

suspend fun BridgeClient.agyStop(settings: BridgeSettings, sessionId: String) {
        postJson(settings, "/agy/stop", JSONObject().put("sessionId", sessionId))
    }

/**
 * "Turu durdur" — backend → uç eşlemesinin TEK yeri.
 *
 * Çağıran: sohbet ekranındaki Durdur tuşu (`RemoteViewModel.stop()`). Kapsül
 * bildirimindeki ikinci çağıran 21.10.2026'da bildirimlerle birlikte kalktı;
 * eşleme yine burada tek yerde kalsın.
 *
 * `cowork` burada YOK: cowork operasyon olayı üretmiyor (server.mjs:253-259),
 * dolayısıyla kapsülde hiç görünmüyor. Çağıran cowork'ü kendi sağlayıcısına
 * (`normalizeCoworkProvider`) çevirip öyle verir.
 *
 * Boş sessionId sessizce yok sayılır: eski davranış da "durdurulacak bir şey
 * yoksa başarı" idi (kullanıcı arayüzü yine de durdu durumuna geçmeli).
 */
suspend fun BridgeClient.turDurdur(settings: BridgeSettings, backend: String, sessionId: String) {
    if (sessionId.isBlank()) return
    when (backend) {
        "agy" -> agyStop(settings, sessionId)
        "claude-app" -> claudeAppInterrupt(settings, sessionId)
        "codex-app" -> codexAppStop(settings, sessionId)
        "omp" -> ompStop(settings, sessionId)
        else -> Unit
    }
}

suspend fun BridgeClient.agyOpenAntigravity(settings: BridgeSettings) {
        val json = postJson(settings, "/agy/open", JSONObject())
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Antigravity acilamadi"))
    }

fun BridgeClient.openAgyStream(settings: BridgeSettings, sessionId: String, listener: WebSocketListener): WebSocket {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val wsUrl = normalizeBase(settings.baseUrl)
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/agy/stream?session=$session"
        val builder = Request.Builder().url(wsUrl)
        if (settings.token.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.token}")
        }
        val request = builder.build()
        return client.newWebSocket(request, listener)
    }

fun BridgeClient.openCodexAppStream(settings: BridgeSettings, sessionId: String, listener: WebSocketListener): WebSocket {
        val session = URLEncoder.encode(sessionId, "UTF-8")
        val wsUrl = normalizeBase(settings.baseUrl)
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/codex-app/stream?session=$session"
        val builder = Request.Builder().url(wsUrl)
        if (settings.token.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.token}")
        }
        val request = builder.build()
        return client.newWebSocket(request, listener)
    }

// ── Seçici sabitlemeleri (yıldızlar) — köprü tarafı ─────────────────────────
// Yıldız cihazda değil köprüde saklanır (GET/POST /ui-pins): telefon ve tablet
// aynı köprüye bağlandığından aralarında otomatik paylaşılır. İki PC'nin iki
// köprüsü AYRI depo tutar; eşitleme kasıtlı olarak yok.
//
// Çakışma politikası son yazan kazanır: push kapsamın TAM setini gönderir ve
// köprüdeki eski değer ezilir.

/** GET /ui-pins yanıtı. [existed]=false → köprüde hiç yıldız yazılmamış; istemci
 *  yerel yıldızlarını bir kez taşır (migration), varsa KÖPRÜ kaynak sayılır. */
data class SelectorPinSnapshot(
    val existed: Boolean,
    val pins: Map<String, List<String>>,
)

suspend fun BridgeClient.selectorPinsFetch(settings: BridgeSettings): SelectorPinSnapshot {
    val json = getJson(settings, "/ui-pins")
    val obj = json.optJSONObject("pins")
    val map = mutableMapOf<String, List<String>>()
    if (obj != null) {
        for (key in obj.keys()) {
            val arr = obj.optJSONArray(key) ?: continue
            val keys = buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i)
                    if (!v.isNullOrBlank()) add(v)
                }
            }
            if (keys.isNotEmpty()) map[key] = keys
        }
    }
    return SelectorPinSnapshot(existed = json.optBoolean("existed"), pins = map)
}

suspend fun BridgeClient.selectorPinsPush(settings: BridgeSettings, scope: String, pinned: Set<String>) {
    val body = JSONObject().put("scope", scope).put("pinned", JSONArray(pinned.toList()))
    val json = postJson(settings, "/ui-pins", body)
    if (!json.optBoolean("ok")) throw IOException(json.optString("error", "Yıldızlar kaydedilemedi"))
}
