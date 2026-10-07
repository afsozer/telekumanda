package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDelegateTest {
    @Test
    fun testExplicitSettingsTargetWorksWithoutActiveBackend() = runBlocking {
        val fakeClient = FakeBridgeClient()
        var uiState = RemoteUiState(backend = null)
        fakeClient.jsonResponseProvider = { req ->
            if (req.path == "/codex-app/mcp/servers") {
                JSONObject().put("servers", JSONArray().put(
                    JSONObject()
                        .put("name", "project-tools")
                        .put("enabled", true)
                        .put("type", "command")
                        .put("command", "node")
                ))
            } else JSONObject().put("ok", true)
        }
        val delegate = McpDelegate(
            client = fakeClient,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
        )

        delegate.loadMcpServers("codex-app").join()

        assertEquals("/codex-app/mcp/servers", fakeClient.recordedRequests.single().path)
        assertEquals("project-tools", uiState.mcpServers.single().name)
    }

    @Test
    fun testMcpDelegateLoadMcpServers() = runBlocking {
        val fakeClient = FakeBridgeClient()
        var uiState = RemoteUiState(backend = "claude-app")
        
        fakeClient.jsonResponseProvider = { req ->
            if (req.path.startsWith("/claude-app/mcp/servers")) {
                val serverObj = JSONObject()
                    .put("name", "test-mcp-server")
                    .put("enabled", true)
                    .put("type", "command")
                    .put("command", "node")
                    .put("args", JSONArray().put("test.js"))
                JSONObject().put("servers", JSONArray().put(serverObj))
            } else {
                JSONObject()
            }
        }

        val emittedMessages = mutableListOf<String>()
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val delegate = McpDelegate(
            client = fakeClient,
            scope = testScope,
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = { msg -> emittedMessages.add(msg); println("EMITTED: $msg") }
        )

        // Run loadMcpServers
        val job = delegate.loadMcpServers()
        job.join() // wait for completion

        // Verify no errors were emitted
        assertTrue("Expected no errors but got: $emittedMessages", emittedMessages.none { it.contains("başarısız") || it.contains("yüklenemedi") || it.contains("hata") })

        // Verify captured request
        assertEquals(1, fakeClient.recordedRequests.size)
        val req = fakeClient.recordedRequests.first()
        assertEquals("GET", req.method)
        assertEquals("/claude-app/mcp/servers", req.path)

        // Verify updated state
        assertEquals(1, uiState.mcpServers.size)
        assertEquals("test-mcp-server", uiState.mcpServers.first().name)
        assertTrue(uiState.mcpServers.first().enabled)
        assertEquals("command", uiState.mcpServers.first().type)
        assertEquals("node", uiState.mcpServers.first().command)
        assertEquals(listOf("test.js"), uiState.mcpServers.first().args)
    }

    @Test
    fun testMcpDelegateSaveMcpServer() = runBlocking {
        val fakeClient = FakeBridgeClient()
        var uiState = RemoteUiState(backend = "claude-app")
        var emittedMessage = ""

        fakeClient.jsonResponseProvider = { req ->
            if (req.path.startsWith("/claude-app/mcp/servers")) {
                val serverObj = JSONObject()
                    .put("name", "test-mcp-server")
                    .put("enabled", true)
                    .put("type", "command")
                    .put("command", "node")
                    .put("args", JSONArray().put("test.js"))
                JSONObject().put("servers", JSONArray().put(serverObj))
            } else if (req.path.startsWith("/claude-app/mcp/server")) {
                JSONObject().put("ok", true)
            } else {
                JSONObject().put("ok", true)
            }
        }

        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val delegate = McpDelegate(
            client = fakeClient,
            scope = testScope,
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = { msg -> emittedMessage = msg }
        )

        val job = delegate.saveMcpServer("test-mcp-server", "command", "", "node")
        job.join()

        // It should call save, and then refresh/load servers
        assertTrue(fakeClient.recordedRequests.any { it.method == "POST" && it.path.contains("/claude-app/mcp/server") })
        assertTrue(fakeClient.recordedRequests.any { it.method == "GET" && it.path.contains("/claude-app/mcp/servers") })
        assertTrue(emittedMessage.contains("MCP eklendi: test-mcp-server"))
    }
}
