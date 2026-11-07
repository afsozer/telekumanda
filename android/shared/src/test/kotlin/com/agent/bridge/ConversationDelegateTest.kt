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
}
