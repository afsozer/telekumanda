package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class PlanItem(val text: String, val status: String, val itemId: String = "", val turnId: String = "")

data class BackendStreamSnapshot(
    val seq: Long?,
    // Bridge'in snapshot'a gömdüğü oturum kimliği. Akışın açıldığı id'den farklıysa
    // sunucu oturumu re-key etmiştir (ör. başka bir istemcinin /clear'ı) — A1 savunması.
    val sessionId: String? = null,
    val transcript: String?,
    val messages: List<ChatMessage>?,
    val running: Boolean?,
    val awaitingApproval: Boolean?,
    val awaitingUserInput: Boolean? = null,
    val approval: ApprovalInfo? = null,
    val awaitingFirstOutput: Boolean?,
    val choices: List<String>?,
    val contextTokens: Int?,
    val contextWindow: Int?,
    val permissionMode: String? = null,
    // Reasoning effort (çaba seviyesi) — claude-app/codex-app snapshot'ında taşınır.
    val effort: String? = null,
    val cost: Double? = null,
    val plan: List<PlanItem>? = null,
    val planDraft: String? = null,
    val interruptStuck: Boolean? = null,
    val availableModels: List<BackendModel>? = null,
    val permissionModes: List<PermissionMode>? = null,
    val commands: List<SlashCommand>? = null,
    // opencode ajan secimi ("" = otomatik). null = snapshot'ta alan yok (eski köprü).
    val agent: String? = null,
    // Turu GERÇEKTEN koşan/koşacak ajan — oto kipte köprünün kararı (yerel/
    // runpod/build). null = karede alan yok (eski köprü) -> dokunma; "" = köprü
    // henüz bilmiyor (ajan listesi okunmadı) ve çip yalnız "otomatik" yazar.
    val resolvedAgent: String? = null,
    // Cowork: tur sonunda teslim edilen dosyalar (snapshot.outputs → coworkOutputs).
    val outputs: List<CoworkOutput>? = null,
    // Codex oturum hedefi — uc durumlu. goalPresent=false: snapshot'ta "goal" alani
    // hic yok (eski kopru) -> mevcut hedefe DOKUNMA. goalPresent=true + goal=null:
    // alan geldi ama null -> hedefi TEMIZLE. goalPresent=true + goal dolu -> yaz.
    // awaitingUserInput gibi opsiyonel alanlarin has()-ayrimini nesneye tasimak icin
    // ayri bir bayrak gerekti (null tek basina alan-yok/temiz ayrimini veremiyor).
    val goalPresent: Boolean = false,
    val goal: CodexGoal? = null,
    // GÖREV PANOSU. todos null = karede alan yok (eski köprü ya da değişmemiş
    // delta) -> mevcut listeye DOKUNMA; boş liste = ajan listeyi temizledi.
    val todos: List<OpencodeTodo>? = null,
    // contextPct null tek başına yetmiyor: null "ölçülemiyor" ANLAMINDA da
    // gelebiliyor (pencere bilinmiyor). goalPresent ile aynı üç-durum ayrımı —
    // alan hiç yoksa dokunma, alan var null ise sayacı temizle.
    val contextPctPresent: Boolean = false,
    val contextPct: Int? = null,
    // Checkpoint geri sarma durumu (opencode). goalPresent ile aynı üç-durum:
    // alan hiç yoksa dokunma, alan var null ise şeridi kaldır. Köprüde
    // META_KEYS'te olduğu için delta kipinde de taşınıyor.
    val revertedPresent: Boolean = false,
    val reverted: OpencodeRevertState? = null,
    // ALT AJANLAR. todos ile aynı üç-durum: null = karede alan yok -> dokunma,
    // boş liste = gerçekten alt-ajan kalmadı.
    val subagents: List<OpencodeSubagent>? = null,
    // Oturum paylaşım linki (opencode). null = karede alan yok -> dokunma;
    // "" = yayında değil. Köprüde META_KEYS'te olduğu için delta kipinde de
    // taşınıyor; başka bir cihazdan/TUI'den paylaşınca burada da görünür.
    val share: String? = null,
)

