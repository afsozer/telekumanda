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

    @Test
    fun testAgyDelegateEnterAgyMode() = runBlocking {
        val fakeClient = FakeBridgeClient()
        var uiState = RemoteUiState()
        val thoughtDetails = MutableStateFlow<Map<Int, String>>(emptyMap())
        var loadWorkerDirsCalledWith = ""
        val emittedMessages = mutableListOf<String>()
        
        fakeClient.jsonResponseProvider = { req ->
            if (req.path.startsWith("/agy/models")) {
                val modelObj = JSONObject().put("label", "Gemini 3.5").put("id", "gemini-3.5")
                JSONObject().put("models", JSONArray().put(modelObj))
            } else if (req.path.contains("/agy/disk-sessions")) {
                val sessionObj = JSONObject()
                    .put("id", "session-1")
                    .put("cwd", "/workspace")
                    .put("title", "Agy Session")
                    .put("lastText", "last prompt")
                    .put("turns", 2)
                    .put("mtime", 123456789L)
                    .put("source", "cli")
                JSONObject().put("sessions", JSONArray().put(sessionObj))
            } else if (req.path.contains("/agy/adopt")) {
                JSONObject().put("ok", true).put("sessionId", "session-1")
            } else {
                JSONObject().put("ok", true)
            }
        }

        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val delegate = AgyDelegate(
            client = fakeClient,
            scope = testScope,
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = { msg -> emittedMessages.add(msg); println("AGY EMIT: $msg") },
            thoughtDetails = thoughtDetails,
            openSocket = { /* no-op */ },
            closeSocket = { /* no-op */ },
            loadWorkerDirs = { path -> loadWorkerDirsCalledWith = path },
            refreshConversation = { /* no-op */ },
            refreshSession = { /* no-op */ },
            applyConversation = { /* no-op */ },
            refreshAll = { /* no-op */ },
            syncActiveTab = { _, _, _, _ -> }
        )

        val job = delegate.enterAgyMode()
        job.join()

        // Verify errors
        assertTrue("Errors emitted: $emittedMessages", emittedMessages.isEmpty())

        // Verify loadWorkerDirs was called
        assertEquals("", loadWorkerDirsCalledWith)

        // Verify API calls
        assertTrue(fakeClient.recordedRequests.any { it.method == "GET" && it.path.startsWith("/agy/models") })
        assertTrue(fakeClient.recordedRequests.any { it.method == "GET" && it.path.startsWith("/agy/disk-sessions") })

        // Verify state is populated
        assertEquals("agy", uiState.backend)
        assertEquals(1, uiState.agyModels.size)
        assertEquals("gemini-3.5", uiState.agyModels.first().id)
        assertEquals(1, uiState.agyDiskSessions.size)
        assertEquals("session-1", uiState.agyDiskSessions.first().id)
        assertFalse(uiState.agyDiskLoading)
    }

    @Test
    fun testAgyDelegateEnterAgyModeFailure() = runBlocking {
        val fakeClient = FakeBridgeClient()
        var uiState = RemoteUiState()
        val thoughtDetails = MutableStateFlow<Map<Int, String>>(emptyMap())
        var emittedMessage = ""
        
        // Mock error on models load
        fakeClient.jsonResponseProvider = {
            throw java.io.IOException("Connection refused")
        }

        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val delegate = AgyDelegate(
            client = fakeClient,
            scope = testScope,
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = { msg -> emittedMessage = msg },
            thoughtDetails = thoughtDetails,
            openSocket = { /* no-op */ },
            closeSocket = { /* no-op */ },
            loadWorkerDirs = { /* no-op */ },
            refreshConversation = { /* no-op */ },
            refreshSession = { /* no-op */ },
            applyConversation = { /* no-op */ },
            refreshAll = { /* no-op */ },
            syncActiveTab = { _, _, _, _ -> }
        )

        val job = delegate.enterAgyMode()
        job.join()

        // Verify loading state was cleared
        assertFalse(uiState.agyDiskLoading)
        // Verify error was reported
        assertTrue("Emitted message: $emittedMessage", emittedMessage.contains("Antigravity CLI modelleri yuklenemedi: Connection refused"))
    }

    // Ortak kurulum: agy delegesi + /agy/models, /agy/disk-sessions, /agy/sessions
    // ve /agy/adopt yanitlari. Iki regresyon testi de bunu kullanir.
    private class AgyKosum {
        val client = FakeBridgeClient()
        var uiState = RemoteUiState()
        val acilanSoketler = mutableListOf<String>()
        val senkronSekmeler = mutableListOf<String>()
        val mesajlar = mutableListOf<String>()
        // /agy/sessions'in dondurecegi CANLI oturum kimligi ("" ise liste bos).
        var canliOturumId = ""

        val delegate: AgyDelegate

        init {
            client.jsonResponseProvider = { req ->
                when {
                    req.path.startsWith("/agy/models") ->
                        JSONObject().put("models", JSONArray().put(
                            JSONObject().put("label", "Gemini 3.5").put("id", "gemini-3.5")))
                    req.path.startsWith("/agy/sessions") -> {
                        val arr = JSONArray()
                        if (canliOturumId.isNotBlank()) arr.put(
                            JSONObject().put("id", canliOturumId).put("cwd", "/workspace")
                                .put("model", "gemini-3.5").put("status", "idle"))
                        JSONObject().put("sessions", arr)
                    }
                    req.path.contains("/agy/disk-sessions") ->
                        JSONObject().put("sessions", JSONArray().put(
                            JSONObject().put("id", "disk-1").put("cwd", "/workspace")
                                .put("title", "Agy Session").put("lastText", "son istem")
                                .put("turns", 2).put("mtime", 123456789L).put("source", "cli")))
                    req.path.contains("/agy/adopt") ->
                        JSONObject().put("ok", true).put("sessionId", "adopted-1")
                    else -> JSONObject().put("ok", true)
                }
            }
            delegate = AgyDelegate(
                client = client,
                scope = CoroutineScope(Dispatchers.Unconfined),
                state = { uiState },
                update = { updater -> uiState = updater(uiState) },
                emit = { msg -> mesajlar.add(msg) },
                thoughtDetails = MutableStateFlow(emptyMap()),
                openSocket = { sid -> acilanSoketler.add(sid) },
                closeSocket = { },
                loadWorkerDirs = { },
                refreshConversation = { },
                refreshSession = { },
                applyConversation = { },
                refreshAll = { },
                syncActiveTab = { backend, _, sid, _ -> senkronSekmeler.add("$backend:$sid") },
            )
        }
    }

    @Test
    fun agyBackendSecimiOturumBAGLAMAZ() = runBlocking {
        // Regresyon (kullanici bildirdi 25.08.2026): "Yeni oturum" bolumunde
        // Antigravity cipine dokunur dokunmaz — daha Baslat'a basmadan — en son
        // agy oturumu aciliyordu. Iki kaynak vardi: (1) enterAgyMode'un
        // `lastAgySessionId`'yi otomatik devralmasi, (2) en yeni CLI disk
        // oturumuna sessizce yapisan resumeAgyDiskSession. Ikisi de kalkti.
        val k = AgyKosum()
        // Cikista yazilan hafiza dolu VE kopruce canli; eski kod buna baglanirdi.
        k.uiState = k.uiState.copy(lastAgySessionId = "eski-1")
        k.canliOturumId = "eski-1"

        k.delegate.enterAgyMode().join()

        assertTrue("Hata: ${k.mesajlar}", k.mesajlar.isEmpty())
        assertEquals("agy", k.uiState.backend)
        // OTURUM YOK: kurulum ekrani acik kalir.
        assertEquals("", k.uiState.agySessionId)
        assertTrue(k.uiState.agySetupPending)
        assertTrue("Soket acilmamali: ${k.acilanSoketler}", k.acilanSoketler.isEmpty())
        assertTrue("Sekme yazilmamali: ${k.senkronSekmeler}", k.senkronSekmeler.isEmpty())
        // Ne canli liste sorulur ne de adopt edilir.
        assertFalse(k.client.recordedRequests.any { it.path.startsWith("/agy/sessions") })
        assertFalse(k.client.recordedRequests.any { it.path.contains("/agy/adopt") })
        // Disk listesi yine de yuklenir — secici dolu gelsin diye.
        assertEquals(1, k.uiState.agyDiskSessions.size)
        assertFalse(k.uiState.agyDiskLoading)
    }

    @Test
    fun agyBilincliDevralmaHalaBaglar() = runBlocking {
        // Bilincli yol (sekmeye dokunma / bildirim): pendingBind hedefi yazilmis
        // olur ve enterAgyMode YALNIZ ona baglanir. Tek atimlik: tuketilince silinir.
        val k = AgyKosum()
        k.canliOturumId = "canli-1"
        k.uiState = k.uiState.copy(pendingBindBackend = "agy", pendingBindSessionId = "canli-1")

        k.delegate.enterAgyMode().join()

        assertEquals("canli-1", k.uiState.agySessionId)
        assertFalse(k.uiState.agySetupPending)
        assertEquals(listOf("canli-1"), k.acilanSoketler)
        assertEquals(listOf("agy:canli-1"), k.senkronSekmeler)
        // Hedef tuketildi: bir sonraki giris kurulum ekranini acmali.
        assertEquals("", k.uiState.pendingBindBackend)
        assertEquals("", k.uiState.pendingBindSessionId)
    }

    @Test
    fun agyDiskOturumunuDevralmakHalaCalisir() = runBlocking {
        // Cekmeceden / Operations kartindan gelen bilincli devralma yolu:
        // enterAgyMode'dan bagimsiz, adopt eder ve baglar.
        val k = AgyKosum()

        k.delegate.resumeAgyDiskSession(AgyDiskSession(
            id = "disk-1", cwd = "/workspace", title = "Agy Session",
            lastText = "son istem", turns = 2, mtime = 123456789L, source = "cli",
        )).join()

        assertTrue(k.client.recordedRequests.any { it.method == "POST" && it.path.contains("/agy/adopt") })
        assertEquals("adopted-1", k.uiState.agySessionId)
        assertFalse(k.uiState.agySetupPending)
        assertEquals(listOf("adopted-1"), k.acilanSoketler)
        assertEquals(listOf("agy:adopted-1"), k.senkronSekmeler)
    }
}
