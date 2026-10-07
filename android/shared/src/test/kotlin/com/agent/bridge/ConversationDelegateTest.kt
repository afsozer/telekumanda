package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDelegateTest {
    @Test
    fun coworkClaudeRewindReplacesTabSessionIdentity() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                when {
                    request.path == "/claude-app/rewind" -> JSONObject()
                        .put("ok", true)
                        .put("sessionId", "claude-rewound")
                    request.path.startsWith("/claude-app/conversation") -> JSONObject()
                        .put("messages", JSONArray())
                    else -> JSONObject().put("ok", true)
                }
            }
        }
        var state = RemoteUiState(
            backend = "cowork",
            cowork = CoworkUiState(provider = "claude-app"),
            claude = ClaudeUiState(sessionId = "claude-old"),
            messagesList = listOf(
                ChatMessage("user", "ilk soru"),
                ChatMessage("assistant", "ilk yanıt"),
                ChatMessage("user", "son soru"),
                ChatMessage("assistant", "son yanıt"),
            ),
        )
        var replaced: List<String> = emptyList()
        val delegate = ConversationDelegate(
            client = client,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { state },
            update = { reducer -> state = reducer(state) },
            emit = {},
            reportError = { _, error -> throw error },
            cache = object : ConversationCache {
                override suspend fun load(backend: String, sessionId: String): OfflineConversation? = null
                override suspend fun save(backend: String, sessionId: String, result: ConversationResult) = Unit
                override suspend fun delete(sessionIds: Set<String>) = Unit
            },
            startCowork = {},
            handleClaudeCleared = {},
            refreshCoworkOutputs = {},
            releaseCoworkLease = {},
            openClaudeSocket = {},
            replaceTabSessionId = { backend, provider, oldSessionId, newSessionId ->
                replaced = listOf(backend, provider, oldSessionId, newSessionId)
            },
        )

        delegate.returnToMessage(2)

        assertEquals("claude-rewound", state.claudeAppSessionId)
        assertEquals(
            listOf("cowork", "claude-app", "claude-old", "claude-rewound"),
            replaced,
        )
    }

    // Canlı vaka 06.08.2026: köprü "geri dönülecek mesaj bulunamadı (0 < 2)"
    // döndürdü (transcript boştu) ve uygulama HİÇBİR ŞEY yapmadı — kullanıcı tuşa
    // basıyor, mesaj olduğu yerde kalıyordu. Artık en azından görünüm geri sarılır.
    @Test
    fun kopruGeriSarmayiReddedinceGorunumYineDeGeriSarilir() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                when {
                    request.path == "/claude-app/rewind" -> JSONObject()
                        .put("ok", false)
                        .put("error", "geri dönülecek mesaj bulunamadı (0 < 2)")
                    else -> JSONObject().put("ok", true)
                }
            }
        }
        var state = RemoteUiState(
            backend = "claude-app",
            claude = ClaudeUiState(sessionId = "bozuk-oturum"),
            messagesList = listOf(
                ChatMessage("user", "ilk soru"),
                ChatMessage("assistant", "ilk yanıt"),
                ChatMessage("user", "son soru"),
            ),
        )
        val duyurular = mutableListOf<String>()
        val delegate = ConversationDelegate(
            client = client,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { state },
            update = { reducer -> state = reducer(state) },
            emit = { duyurular += it },
            reportError = { _, error -> throw error },
            cache = object : ConversationCache {
                override suspend fun load(backend: String, sessionId: String): OfflineConversation? = null
                override suspend fun save(backend: String, sessionId: String, result: ConversationResult) = Unit
                override suspend fun delete(sessionIds: Set<String>) = Unit
            },
            startCowork = {},
            handleClaudeCleared = {},
            refreshCoworkOutputs = {},
            releaseCoworkLease = {},
            openClaudeSocket = {},
            replaceTabSessionId = { _, _, _, _ -> },
        )

        delegate.returnToMessage(2)

        // Görünüm geri sarıldı ve metin yazma kutusuna döndü.
        assertEquals(2, state.truncateAfterIndex)
        assertEquals("son soru", state.input)
        // Kullanıcı NEDEN tam geri sarılmadığını öğrenir; sessiz yarım iş yok.
        assertEquals(1, duyurular.size)
        assertEquals(true, duyurular[0].contains("geri dönülecek mesaj bulunamadı"))
    }

    // Canlı vaka 07.08.2026: codex oturumunda "/compact" yazınca hiçbir şey
    // sıkışmıyordu — metin düz prompt olarak gidiyor, app-server slash komutunu
    // yorumlamadığı için model onu sıradan bir mesaj sanıyordu. Sıkıştırma ucu
    // köprüde ÇALIŞIR durumdaydı, sadece kimse çağırmıyordu.
    @Test
    fun codexOturumundaCompactPromptDegilSikistirmaUcunaGider() {
        val client = FakeBridgeClient()
        var state = RemoteUiState(
            backend = "codex-app",
            codex = CodexUiState(sessionId = "codex-1"),
            input = "/compact",
        )
        val delegate = ConversationDelegate(
            client = client,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { state },
            update = { reducer -> state = reducer(state) },
            emit = {},
            reportError = { _, error -> throw error },
            cache = object : ConversationCache {
                override suspend fun load(backend: String, sessionId: String): OfflineConversation? = null
                override suspend fun save(backend: String, sessionId: String, result: ConversationResult) = Unit
                override suspend fun delete(sessionIds: Set<String>) = Unit
            },
            startCowork = {},
            handleClaudeCleared = {},
            refreshCoworkOutputs = {},
            releaseCoworkLease = {},
            openClaudeSocket = {},
            replaceTabSessionId = { _, _, _, _ -> },
        )

        delegate.send()

        val yollar = client.recordedRequests.map { it.path }
        assertEquals(true, yollar.contains("/codex-app/compact"))
        // Asıl hata buydu: komut metin olarak modele gidiyordu.
        assertEquals(false, yollar.contains("/codex-app/prompt"))
        // Kutu temizlenir, komut yazılı kalmaz.
        assertEquals("", state.input)
    }

    @Test
    fun opencodeMesajiSeciliIzinModunuVeVaryantiKorur() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                when {
                    request.path == "/opencode2-app/prompt" -> JSONObject().put("ok", true)
                    request.path.startsWith("/opencode2-app/conversation") -> JSONObject().put("messages", JSONArray())
                    else -> JSONObject().put("ok", true)
                }
            }
        }
        var state = RemoteUiState(
            backend = "opencode2-app",
            opencode = OpencodeUiState(
                sessionId = "oc-ask",
                model = "runpod/runpod",
                permissionMode = "ask",
                variant = "high",
            ),
            input = "dosyayi oku",
        )
        val delegate = ConversationDelegate(
            client = client,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { state },
            update = { reducer -> state = reducer(state) },
            emit = {},
            reportError = { _, error -> throw error },
            cache = object : ConversationCache {
                override suspend fun load(backend: String, sessionId: String): OfflineConversation? = null
                override suspend fun save(backend: String, sessionId: String, result: ConversationResult) = Unit
                override suspend fun delete(sessionIds: Set<String>) = Unit
            },
            startCowork = {},
            handleClaudeCleared = {},
            refreshCoworkOutputs = {},
            releaseCoworkLease = {},
            openClaudeSocket = {},
            replaceTabSessionId = { _, _, _, _ -> },
        )

        delegate.send()

        val request = client.recordedRequests.single { it.path == "/opencode2-app/prompt" }
        assertEquals("ask", request.body?.optString("permissionMode"))
        assertEquals("high", request.body?.optString("variant"))
    }

    // GÖREV PANOSU — poll (HTTP) yolu. Soket yolu SessionStateReducer testinde;
    // ikisi ayrı kod olduğu için ikisi de sınanıyor: bu depoda tekrarlayan hata
    // "bir taşımaya yazıldı, diğeri unutuldu".
    @Test
    fun gorevPanosuPollYolundanDaGelir() {
        var yuk = JSONObject()
            .put("messages", JSONArray())
            .put("todos", JSONArray().apply {
                put(JSONObject().put("content", "Köprüyü oku").put("status", "completed").put("priority", "high"))
                put(JSONObject().put("content", "Panoyu ekle").put("status", "in_progress").put("priority", "high"))
            })
            .put("contextPct", 42)
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                if (request.path.startsWith("/opencode2-app/conversation")) yuk else JSONObject().put("ok", true)
            }
        }
        var state = RemoteUiState(
            backend = "opencode2-app",
            opencode = OpencodeUiState(sessionId = "oc-pano"),
        )
        val delegate = ConversationDelegate(
            client = client,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { state },
            update = { reducer -> state = reducer(state) },
            emit = {},
            reportError = { _, error -> throw error },
            cache = object : ConversationCache {
                override suspend fun load(backend: String, sessionId: String): OfflineConversation? = null
                override suspend fun save(backend: String, sessionId: String, result: ConversationResult) = Unit
                override suspend fun delete(sessionIds: Set<String>) = Unit
            },
            startCowork = {},
            handleClaudeCleared = {},
            refreshCoworkOutputs = {},
            releaseCoworkLease = {},
            openClaudeSocket = {},
            replaceTabSessionId = { _, _, _, _ -> },
        )

        delegate.refresh(showErrors = false)
        assertEquals(listOf("Köprüyü oku", "Panoyu ekle"), state.opencode.todos.map { it.content })
        assertEquals("in_progress", state.opencode.todos[1].status)
        assertEquals(42, state.opencode.contextPct)

        // Eski köprü: alanlar hiç yok -> panoya DOKUNMA (boşaltma).
        yuk = JSONObject().put("messages", JSONArray())
        delegate.refresh(showErrors = false)
        assertEquals(2, state.opencode.todos.size)
        assertEquals(42, state.opencode.contextPct)

        // Alan geldi ama ölçülemiyor (pencere bilinmiyor) -> sayaç temizlenir,
        // liste durur.
        yuk = JSONObject().put("messages", JSONArray()).put("contextPct", JSONObject.NULL)
        delegate.refresh(showErrors = false)
        assertEquals(null, state.opencode.contextPct)
        assertEquals(2, state.opencode.todos.size)
    }

    // ÇÖZÜMLENEN AJAN — poll (HTTP) yolu. Soket kapalıyken göstergenin tek
    // kaynağı burası; okuma dalı unutulsaydı çip poll kipinde hep "otomatik" derdi.
    @Test
    fun cozumlenenAjanPollYolundanDaGelir() {
        var yuk = JSONObject().put("messages", JSONArray()).put("resolvedAgent", "yerel")
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                if (request.path.startsWith("/opencode2-app/conversation")) yuk else JSONObject().put("ok", true)
            }
        }
        var state = RemoteUiState(
            backend = "opencode2-app",
            opencode = OpencodeUiState(sessionId = "oc-ajan"),
        )
        val delegate = ConversationDelegate(
            client = client,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { state },
            update = { reducer -> state = reducer(state) },
            emit = {},
            reportError = { _, error -> throw error },
            cache = object : ConversationCache {
                override suspend fun load(backend: String, sessionId: String): OfflineConversation? = null
                override suspend fun save(backend: String, sessionId: String, result: ConversationResult) = Unit
                override suspend fun delete(sessionIds: Set<String>) = Unit
            },
            startCowork = {},
            handleClaudeCleared = {},
            refreshCoworkOutputs = {},
            releaseCoworkLease = {},
            openClaudeSocket = {},
            replaceTabSessionId = { _, _, _, _ -> },
        )

        delegate.refresh(showErrors = false)
        assertEquals("yerel", state.opencode.resolvedAgent)

        // Eski köprü: alan hiç yok -> son bilinen değere DOKUNMA.
        yuk = JSONObject().put("messages", JSONArray())
        delegate.refresh(showErrors = false)
        assertEquals("yerel", state.opencode.resolvedAgent)

        // Boş değer gerçek bilgi: köprü artık bilmiyor, çip "otomatik"e döner.
        yuk = JSONObject().put("messages", JSONArray()).put("resolvedAgent", "")
        delegate.refresh(showErrors = false)
        assertEquals("", state.opencode.resolvedAgent)
    }

    // ALT AJAN KARTLARI — poll (HTTP) yolu. Soket yolu SessionStateReducer
    // testinde; panoyla aynı gerekçe (iki ayrı kod, ikisi de sınanır).
    @Test
    fun altAjanKartlariPollYolundanDaGelir() {
        var yuk = JSONObject()
            .put("messages", JSONArray())
            .put("subagents", JSONArray().apply {
                put(
                    JSONObject().put("id", "ses_a").put("title", "dosya sayimi")
                        .put("status", "running").put("lastText", "Dizini okuyorum").put("turns", 1)
                        .put("agent", "general"),
                )
                // Kimliksiz kayıt ELENİR: tıklanınca transkript isteği kurulamaz.
                put(JSONObject().put("title", "kimliksiz").put("status", "idle"))
            })
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                if (request.path.startsWith("/opencode2-app/conversation")) yuk else JSONObject().put("ok", true)
            }
        }
        var state = RemoteUiState(
            backend = "opencode2-app",
            opencode = OpencodeUiState(sessionId = "oc-alt"),
        )
        val delegate = ConversationDelegate(
            client = client,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { state },
            update = { reducer -> state = reducer(state) },
            emit = {},
            reportError = { _, error -> throw error },
            cache = object : ConversationCache {
                override suspend fun load(backend: String, sessionId: String): OfflineConversation? = null
                override suspend fun save(backend: String, sessionId: String, result: ConversationResult) = Unit
                override suspend fun delete(sessionIds: Set<String>) = Unit
            },
            startCowork = {},
            handleClaudeCleared = {},
            refreshCoworkOutputs = {},
            releaseCoworkLease = {},
            openClaudeSocket = {},
            replaceTabSessionId = { _, _, _, _ -> },
        )

        delegate.refresh(showErrors = false)
        assertEquals(1, state.opencode.subagents.size)
        assertEquals("ses_a", state.opencode.subagents[0].id)
        assertEquals("running", state.opencode.subagents[0].status)
        assertEquals("Dizini okuyorum", state.opencode.subagents[0].lastText)

        // Eski köprü: alan hiç yok -> yığına DOKUNMA (boşaltma).
        yuk = JSONObject().put("messages", JSONArray())
        delegate.refresh(showErrors = false)
        assertEquals(1, state.opencode.subagents.size)

        // Boş dizi gerçek bilgi: yığın temizlenir.
        yuk = JSONObject().put("messages", JSONArray()).put("subagents", JSONArray())
        delegate.refresh(showErrors = false)
        assertTrue(state.opencode.subagents.isEmpty())
    }

    // GERİ SARMA ŞERİDİ — poll (HTTP) yolu. Soket yolu SessionStateReducer
    // testinde; yukarıdaki panoyla aynı gerekçe (iki ayrı kod, ikisi de sınanır).
    @Test
    fun geriSarmaSeridiPollYolundanDaGelir() {
        var yuk = JSONObject()
            .put("messages", JSONArray())
            .put("reverted", JSONObject().put("messageID", "msg_2").put("filesReverted", true).put("files", 3))
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                if (request.path.startsWith("/opencode2-app/conversation")) yuk else JSONObject().put("ok", true)
            }
        }
        var state = RemoteUiState(
            backend = "opencode2-app",
            opencode = OpencodeUiState(sessionId = "oc-revert"),
        )
        val delegate = ConversationDelegate(
            client = client,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { state },
            update = { reducer -> state = reducer(state) },
            emit = {},
            reportError = { _, error -> throw error },
            cache = object : ConversationCache {
                override suspend fun load(backend: String, sessionId: String): OfflineConversation? = null
                override suspend fun save(backend: String, sessionId: String, result: ConversationResult) = Unit
                override suspend fun delete(sessionIds: Set<String>) = Unit
            },
            startCowork = {},
            handleClaudeCleared = {},
            refreshCoworkOutputs = {},
            releaseCoworkLease = {},
            openClaudeSocket = {},
            replaceTabSessionId = { _, _, _, _ -> },
        )

        delegate.refresh(showErrors = false)
        assertEquals("msg_2", state.opencode.reverted?.messageID)
        assertEquals(true, state.opencode.reverted?.filesReverted)
        assertEquals(3, state.opencode.reverted?.files)

        // Eski köprü: alan hiç yok -> şeride DOKUNMA.
        yuk = JSONObject().put("messages", JSONArray())
        delegate.refresh(showErrors = false)
        assertEquals("msg_2", state.opencode.reverted?.messageID)

        // Alan geldi ve null: yeni tur geri almayı kalıcı kıldı -> şerit iner.
        yuk = JSONObject().put("messages", JSONArray()).put("reverted", JSONObject.NULL)
        delegate.refresh(showErrors = false)
        assertEquals(null, state.opencode.reverted)
    }
}