// Cowork teslimat dosyası — bridge snapshot'ındaki outputs[] alanıyla birebir.
data class CoworkOutput(val name: String, val path: String, val size: Long, val mtime: Double)
private data class StreamRow(val rowId: String, val message: ChatMessage)

class SessionStreamManager(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val onSnapshot: (String, BackendStreamSnapshot) -> Unit,
    private val onEnd: (String) -> Unit,
    private val onError: (String, String) -> Unit,
) {
    private data class StashEntry(val lastSeq: Long?, val rows: MutableList<StreamRow>)

    private var socket: WebSocket? = null
    private var currentBackend: String = ""
    private var currentSessionId: String = ""
    private var currentSettings: BridgeSettings? = null
    private var lastSeq: Long? = null
    private var rows: MutableList<StreamRow> = mutableListOf()
    // Ayrılınan oturumun (lastSeq + satırlar) durağı: aynı oturuma dönüşte soket
    // since=lastSeq ile açılır ve köprü yalnız kaçan deltaları yollar (delta ring;
    // yetmezse sunucu kendiliğinden tam snapshot'a düşer). Eskiden her geçişte
    // sıfırdan tam snapshot indiriliyordu.
    private val stash = StreamSessionStash<StashEntry>()
    // Bumped on every open()/close(). A listener or pending reconnect whose epoch no
    // longer matches is stale (the target session changed or was closed) and must no-op,
    // so an old socket dying can never reconnect a session the user has since left.
    private var epoch = 0
    private var reconnectJob: Job? = null
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun open(backend: String, sessionId: String, settings: BridgeSettings) {
        close()
        epoch++
        currentBackend = backend
        currentSessionId = sessionId
        currentSettings = settings
        val restored = stash.take("$backend:$sessionId")
        lastSeq = restored?.lastSeq
        rows = restored?.rows ?: mutableListOf()
        _isConnected.value = false
        connect(epoch, backend, sessionId, settings)
    }

    private fun connect(myEpoch: Int, backend: String, sessionId: String, settings: BridgeSettings) {
        val since = lastSeq
        val newSocket = client.openBackendStream(settings, backend, sessionId, since, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myEpoch == epoch) _isConnected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myEpoch != epoch) return
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myEpoch != epoch) return
                _isConnected.value = false
                scheduleReconnect(myEpoch, backend, sessionId, settings)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myEpoch != epoch) return
                _isConnected.value = false
                scheduleReconnect(myEpoch, backend, sessionId, settings)
            }
        })
        socket = newSocket
    }

    // Reconnect with capped exponential backoff. Guarded by epoch so a session the user
    // has navigated away from (or closed) never silently reconnects in the background.
    private fun scheduleReconnect(myEpoch: Int, backend: String, sessionId: String, settings: BridgeSettings) {
        if (myEpoch != epoch) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (isActive && myEpoch == epoch && !_isConnected.value) {
                val backoffMs = (1000L * (1 shl attempt.coerceAtMost(4))).coerceAtMost(15_000L)
                delay(backoffMs)
                if (myEpoch != epoch || _isConnected.value) return@launch
                socket?.cancel()
                connect(myEpoch, backend, sessionId, settings)
                attempt++
                // Give the new socket time to open before deciding to retry again.
                delay(3_000L)
            }
        }
    }

    // Uygulama öne gelince çağrılır: bekleyen backoff'u iptal edip soketi ANINDA
    // tazele. Arka planda süreç dondurulunca soket yarı-açık kalabiliyor (app
    // "bağlı" sanır ama veri akmaz) ve bunu fark etmek ping (25 sn) + backoff
    // (15+3 sn) beklemesine kalıyordu — dönüşte sohbet 15-20 sn donuk görünüyordu.
    // since=lastSeq korunur: sunucu delta ring'den yalnız kaçanları yollar
    // (ring yetmezse tam snapshot'a düşer), yani koşulsuz tazelemek ucuzdur.
    fun nudge() {
        if (currentBackend.isBlank() || currentSessionId.isBlank()) return
        val settings = currentSettings ?: return
        epoch++
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.cancel()
        socket = null
        _isConnected.value = false
        connect(epoch, currentBackend, currentSessionId, settings)
    }

    fun close() {
        // Kapanan oturumun akış hafızası atılmaz, kenara konur: dönüşte since ile
        // yalnız kaçanlar istenir. rows aşağıda YENİ listeye bağlandığı için
        // stash'teki liste bu noktadan sonra mutasyona uğramaz.
        if (currentSessionId.isNotBlank() && rows.isNotEmpty()) {
            stash.put("$currentBackend:$currentSessionId", StashEntry(lastSeq, rows))
        }
        epoch++
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, null)
        socket = null
        currentBackend = ""
        currentSessionId = ""
        currentSettings = null
        lastSeq = null
        rows = mutableListOf()
        _isConnected.value = false
    }

    val sessionId: String get() = currentSessionId

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "conversation", "snapshot" -> {
                    val seq = if (json.has("seq")) json.optLong("seq") else null
                    if (seq != null) lastSeq = seq
                    rows = parseSnapshotRows(json).toMutableList()
                    val snapshot = BackendStreamSnapshot(
                        seq = seq,
                        sessionId = json.optString("sessionId").takeIf { it.isNotBlank() },
                        transcript = json.optString("text"),
                        messages = rows.map { it.message },
                        running = json.optBoolean("running"),
                        awaitingApproval = json.optBoolean("awaitingApproval"),
                        awaitingUserInput = if (json.has("awaitingUserInput")) json.optBoolean("awaitingUserInput") else null,
                        approval = parseApproval(json.optJSONObject("approval")),
                        awaitingFirstOutput = json.optBoolean("awaitingFirstOutput"),
                        choices = parseStringArray(json.optJSONArray("choices")),
                        contextTokens = json.optInt("contextTokens"),
                        contextWindow = json.optInt("contextWindow"),
                        permissionMode = if (json.has("permissionMode")) json.optString("permissionMode") else null,
                        effort = if (json.has("effort")) json.optString("effort") else null,
                        cost = if (json.has("cost")) json.optDouble("cost", 0.0) else null,
                        plan = parsePlanArray(json.optJSONArray("plan")),
                        planDraft = if (json.has("planDraft")) json.optString("planDraft") else null,
                        interruptStuck = if (json.has("interruptStuck")) json.optBoolean("interruptStuck") else null,
                        availableModels = parseSnapshotBackendModels(json.optJSONArray("availableModels")),
                        permissionModes = parseSnapshotPermissionModes(json.optJSONArray("permissionModes")),
                        commands = parseSnapshotSlashCommands(json.optJSONArray("commands")),
                        agent = if (json.has("agent")) json.optString("agent") else null,
                        resolvedAgent = if (json.has("resolvedAgent")) json.optString("resolvedAgent") else null,
                        outputs = parseCoworkOutputs(json.optJSONArray("outputs")),
                        goalPresent = json.has("goal"),
                        goal = CodexGoal.fromJson(json.optJSONObject("goal")),
                        todos = parseTodoArray(json.optJSONArray("todos")),
                        contextPctPresent = json.has("contextPct"),
                        contextPct = parseContextPct(json),
                        revertedPresent = json.has("reverted"),
                        reverted = parseRevertState(json),
                        subagents = parseSubagentArray(json.optJSONArray("subagents")),
                        share = if (json.has("share")) json.optString("share") else null,
                    )
                    onSnapshot(currentSessionId, snapshot)
                }
                "delta" -> onSnapshot(currentSessionId, applyDelta(json))
                "end" -> onEnd(currentSessionId)
                "error" -> onError(currentSessionId, json.optString("error", "Stream error"))
                else -> Unit
            }
        } catch (_: Exception) {
        }
    }

    private fun applyDelta(json: JSONObject): BackendStreamSnapshot {
        val seq = if (json.has("seq")) json.optLong("seq") else null
        if (seq != null) lastSeq = seq
        // Delta zarfı da kök sessionId taşır; re-key (A1 savunması) delta modunda da algılansın.
        val sessionId = json.optString("sessionId").takeIf { it.isNotBlank() }
        val ops = json.optJSONArray("ops") ?: return BackendStreamSnapshot(
            seq = seq, sessionId = sessionId, transcript = null, messages = null, running = null,
            awaitingApproval = null, awaitingFirstOutput = null, choices = null,
            contextTokens = null, contextWindow = null,
        )
        var changedRows = false
        var meta = JSONObject()
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            when (op.optString("op")) {
                "appendRow" -> {
                    op.optJSONObject("row")?.let {
                        rows.add(parseStreamRow(it, rows.size))
                        changedRows = true
                    }
                }
                "patchRow" -> {
                    val rowId = op.optString("rowId")
                    val rowObj = op.optJSONObject("row") ?: continue
                    val idx = rows.indexOfFirst { it.rowId == rowId }
                    if (idx >= 0) rows[idx] = parseStreamRow(rowObj, idx)
                    else rows.add(parseStreamRow(rowObj, rows.size))
                    changedRows = true
                }
                "appendText" -> {
                    val rowId = op.optString("rowId")
                    val chunk = op.optString("chunk")
                    val idx = rows.indexOfFirst { it.rowId == rowId }
                    if (idx >= 0) {
                        val cur = rows[idx]
                        rows[idx] = cur.copy(message = cur.message.copy(text = cur.message.text + chunk))
                        changedRows = true
                    }
                }
                "dropRows" -> {
                    val beforeRowId = op.optString("beforeRowId")
                    val idx = rows.indexOfFirst { it.rowId == beforeRowId }
                    rows = if (idx >= 0) rows.drop(idx).toMutableList() else mutableListOf()
                    changedRows = true
                }
                "setMeta" -> {
                    meta = op.optJSONObject("meta") ?: meta
                }
            }
        }
        return snapshotFromJson(meta, seq, if (changedRows) rows.map { it.message } else null)
            .copy(sessionId = sessionId)
    }

    private fun parseSnapshotMessages(json: JSONObject): List<ChatMessage> {
        return parseSnapshotRows(json).map { it.message }
    }

    private fun parseSnapshotRows(json: JSONObject): List<StreamRow> {
        val parsed = mutableListOf<StreamRow>()
        val array = json.optJSONArray("messages") ?: return emptyList()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            parsed.add(parseStreamRow(item, i))
        }
        return parsed
    }

    private fun parseStreamRow(item: JSONObject, index: Int): StreamRow {
        val fallback = "${item.optString("role")}:${item.optInt("thoughtIndex", -1)}:$index"
        val rowId = item.optString("rowId", fallback)
        val message = ChatMessage(
            role = item.optString("role"),
            text = item.optString("text"),
            time = item.optString("time"),
            thoughtIndex = item.optInt("thoughtIndex", -1),
            rowId = rowId,
        )
        return StreamRow(rowId, message)
    }

    private fun snapshotFromJson(json: JSONObject, seq: Long?, messages: List<ChatMessage>?): BackendStreamSnapshot {
        return BackendStreamSnapshot(
            seq = seq,
            transcript = if (json.has("text")) json.optString("text") else null,
            messages = messages,
            running = if (json.has("running")) json.optBoolean("running") else null,
            awaitingApproval = if (json.has("awaitingApproval")) json.optBoolean("awaitingApproval") else null,
            awaitingUserInput = if (json.has("awaitingUserInput")) json.optBoolean("awaitingUserInput") else null,
            approval = parseApproval(json.optJSONObject("approval")),
            awaitingFirstOutput = if (json.has("awaitingFirstOutput")) json.optBoolean("awaitingFirstOutput") else null,
            choices = parseStringArray(json.optJSONArray("choices")),
            contextTokens = if (json.has("contextTokens")) json.optInt("contextTokens") else null,
            contextWindow = if (json.has("contextWindow")) json.optInt("contextWindow") else null,
            permissionMode = if (json.has("permissionMode")) json.optString("permissionMode") else null,
            effort = if (json.has("effort")) json.optString("effort") else null,
            cost = if (json.has("cost")) json.optDouble("cost", 0.0) else null,
            plan = parsePlanArray(json.optJSONArray("plan")),
            planDraft = if (json.has("planDraft")) json.optString("planDraft") else null,
            interruptStuck = if (json.has("interruptStuck")) json.optBoolean("interruptStuck") else null,
            availableModels = parseSnapshotBackendModels(json.optJSONArray("availableModels")),
            permissionModes = parseSnapshotPermissionModes(json.optJSONArray("permissionModes")),
            commands = parseSnapshotSlashCommands(json.optJSONArray("commands")),
            agent = if (json.has("agent")) json.optString("agent") else null,
            // Delta setMeta yolu: köprüde META_KEYS'te. Değer tam da tur
            // başlarken değişiyor ve uzun koşuda tam snapshot neredeyse hiç
            // gelmiyor — burayı atlamak göstergeyi ilk kareye çivilerdi.
            resolvedAgent = if (json.has("resolvedAgent")) json.optString("resolvedAgent") else null,
            outputs = parseCoworkOutputs(json.optJSONArray("outputs")),
            // Delta setMeta yolunda da uc-durumu koru: meta'da "goal" hic yoksa dokunma.
            goalPresent = json.has("goal"),
            goal = CodexGoal.fromJson(json.optJSONObject("goal")),
            // Görev panosu delta kipinde de taşınır (köprüde META_KEYS'e eklendi).
            // Uzun koşuda tam snapshot neredeyse hiç gelmiyor; burayı atlamak
            // panoyu ilk kareden sonra donmuş bırakırdı.
            todos = parseTodoArray(json.optJSONArray("todos")),
            contextPctPresent = json.has("contextPct"),
            contextPct = parseContextPct(json),
            // Delta setMeta yolu: köprü "reverted"ı META_KEYS'te taşıyor. Burayı
            // atlamak, uzun koşuda (tam snapshot neredeyse hiç gelmiyor) şeridin
            // hiç doğmamasına ya da yeni tur sonrası asılı kalmasına yol açardı.
            revertedPresent = json.has("reverted"),
            reverted = parseRevertState(json),
            // Alt-ajan kartları: aynı gerekçe. Kart yığınının bütün değeri UZUN
            // koşuda ve orası tam da tam-snapshot'ın gelmediği yer.
            subagents = parseSubagentArray(json.optJSONArray("subagents")),
            // Paylaşım linki de META_KEYS'te: uzun koşuda tam snapshot gelmediği
            // için burayı atlamak menüdeki satırı ilk kareye çivilerdi.
            share = if (json.has("share")) json.optString("share") else null,
        )
    }

    private fun parsePlanArray(arr: org.json.JSONArray?): List<PlanItem>? {
        if (arr == null) return null
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(PlanItem(
                    text = item.optString("text"),
                    status = item.optString("status", "pending"),
                    itemId = item.optString("itemId", ""),
                    turnId = item.optString("turnId", ""),
                ))
            }
        }
    }

    private fun parseSnapshotBackendModels(arr: org.json.JSONArray?): List<BackendModel>? {
        if (arr == null) return null
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(BackendModel(
                        item.optString("label"),
                        item.optString("id"),
                        item.optJSONArray("variants")?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
                        } ?: emptyList(),
                        item.optString("defaultVariant"),
                        item.optString("detail"),
                    ))
            }
        }
    }

    private fun parseSnapshotPermissionModes(arr: org.json.JSONArray?): List<PermissionMode>? {
        if (arr == null) return null
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                if (item != null) {
                    add(PermissionMode(
                        item.optString("id"),
                        item.optString("name", item.optString("id")),
                        item.optString("description", ""),
                    ))
                } else {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { add(PermissionMode(it, it)) }
                }
            }
        }
    }

    private fun parseSnapshotSlashCommands(arr: org.json.JSONArray?): List<SlashCommand>? {
        if (arr == null) return null
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val hintObj = item.optJSONObject("input")
                add(SlashCommand(
                    name = item.optString("name", ""),
                    desc = item.optString("description", ""),
                    hint = hintObj?.optString("hint", "") ?: "",
                ))
            }
        }
    }

    // Cowork teslimat dosyalarını parse et. outputs alanı yoksa (cowork değilse) null döner;
    // boş array ise boş liste (turda teslimat yok). { name, path, size, mtime }.
    private fun parseCoworkOutputs(arr: org.json.JSONArray?): List<CoworkOutput>? {
        if (arr == null) return null
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(CoworkOutput(
                    name = item.optString("name", ""),
                    path = item.optString("path", ""),
                    size = item.optLong("size", 0),
                    mtime = item.optDouble("mtime", 0.0),
                ))
            }
        }
    }
}
