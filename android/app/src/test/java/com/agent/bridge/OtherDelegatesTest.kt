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

class OtherDelegatesTest {

    @Test
    fun testFileBrowserDelegateLoadBrowserDir() = runBlocking {
        val fakeClient = FakeBridgeClient()
        var uiState = RemoteUiState(
            backend = "claude-app"
        )
        val emittedMessages = mutableListOf<String>()
        
        fakeClient.jsonResponseProvider = { req ->
            if (req.path.startsWith("/dirs")) {
                val dirEntry = JSONObject()
                    .put("name", "src")
                    .put("path", "/workspace/src")
                    .put("type", "dir")
                    .put("size", 0L)
                JSONObject()
                    .put("ok", true)
                    .put("base", "/workspace")
                    .put("dirs", JSONArray().put(dirEntry))
            } else {
                JSONObject().put("ok", true)
            }
        }

        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val delegate = FileBrowserDelegate(
            client = fakeClient,
            scope = testScope,
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = { msg -> emittedMessages.add(msg); println("FILE_BROWSER EMIT: $msg") },
            openFile = { path -> /* no-op */ }
        )

        val job = delegate.loadBrowserDir("/workspace")
        job.join()

        // Print error if any
        assertTrue("Errors emitted: $emittedMessages", emittedMessages.isEmpty())

        // Verify request was captured
        assertEquals(1, fakeClient.recordedRequests.size)
        val req = fakeClient.recordedRequests.first()
        assertEquals("GET", req.method)
        assertTrue(req.path.contains("/dirs?root=%2Fworkspace&files=true"))

        // Verify state is updated
        assertFalse(uiState.fileBrowserLoading)
        assertEquals("/workspace", uiState.fileBrowserBase)
        assertEquals(1, uiState.fileBrowserEntries.size)
        assertEquals("src", uiState.fileBrowserEntries.first().name)
        assertEquals("/workspace/src", uiState.fileBrowserEntries.first().path)
        assertEquals("dir", uiState.fileBrowserEntries.first().type)
    }

    @Test
    fun testFileBrowserDelegateDeleteBrowserEntry() = runBlocking {
        val fakeClient = FakeBridgeClient()
        var uiState = RemoteUiState(
            backend = "claude-app",
            files = FilesUiState(browserBase = "/workspace"),
        )
        var emittedMessage = ""
        
        fakeClient.jsonResponseProvider = { req ->
            if (req.path == "/delete") {
                JSONObject().put("ok", true)
            } else if (req.path.startsWith("/dirs")) {
                JSONObject().put("ok", true).put("base", "/workspace").put("dirs", JSONArray())
            } else {
                JSONObject().put("ok", true)
            }
        }

        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val delegate = FileBrowserDelegate(
            client = fakeClient,
            scope = testScope,
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = { msg -> emittedMessage = msg },
            openFile = { path -> /* no-op */ }
        )

        val job = delegate.deleteBrowserEntry("/workspace/src/old.txt")
        job.join()

        // Verify delete request was captured
        assertTrue(fakeClient.recordedRequests.any { it.method == "POST" && it.path == "/delete" })
        assertEquals("Silindi", emittedMessage)
    }

}
