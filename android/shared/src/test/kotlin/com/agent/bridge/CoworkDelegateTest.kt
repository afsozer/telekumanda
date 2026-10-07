package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoworkDelegateTest {
    private data class Harness(
        val client: FakeBridgeClient,
        var state: RemoteUiState,
        val emitted: MutableList<String>,
        var activeDeleted: Boolean = false,
        var promptSent: Boolean = false,
    )

    private fun delegate(h: Harness): CoworkDelegate = CoworkDelegate(
        client = h.client,
        scope = CoroutineScope(Dispatchers.Unconfined),
        prefill = ConversationPrefiller(
            memory = ConversationMemory(),
            cache = object : ConversationCache {
                override suspend fun load(backend: String, sessionId: String): OfflineConversation? = null
                override suspend fun save(backend: String, sessionId: String, result: ConversationResult) {}
                override suspend fun delete(sessionIds: Set<String>) {}
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
            update = { _ -> },
        ),
        state = { h.state },
        update = { reducer -> h.state = reducer(h.state) },
        emit = { h.emitted.add(it) },
        thoughtDetails = MutableStateFlow(emptyMap()),
        openSocket = { _, _ -> },
        startCodexAppPolling = {},
        startOpencodeAppPolling = {},
        startOmpPolling = {},
        startClaudeAppPolling = {},
        loadCodexAppInfo = {},
        loadOpencodeAppInfo = {},
        loadOmpInfo = {},
        loadClaudeAppInfo = {},
        refreshConversation = {},
        updateInput = { value -> h.state = h.state.copy(input = value) },
        sendPrompt = { h.promptSent = true },
        downloadRepo = null,
        syncActiveTab = { _, _, _, _ -> },
        onActiveCoworkDeleted = { h.activeDeleted = true },
    )

    @Test
    fun selectedWorkspaceWinsForDrawerFileOperations() = runBlocking {
        val client = FakeBridgeClient()
        client.jsonResponseProvider = { req ->
            if (req.path == "/cowork/import") {
                JSONObject().put("ok", true).put("copied", JSONArray()).put("errors", JSONArray())
            } else JSONObject().put("ok", true)
        }
        val h = Harness(
            client,
            RemoteUiState(
                backend = "cowork",
                cowork = CoworkUiState(activeProjectPath = "C:/Cowork/project-a", selectedWorkspace = "C:/Cowork/project-b"),
            ),
            mutableListOf(),
        )

        delegate(h).importPcIntoCoworkWorkspace(listOf("C:/source.docx")).join()

        val request = client.recordedRequests.single { it.path == "/cowork/import" }
        assertEquals("C:/Cowork/project-b", request.body?.optString("projectPath"))
    }

    @Test
    fun singleWorkspaceLoadFillsBlankCwdFromProjectPath() = runBlocking {
        // Boş cwd kaydı resume sırasında yanlış klasörü hedefliyordu; kayıt
        // çalışma alanının projectPath'iyle tamamlanmalı.
        val client = FakeBridgeClient()
        client.jsonResponseProvider = { req ->
            if (req.path.startsWith("/cowork/sessions")) {
                JSONObject().put("ok", true).put(
                    "sessions",
                    JSONArray()
                        .put(JSONObject().put("sessionId", "s-bos").put("provider", "claude-app").put("cwd", ""))
                        .put(JSONObject().put("sessionId", "s-dolu").put("provider", "claude-app").put("cwd", "C:/Cowork/baska")),
                )
            } else JSONObject().put("ok", true)
        }
        val h = Harness(client, RemoteUiState(backend = "cowork"), mutableListOf())

        delegate(h).loadCoworkSessions("C:/Cowork/project-a").join()

        val sessions = h.state.cowork.sessions.associateBy { it.sessionId }
        assertEquals("C:/Cowork/project-a", sessions.getValue("s-bos").cwd)
        // Dolu olan EZİLMEZ.
        assertEquals("C:/Cowork/baska", sessions.getValue("s-dolu").cwd)
    }

    @Test
    fun blankWorkspaceStillClearsSessionList() = runBlocking {
        val client = FakeBridgeClient()
        val h = Harness(
            client,
            RemoteUiState(
                backend = "cowork",
                cowork = CoworkUiState(sessions = listOf(CoworkSessionRecord(sessionId = "eski", provider = "claude-app"))),
            ),
            mutableListOf(),
        )

        delegate(h).loadCoworkSessions("").join()

        assertTrue(h.state.cowork.sessions.isEmpty())
        assertTrue(client.recordedRequests.none { it.path.startsWith("/cowork/sessions") })
    }

    @Test
    fun failedProviderSwitchKeepsPreviousProviderState() = runBlocking {
        val client = FakeBridgeClient()
        client.jsonResponseProvider = { req ->
            if (req.path == "/cowork/session/switch") JSONObject().put("ok", false).put("error", "offline")
            else JSONObject().put("ok", true)
        }
        val h = Harness(
            client,
            RemoteUiState(
                backend = "cowork",
                cowork = CoworkUiState(provider = "claude-app", activeProjectPath = "C:/Cowork/project-a"),
                claudeAppSessionId = "claude-1",
            ),
            mutableListOf(),
        )

        delegate(h).switchCoworkProvider("codex-app").join()

        assertEquals("claude-app", h.state.coworkProvider)
        assertTrue(h.emitted.any { it.contains("offline") })
    }

    @Test
    fun modelChangeTargetsActiveCoworkSessionInsteadOfLatestSession() = runBlocking {
        val client = FakeBridgeClient()
        client.jsonResponseProvider = { req ->
            if (req.path == "/cowork/session/switch") {
                JSONObject()
                    .put("ok", true)
                    .put("provider", "claude-app")
                    .put("apiBackend", "claude-app")
                    .put("sessionId", "rewound-fable-session")
                    .put("model", "claude-opus-5")
                    .put("outputs", JSONArray())
            } else {
                JSONObject().put("ok", true)
            }
        }
        val h = Harness(
            client,
            RemoteUiState(
                backend = "cowork",
                cowork = CoworkUiState(
                    provider = "claude-app",
                    activeProjectPath = "C:/Cowork/ornek-proje",
                ),
                claude = ClaudeUiState(
                    sessionId = "rewound-fable-session",
                    model = "claude-fable-5",
                ),
            ),
            mutableListOf(),
        )

        delegate(h).setCoworkModel("claude-opus-5").join()

        val request = client.recordedRequests.single { it.path == "/cowork/session/switch" }
        assertEquals("rewound-fable-session", request.body?.optString("sessionId"))
        assertEquals("claude-opus-5", request.body?.optString("model"))
        assertEquals("rewound-fable-session", h.state.claudeAppSessionId)
        assertEquals("claude-opus-5", h.state.claudeAppModel)
    }

    @Test
    fun deletingActiveSessionTriggersUiCleanup() = runBlocking {
        val client = FakeBridgeClient()
        client.jsonResponseProvider = { req -> when {
            req.path == "/cowork/delete-session" -> JSONObject().put("ok", true)
            req.path.startsWith("/cowork/sessions") -> JSONObject().put("ok", true).put("sessions", JSONArray())
            else -> JSONObject().put("ok", true)
        } }
        val h = Harness(
            client,
            RemoteUiState(
                backend = "cowork",
                cowork = CoworkUiState(provider = "claude-app", activeProjectPath = "C:/Cowork/project-a", selectedWorkspace = "C:/Cowork/project-a"),
                claudeAppSessionId = "claude-1",
            ),
            mutableListOf(),
        )

        delegate(h).deleteCoworkSession("C:/Cowork/project-a", "claude-1").join()

        assertTrue(h.activeDeleted)
        assertFalse(h.state.coworkSessionsLoading)
    }

    @Test
    fun handoffWritesNoteSwitchesProviderAndStartsTakeoverPrompt() = runBlocking {
        val client = FakeBridgeClient()
        client.jsonResponseProvider = { req -> when (req.path) {
            "/cowork/handoff" -> JSONObject()
                .put("ok", true)
                .put("path", "C:/Cowork/project-a/.cowork/handoff.md")
                .put("fromProvider", "claude-app")
                .put("toProvider", "codex-app")
                .put("messageCount", 4)
            "/cowork/session/switch" -> JSONObject()
                .put("ok", true)
                .put("provider", "codex-app")
                .put("apiBackend", "codex-app")
                .put("sessionId", "codex-1")
                .put("model", "gpt-test")
                .put("outputs", JSONArray())
            else -> JSONObject().put("ok", true)
        } }
        val h = Harness(
            client,
            RemoteUiState(
                backend = "cowork",
                cowork = CoworkUiState(provider = "claude-app", activeProjectPath = "C:/Cowork/project-a"),
                claudeAppSessionId = "claude-1",
                codexAppModel = "gpt-test",
            ),
            mutableListOf(),
        )

        delegate(h).handoffCoworkTo("codex-app").join()

        assertEquals("codex-app", h.state.coworkProvider)
        assertEquals("codex-1", h.state.codexAppSessionId)
        assertTrue(h.state.input.contains(".cowork/handoff.md"))
        assertTrue(h.promptSent)
        assertEquals(listOf("/cowork/handoff", "/cowork/session/switch"), client.recordedRequests.map { it.path })
    }
}
