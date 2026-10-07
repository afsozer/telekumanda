package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TabsDelegateTest {

    private class FakeKeyValueStore : KeyValueStore {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
    }

    @Test
    fun singleSessionModeNeverKeepsMoreThanOneVisibleTab() = runBlocking {
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState(backend = "claude-app")
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = FakeBridgeClient(),
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {},
            singleSession = true,
        )

        delegate.syncActiveTab("claude-app", "", "claude-1", "Claude")
        uiState = uiState.copy(backend = "codex-app")
        delegate.syncActiveTab("codex-app", "", "codex-1", "Codex")
        assertEquals(1, uiState.visibleTabs.size)
        assertEquals("codex-1", uiState.visibleTabs.single().sessionId)

        delegate.newTab()
        assertEquals(1, uiState.visibleTabs.size)
        assertTrue(uiState.visibleTabs.single().sessionId.isEmpty())
    }

    @Test
    fun singleSessionModeIgnoresLateSyncFromPreviousBackend() = runBlocking {
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState(backend = "agy")
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = FakeBridgeClient(),
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {},
            singleSession = true,
        )

        delegate.syncActiveTab("agy", "", "agy-1", "Gemini")
        delegate.syncActiveTab("claude-app", "", "claude-late", "Claude")

        assertEquals(1, uiState.visibleTabs.size)
        assertEquals("agy", uiState.visibleTabs.single().backend)
        assertEquals("agy-1", uiState.visibleTabs.single().sessionId)
        val persisted = JSONObject(fakePrefs.getString("app_tabs")!!)
            .getJSONArray("tabs")
            .getJSONObject(0)
        assertEquals("agy", persisted.getString("backend"))
        assertEquals("agy-1", persisted.getString("sessionId"))
    }

    @Test
    fun testJsonPersistenceAndSync() = runBlocking {
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val fakeClient = FakeBridgeClient()
        val emitted = mutableListOf<String>()

        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = { msg -> emitted.add(msg) },
            client = fakeClient,
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {}
        )

        // 1. Sync first tab
        delegate.syncActiveTab("codex-app", "", "session-123", "agtest · sol")
        assertEquals(1, uiState.openTabs.size)
        val tab1 = uiState.openTabs.first()
        assertEquals("codex-app", tab1.backend)
        assertEquals("session-123", tab1.sessionId)
        assertEquals("agtest · sol", tab1.title)
        assertEquals(tab1.id, uiState.activeTabId)

        // Verify it was persisted to SharedPreferences
        val rawJson = fakePrefs.getString("app_tabs")
        assertTrue(rawJson != null)
        val obj = JSONObject(rawJson!!)
        assertEquals(uiState.activeTabId, obj.getString("active"))

        // 2. Restore in a new delegate
        var newUiState = RemoteUiState()
        val newDelegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { newUiState },
            update = { updater -> newUiState = updater(newUiState) },
            emit = {},
            client = fakeClient,
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {}
        )
        newDelegate.restoreTabs()
        assertEquals(1, newUiState.openTabs.size)
        assertEquals(uiState.activeTabId, newUiState.activeTabId)
    }

    @Test
    fun restoreAssignsLegacyTabToActiveBridgeProfile() {
        val fakePrefs = FakeKeyValueStore()
        val legacyJson = JSONObject()
            .put("active", "legacy-tab")
            .put(
                "tabs",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("id", "legacy-tab")
                        .put("backend", "codex-app")
                        .put("sessionId", "legacy-session")
                        .put("title", "Eski sekme"),
                ),
            )
        fakePrefs.putString("app_tabs", legacyJson.toString())
        var uiState = RemoteUiState(activeBridgeProfileId = "bridge-current")
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = FakeBridgeClient(),
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {},
        )

        delegate.restoreTabs()

        assertEquals("bridge-current", uiState.openTabs.single().bridgeProfileId)
        assertEquals("legacy-tab", uiState.activeTabId)
        val persistedTab = JSONObject(fakePrefs.getString("app_tabs")!!)
            .getJSONArray("tabs")
            .getJSONObject(0)
        assertEquals("bridge-current", persistedTab.getString("bridgeProfileId"))
    }

    @Test
    fun bridgeSwitchShowsOnlyItsTabsWithoutDeletingHiddenTabs() = runBlocking {
        val fakePrefs = FakeKeyValueStore()
        val fakeClient = FakeBridgeClient()
        val bridgeATab = AppTab(
            id = "tab-a",
            backend = "claude-app",
            sessionId = "session-a",
            bridgeProfileId = "bridge-a",
        )
        val bridgeBTab = AppTab(
            id = "tab-b",
            backend = "codex-app",
            sessionId = "session-b",
            bridgeProfileId = "bridge-b",
        )
        var uiState = RemoteUiState(
            activeBridgeProfileId = "bridge-a",
            openTabs = listOf(bridgeATab, bridgeBTab),
            activeTabId = bridgeATab.id,
        )
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = fakeClient,
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {},
        )

        uiState = uiState.copy(activeBridgeProfileId = "bridge-b")
        val activeTabId = delegate.reconcileActiveTabForBridge()
        delegate.refreshTabStatuses()

        assertEquals(listOf(bridgeBTab), uiState.visibleTabs)
        assertEquals(bridgeBTab.id, activeTabId)
        assertEquals(bridgeBTab.id, uiState.activeTabId)
        assertEquals(listOf(bridgeATab, bridgeBTab), uiState.openTabs)
        assertTrue(fakeClient.recordedRequests.any { it.path == "/codex-app/sessions" })
        assertFalse(fakeClient.recordedRequests.any { it.path == "/claude-app/sessions" })
    }

    @Test
    fun testCloseTabAndFallback() = runBlocking {
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val fakeClient = FakeBridgeClient()

        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = fakeClient,
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {}
        )

        // Start with 2 tabs
        delegate.syncActiveTab("codex-app", "", "sess-1", "tab1")
        val tab1Id = uiState.activeTabId
        
        // Add a second tab using newTab
        delegate.newTab()
        val tab2Id = uiState.activeTabId
        assertEquals(2, uiState.openTabs.size)

        // Close the active tab (tab2) -> tab1 should become active
        delegate.closeTab(tab2Id)
        assertEquals(1, uiState.openTabs.size)
        assertEquals(tab1Id, uiState.activeTabId)

        // Close the last remaining tab -> a new empty tab should be created automatically
        delegate.closeTab(tab1Id)
        assertEquals(1, uiState.openTabs.size)
        assertTrue(uiState.openTabs.first().sessionId.isEmpty())
    }

    @Test
    fun testActivateTab() = runBlocking {
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val fakeClient = FakeBridgeClient()

        var landingCalled = false
        var enterClaudeAppModeCalled = false
        var enterCodexAppModeCalled = false

        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = fakeClient,
            // Gerçek exit* davranışını taklit et: çıkışta last* alanı AYRILINAN
            // oturumla yazılır. activateTab hedef id'yi bundan SONRA yazmalı;
            // sıra bozulursa bu test yakalar (aynı-backend geçiş regresyonu).
            goToLanding = {
                landingCalled = true
                val leavingCodex = uiState.codexAppSessionId
                val leavingClaude = uiState.claudeAppSessionId
                uiState = uiState.copy(
                    backend = null,
                    lastCodexAppSessionId = if (leavingCodex.isNotBlank()) leavingCodex else uiState.lastCodexAppSessionId,
                    lastClaudeAppSessionId = if (leavingClaude.isNotBlank()) leavingClaude else uiState.lastClaudeAppSessionId,
                    codexAppSessionId = "",
                    claudeAppSessionId = "",
                )
            },
            enterAgyMode = {},
            enterClaudeAppMode = {
                enterClaudeAppModeCalled = true
                uiState = uiState.copy(backend = "claude-app", claudeAppSessionId = uiState.lastClaudeAppSessionId)
            },
            enterCodexAppMode = {
                enterCodexAppModeCalled = true
                uiState = uiState.copy(backend = "codex-app", codexAppSessionId = uiState.lastCodexAppSessionId)
            },

            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {}
        )

        // 1. Create a tab (claude oturumu aktifmiş gibi state'i de kur)
        delegate.syncActiveTab("claude-app", "", "claude-sess", "Claude Tab")
        val tab1Id = uiState.activeTabId

        // 2. Create another tab
        delegate.newTab()
        val tab2Id = uiState.activeTabId
        delegate.syncActiveTab("codex-app", "", "codex-sess", "Codex Tab")
        uiState = uiState.copy(backend = "codex-app", codexAppSessionId = "codex-sess")

        // Reset tracking vars
        landingCalled = false
        uiState = uiState.copy(messagePageSize = 500)

        // 3. Switch back to tab1 (claude-app)
        delegate.activateTab(tab1Id)
        assertEquals(tab1Id, uiState.activeTabId)
        assertEquals(100, uiState.messagePageSize)
        assertEquals("claude-sess", uiState.lastClaudeAppSessionId)
        assertTrue(landingCalled)
        assertTrue(enterClaudeAppModeCalled)
        assertEquals("claude-app", uiState.backend)

        // 4. Switch back to tab2 (codex-app)
        landingCalled = false
        delegate.activateTab(tab2Id)
        assertEquals(tab2Id, uiState.activeTabId)
        assertEquals("codex-sess", uiState.lastCodexAppSessionId)
        assertTrue(enterCodexAppModeCalled)
        assertEquals("codex-app", uiState.backend)
    }

    @Test
    fun testResetActiveTabReturnsToNewTabState() = runBlocking {
        // Geri tuşuyla ana menüye çıkış: aktif sekme "Yeni Sekme" durumuna döner,
        // diğer sekmeler ve aktif sekme kimliği değişmez.
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val fakeClient = FakeBridgeClient()

        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = fakeClient,
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {}
        )

        delegate.syncActiveTab("claude-app", "", "claude-sess", "Claude Tab")
        val tab1Id = uiState.activeTabId
        delegate.newTab()
        delegate.syncActiveTab("codex-app", "", "codex-sess", "Codex Tab")
        val tab2Id = uiState.activeTabId

        delegate.resetActiveTab()

        // Aktif sekme boşaldı ama yerinde duruyor
        assertEquals(tab2Id, uiState.activeTabId)
        assertEquals(2, uiState.openTabs.size)
        val resetTab = uiState.openTabs.first { it.id == tab2Id }
        assertEquals("", resetTab.backend)
        assertEquals("", resetTab.sessionId)
        assertEquals("Yeni Sekme", resetTab.title)

        // Diğer sekme etkilenmedi
        val otherTab = uiState.openTabs.first { it.id == tab1Id }
        assertEquals("claude-sess", otherTab.sessionId)

        // Kalıcılığa da yansıdı
        val obj = JSONObject(fakePrefs.getString("app_tabs")!!)
        val tabsArray = obj.getJSONArray("tabs")
        var persistedSessionForTab2 = "unset"
        for (i in 0 until tabsArray.length()) {
            val t = tabsArray.getJSONObject(i)
            if (t.getString("id") == tab2Id) persistedSessionForTab2 = t.optString("sessionId", "")
        }
        assertEquals("", persistedSessionForTab2)
    }

    @Test
    fun testActivateTabSameBackendSwitchesSession() = runBlocking {
        // Regresyon: aynı backend'de iki sekme arasında geçiş. exit* fonksiyonu
        // last* alanını ayrılınan oturumla yazdığı için activateTab hedef id'yi
        // çıkıştan SONRA yazmazsa hep eski oturuma geri bağlanılır.
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val fakeClient = FakeBridgeClient()
        var attachedSession = ""

        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = fakeClient,
            goToLanding = {
                val leaving = uiState.codexAppSessionId
                uiState = uiState.copy(
                    backend = null,
                    lastCodexAppSessionId = if (leaving.isNotBlank()) leaving else uiState.lastCodexAppSessionId,
                    codexAppSessionId = "",
                )
            },
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {
                attachedSession = uiState.lastCodexAppSessionId
                uiState = uiState.copy(backend = "codex-app", codexAppSessionId = uiState.lastCodexAppSessionId)
            },

            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {}
        )

        // Sekme A: codex sess-A aktif
        delegate.syncActiveTab("codex-app", "", "sess-A", "A")
        val tabAId = uiState.activeTabId
        // Sekme B: aynı backend, farklı oturum
        delegate.newTab()
        delegate.syncActiveTab("codex-app", "", "sess-B", "B")
        val tabBId = uiState.activeTabId
        // Şu an cihazda sess-A açıkmış gibi kur
        uiState = uiState.copy(backend = "codex-app", codexAppSessionId = "sess-A")

        delegate.activateTab(tabBId)
        assertEquals("sess-B", attachedSession)
        assertEquals("sess-B", uiState.codexAppSessionId)

        // Geri A'ya
        delegate.activateTab(tabAId)
        assertEquals("sess-A", attachedSession)
        assertEquals("sess-A", uiState.codexAppSessionId)
    }

    @Test
    fun testComputeTabStatuses() {
        val tabA = AppTab("id-a", "codex-app", "", "sess-A")
        val tabB = AppTab("id-b", "claude-app", "", "sess-B")
        val tabC = AppTab("id-c", "claude-app", "", "sess-C")
        val tabD = AppTab("id-d", "opencode2-app", "", "sess-D")
        val tabLanding = AppTab("id-l", "codex-app", "", "")

        val tabs = listOf(tabA, tabB, tabC, tabD, tabLanding)
        
        val liveSessionsCodex = listOf(
            LiveSession("sess-A", "/path/a", "model-a", "running")
        )
        val liveSessionsClaude = listOf(
            LiveSession("sess-B", "/path/b", "model-b", "idle", awaitingApproval = true, lastText = "approval required")
        )
        val liveByBackend = mapOf(
            "codex-app" to liveSessionsCodex,
            "claude-app" to liveSessionsClaude,
            "opencode2-app" to null
        )

        val prevStatuses = mapOf(
            "id-d" to TabStatus(live = true, running = true, cwd = "/prev-d")
        )

        val statuses = computeTabStatuses(tabs, activeTabId = "id-a", liveByBackend = liveByBackend, prev = prevStatuses)

        val statusA = statuses["id-a"]
        assertTrue(statusA != null)
        assertTrue(statusA!!.live)
        assertTrue(statusA.running)
        assertFalse(statusA.awaitingApproval)
        assertFalse(statusA.finishedUnseen)

        val statusB = statuses["id-b"]
        assertTrue(statusB != null)
        assertTrue(statusB!!.live)
        assertFalse(statusB.running)
        assertTrue(statusB.awaitingApproval)
        assertEquals("approval required", statusB.lastText)
        assertFalse(statusB.finishedUnseen)

        val statusC = statuses["id-c"]
        assertTrue(statusC != null)
        assertFalse(statusC!!.live)

        val statusD = statuses["id-d"]
        assertTrue(statusD != null)
        assertTrue(statusD!!.live)
        assertTrue(statusD.running)
        assertEquals("/prev-d", statusD.cwd)

        assertFalse(statuses.containsKey("id-l"))

        val liveSessionsCodex2 = listOf(
            LiveSession("sess-A", "/path/a", "model-a", "idle")
        )
        val liveSessionsClaude2 = listOf(
            LiveSession("sess-B", "/path/b", "model-b", "idle", awaitingApproval = false)
        )
        val liveByBackend2 = mapOf(
            "codex-app" to liveSessionsCodex2,
            "claude-app" to liveSessionsClaude2
        )

        val statuses2 = computeTabStatuses(tabs, activeTabId = "id-a", liveByBackend = liveByBackend2, prev = statuses)

        assertFalse(statuses2["id-a"]!!.finishedUnseen)
        assertTrue(statuses2["id-b"]!!.finishedUnseen)
    }

    @Test
    fun testTabDotState() {
        assertEquals(TabDotState.NORMAL, tabDotState(null))
        assertEquals(TabDotState.NORMAL, tabDotState(TabStatus(live = true, running = false, awaitingApproval = false, finishedUnseen = false)))
        assertEquals(TabDotState.APPROVAL, tabDotState(TabStatus(live = true, running = false, awaitingApproval = true)))
        assertEquals(TabDotState.RUNNING, tabDotState(TabStatus(live = true, running = true, awaitingApproval = false)))
        assertEquals(TabDotState.FINISHED, tabDotState(TabStatus(live = true, running = false, awaitingApproval = false, finishedUnseen = true)))
        assertEquals(TabDotState.DEAD, tabDotState(TabStatus(live = false)))
    }

    @Test
    fun testGenerateTabTitleRepoOnly() {
        // Model adı (opus/sonnet/sol) artık sekme başlığında yer almaz; sadece repo adı.
        assertEquals("agtest", generateTabTitle("claude-app", "", "/home/u/agtest", "claude-opus-4-8"))
        assertEquals("agtest", generateTabTitle("codex-app", "", "/home/u/agtest", "sol"))
        assertEquals("agtest", generateTabTitle("cowork", "", "/home/u/agtest", "sonnet"))
        // cwd boşsa backend etiketine düşer
        assertEquals("Claude", generateTabTitle("claude-app", "", "", "opus"))
        assertEquals("Codex", generateTabTitle("codex-app", "", "", ""))
    }

    @Test
    fun testLiveTitleLockedAfterFirst() {
        // Canlı başlık "ilk prompt sonrası bir kez" kuralıyla kilitlenir:
        // ikinci refresh'te bridge farklı title gönderse de ilk başlık korunur.
        val tab = AppTab("id-a", "claude-app", "", "sess-A")
        val tabs = listOf(tab)
        val live1 = listOf(LiveSession("sess-A", "/p/a", "m", "idle", title = "ilk soru"))
        val statuses1 = computeTabStatuses(tabs, "id-a", mapOf("claude-app" to live1), emptyMap())
        assertEquals("ilk soru", statuses1["id-a"]?.liveTitle)

        // İkinci refresh: bridge farklı title gönderiyor, ama kilit kuralı korur.
        val live2 = listOf(LiveSession("sess-A", "/p/a", "m", "idle", title = "ikinci soru"))
        val statuses2 = computeTabStatuses(tabs, "id-a", mapOf("claude-app" to live2), statuses1)
        assertEquals("ilk soru", statuses2["id-a"]?.liveTitle)

        // Boş title (yeni oturum, henüz prompt yok) yazmaz; prev boşsa boş kalır.
        val liveEmpty = listOf(LiveSession("sess-A", "/p/a", "m", "idle", title = ""))
        val statusesEmpty = computeTabStatuses(tabs, "id-a", mapOf("claude-app" to liveEmpty), emptyMap())
        assertEquals("", statusesEmpty["id-a"]?.liveTitle)
    }

    @Test
    fun testLiveTitleResetsOnSessionChange() {
        // Regresyon: aynı sekmede (tab.id sabit) farklı oturuma geçilince kilitli
        // başlık yeni oturuma geçmeli. syncActiveTab tab.id'yi koruyup sessionId'yi
        // değiştirdiği için kilit tab.id'ye değil sessionId'ye bağlı olmalıdır.
        // Aksi halde ilk oturumun başlığı sonsuza dek ekranda kalır.
        val tabId = "id-a"

        // 1. Oturum A açık, başlığı kilitlendi
        val tabA = AppTab(tabId, "claude-app", "", "sess-A")
        val liveA = listOf(LiveSession("sess-A", "/p/a", "m", "idle", title = "A başlığı"))
        val prevA = computeTabStatuses(listOf(tabA), tabId, mapOf("claude-app" to liveA), emptyMap())
        assertEquals("A başlığı", prevA[tabId]?.liveTitle)

        // 2. Çekmeceden oturum B açıldı: syncActiveTab aynı tab.id'de sessionId'yi sess-B yaptı.
        //    tabStatuses map'i hâlâ eski liveTitle'ı taşır (prev = prevA).
        val tabB = AppTab(tabId, "claude-app", "", "sess-B")
        val liveB = listOf(LiveSession("sess-B", "/p/b", "m", "idle", title = "B başlığı"))
        val statusesB = computeTabStatuses(listOf(tabB), tabId, mapOf("claude-app" to liveB), prevA)

        // Yeni oturumun başlığı gelmeli — kilit tab.id'ye değil sessionId'ye bağlı.
        assertEquals("B başlığı", statusesB[tabId]?.liveTitle)
    }

    @Test
    fun testLiveTitleResetsWhenNewSessionArrivesLate() {
        // Regresyon (canlıda görüldü: "proje · başlık"): sekme başka oturuma
        // geçtiği AN yeni oturum henüz canlı listede YOKSA (adopt/aç akışında bir
        // tick gecikme olur), null-branch eski liveTitle'ı taşırken sessionId'yi
        // yeni oturuma ilerletiyordu. Bir sonraki tick'te sessionId artık aynı
        // göründüğü için kilit eski başlığı kalıcı yapıyordu — başka (hatta
        // silinmiş) bir oturumun başlığı sonsuza dek asılı kalıyordu.
        val tabId = "id-a"

        // 1. Oturum A açık, başlığı kilitlendi.
        val tabA = AppTab(tabId, "claude-app", "", "sess-A")
        val liveA = listOf(LiveSession("sess-A", "/p/a", "m", "idle", title = "ttft ne"))
        val prevA = computeTabStatuses(listOf(tabA), tabId, mapOf("claude-app" to liveA), emptyMap())
        assertEquals("ttft ne", prevA[tabId]?.liveTitle)

        // 2. Sekme sess-B'ye geçti ama B henüz canlı listede YOK (boş liste).
        val tabB = AppTab(tabId, "claude-app", "", "sess-B")
        val mid = computeTabStatuses(listOf(tabB), tabId, mapOf("claude-app" to emptyList()), prevA)
        // Eski başlık taşınmamalı: bu status artık sess-B'ye ait.
        assertEquals("", mid[tabId]?.liveTitle)

        // 3. B canlı geldi: kendi başlığı yazılmalı, eski "ttft ne" değil.
        val liveB = listOf(LiveSession("sess-B", "/p/b", "m", "idle", title = "Setup TRLawBench repository locally"))
        val statusesB = computeTabStatuses(listOf(tabB), tabId, mapOf("claude-app" to liveB), mid)
        assertEquals("Setup TRLawBench repository locally", statusesB[tabId]?.liveTitle)
    }

    @Test
    fun renameUpdatesOpenTabByCodexThreadIdWhenShellIdDiffers() {
        val fakePrefs = FakeKeyValueStore()
        val tab = AppTab(
            id = "tab-codex",
            backend = "codex-app",
            sessionId = "shell-1",
            title = "agtest",
            bridgeProfileId = "default",
        )
        var uiState = RemoteUiState(
            openTabs = listOf(tab),
            activeTabId = tab.id,
            tabStatuses = mapOf(
                tab.id to TabStatus(
                    liveTitle = "Eski başlık",
                    threadId = "thread-1",
                    sessionId = "shell-1",
                ),
            ),
        )
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = FakeBridgeClient(),
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {},
        )

        delegate.renameSessionTitle("codex-app", "", "thread-1", "  Yeni   başlık  ")

        assertEquals("Yeni başlık", uiState.tabStatuses[tab.id]?.liveTitle)
        assertEquals("shell-1", uiState.tabStatuses[tab.id]?.sessionId)
        assertEquals("thread-1", uiState.tabStatuses[tab.id]?.threadId)
    }

    @Test
    fun testDeduplicateTabs() = runBlocking {
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val fakeClient = FakeBridgeClient()

        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = fakeClient,
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {}
        )

        // Sekme A: codex sess-X aktif
        delegate.syncActiveTab("codex-app", "", "sess-X", "A")
        val tabAId = uiState.activeTabId

        // Sekme B: yeni sekme aç
        delegate.newTab()
        // Sekme B'de aynı sess-X'e bağlan -> Sekme A silinmeli, sadece Sekme B kalmalı
        delegate.syncActiveTab("codex-app", "", "sess-X", "B")
        
        assertEquals(1, uiState.openTabs.size)
        assertEquals("B", uiState.openTabs.first().title)
        assertEquals("sess-X", uiState.openTabs.first().sessionId)
        assertEquals(uiState.activeTabId, uiState.openTabs.first().id)

        // Çakışmayan sekmelerin silinmediğini kontrol et
        delegate.newTab()
        // Sekme C: opencode-app backend'inde aynı sess-X id'sine bağlan (çakışma olmamalı çünkü backend farklı)
        delegate.syncActiveTab("opencode2-app", "", "sess-X", "C")
        
        assertEquals(2, uiState.openTabs.size)
    }

    @Test
    fun testSyncDoesNotOverwriteFullActiveTab() = runBlocking {
        // Merkez'den (proje/operasyon) oturum açmak: aktif sekmede BAŞKA bir oturum
        // yaşıyorsa syncActiveTab onu EZMEZ, yeni sekme açar. Sekme koruması.
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val fakeClient = FakeBridgeClient()

        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = fakeClient,
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {}
        )

        // Sekme A: claude oturumu aktif ve doluyken KALIYOR (arada newTab yok).
        delegate.syncActiveTab("claude-app", "", "claude-sess", "Claude Tab")
        val tabAId = uiState.activeTabId

        // Merkez'den cowork oturumu açıldı: aktif sekme dolu → yeni sekme açılmalı.
        delegate.syncActiveTab("cowork", "codex", "cowork-sess", "Cowork Tab")

        assertEquals(2, uiState.openTabs.size)
        val tabA = uiState.openTabs.first { it.id == tabAId }
        assertEquals("claude-sess", tabA.sessionId) // eski sekme korundu
        val active = uiState.openTabs.first { it.id == uiState.activeTabId }
        assertEquals("cowork-sess", active.sessionId) // yeni sekme aktif
        assertEquals("cowork", active.backend)
        assertTrue(tabAId != uiState.activeTabId)
    }

    @Test
    fun testSyncWithExistingSessionSwitchesInsteadOfOverwriting() = runBlocking {
        // Aktif sekme dolu ve hedef oturum BAŞKA bir sekmede zaten açık: yeni sekme
        // açmak yerine o sekme aktifleşir (bir oturum tek sekmede yaşar).
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val fakeClient = FakeBridgeClient()

        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = fakeClient,
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {}
        )

        delegate.syncActiveTab("codex-app", "", "sess-A", "A")
        val tabAId = uiState.activeTabId
        delegate.newTab()
        delegate.syncActiveTab("claude-app", "", "sess-B", "B")
        val tabBId = uiState.activeTabId

        // Aktif sekme B (dolu) iken sess-A'yı sync et: A sekmesi aktifleşmeli,
        // yeni sekme açılmamalı, sekme sayısı sabit kalmalı.
        delegate.syncActiveTab("codex-app", "", "sess-A", "A")

        assertEquals(2, uiState.openTabs.size)
        assertEquals(tabAId, uiState.activeTabId)
    }

    @Test
    fun activateTabDoesNotCallSessionDeadWhenLiveCheckTimesOut() = runBlocking {
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val emitted = mutableListOf<String>()
        val fakeClient = FakeBridgeClient().apply {
            jsonResponseProvider = { throw java.net.SocketTimeoutException("timeout") }
        }
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = { emitted.add(it) },
            client = fakeClient,
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {},
        )

        delegate.syncActiveTab("codex-app", "", "still-live", "Codex")
        delegate.activateTab(uiState.activeTabId)

        assertFalse(emitted.contains("Oturum artık canlı değil"))
    }

    @Test
    fun testRewindReplacesCoworkSessionIdInPlace() = runBlocking {
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = FakeBridgeClient(),
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {},
        )

        delegate.syncActiveTab("cowork", "claude-app", "fable-old", "Fable")
        val rewoundTabId = uiState.activeTabId
        delegate.newTab()
        delegate.syncActiveTab("cowork", "claude-app", "opus-other", "Opus")
        val opusTabId = uiState.activeTabId
        delegate.activateTab(rewoundTabId)

        delegate.replaceSessionId("cowork", "claude-app", "fable-old", "fable-rewound")
        delegate.syncActiveTab("cowork", "claude-app", "fable-rewound", "Opus")

        assertEquals(2, uiState.openTabs.size)
        assertEquals(rewoundTabId, uiState.activeTabId)
        assertEquals("fable-rewound", uiState.openTabs.first { it.id == rewoundTabId }.sessionId)
        assertEquals("opus-other", uiState.openTabs.first { it.id == opusTabId }.sessionId)
    }

    @Test
    fun testCloseOtherTabs() = runBlocking {
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val fakeClient = FakeBridgeClient()

        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = fakeClient,
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {}
        )

        // Start with 3 tabs
        delegate.syncActiveTab("codex-app", "", "sess-A", "Tab A")
        val tabAId = uiState.activeTabId
        
        delegate.newTab()
        delegate.syncActiveTab("claude-app", "", "sess-B", "Tab B")
        val tabBId = uiState.activeTabId
        
        delegate.newTab()
        delegate.syncActiveTab("opencode2-app", "", "sess-C", "Tab C")
        val tabCId = uiState.activeTabId

        assertEquals(3, uiState.openTabs.size)

        // Close other tabs keeping Tab B
        delegate.closeOtherTabs(tabBId)

        assertEquals(1, uiState.openTabs.size)
        assertEquals(tabBId, uiState.openTabs.first().id)
        assertEquals(tabBId, uiState.activeTabId)
    }

    @Test
    fun testMoveTab() = runBlocking {
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val fakeClient = FakeBridgeClient()

        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = fakeClient,
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {}
        )

        delegate.syncActiveTab("codex-app", "", "sess-A", "Tab A")
        val tabAId = uiState.activeTabId
        
        delegate.newTab()
        delegate.syncActiveTab("claude-app", "", "sess-B", "Tab B")
        val tabBId = uiState.activeTabId

        assertEquals(2, uiState.openTabs.size)
        assertEquals(tabAId, uiState.openTabs[0].id)
        assertEquals(tabBId, uiState.openTabs[1].id)

        // Move tab 0 to 1
        delegate.moveTab(0, 1)

        assertEquals(2, uiState.openTabs.size)
        assertEquals(tabBId, uiState.openTabs[0].id)
        assertEquals(tabAId, uiState.openTabs[1].id)

        // Invalid index move should be a no-op
        delegate.moveTab(0, 5)
        assertEquals(tabBId, uiState.openTabs[0].id)
        assertEquals(tabAId, uiState.openTabs[1].id)
    }

    @Test
    fun removeProjectTabsClearsExactAndNestedSessionsAndReplacesActiveTab() {
        val fakePrefs = FakeKeyValueStore()
        val rootTab = AppTab("tab-root", "codex-app", sessionId = "session-root", title = "Root", bridgeProfileId = "default")
        val childTab = AppTab("tab-child", "claude-app", sessionId = "session-child", title = "Child", bridgeProfileId = "default")
        val otherTab = AppTab("tab-other", "opencode2-app", sessionId = "session-other", title = "Other", bridgeProfileId = "default")
        var uiState = RemoteUiState(
            openTabs = listOf(rootTab, childTab, otherTab),
            activeTabId = childTab.id,
            tabStatuses = mapOf(
                rootTab.id to TabStatus(cwd = "C:\\work\\project"),
                childTab.id to TabStatus(cwd = "C:/work/project/packages/child"),
                otherTab.id to TabStatus(cwd = "C:/work/other"),
            ),
        )
        var landingCalled = false
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = FakeBridgeClient(),
            goToLanding = { landingCalled = true },
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {},
        )

        delegate.removeProjectTabs("C:/work/project", setOf("session-root"))

        assertTrue(landingCalled)
        assertFalse(uiState.openTabs.any { it.id == rootTab.id || it.id == childTab.id })
        assertTrue(uiState.openTabs.any { it.id == otherTab.id })
        val replacement = uiState.openTabs.single { it.id != otherTab.id }
        assertEquals("", replacement.sessionId)
        assertEquals(replacement.id, uiState.activeTabId)
        assertFalse(uiState.tabStatuses.containsKey(rootTab.id))
        assertFalse(uiState.tabStatuses.containsKey(childTab.id))
    }

    // Tek oturum silindiğinde SADECE o sekme kapanmalı. Kritik olan ikinci
    // iddia: aynı cwd'yi paylaşan komşu sekmeye dokunulmuyor — removeSessionTabs
    // altta removeProjectTabs'ı boş projectPath ile çağırıyor, cwd kuralının
    // gerçekten devre dışı kaldığını burası doğruluyor.
    @Test
    fun removeSessionTabsClosesOnlyMatchingSessionAndIgnoresCwd() {
        val fakePrefs = FakeKeyValueStore()
        val silinen = AppTab("tab-1", "opencode2-app", sessionId = "ses-silinen", title = "Silinen", bridgeProfileId = "default")
        val komsu = AppTab("tab-2", "opencode2-app", sessionId = "ses-komsu", title = "Komşu", bridgeProfileId = "default")
        var uiState = RemoteUiState(
            openTabs = listOf(silinen, komsu),
            activeTabId = komsu.id,
            tabStatuses = mapOf(
                silinen.id to TabStatus(cwd = "C:/Users/ornek"),
                komsu.id to TabStatus(cwd = "C:/Users/ornek"),
            ),
        )
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = FakeBridgeClient(),
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {},
        )

        delegate.removeSessionTabs(setOf("ses-silinen"))

        assertFalse(uiState.openTabs.any { it.id == silinen.id })
        assertTrue(uiState.openTabs.any { it.id == komsu.id })
        assertEquals(komsu.id, uiState.activeTabId)
        assertFalse(uiState.tabStatuses.containsKey(silinen.id))
    }

    @Test
    fun removeSessionTabsWithEmptySetIsNoOp() {
        val fakePrefs = FakeKeyValueStore()
        val tab = AppTab("tab-1", "opencode2-app", sessionId = "ses-1", title = "Tek", bridgeProfileId = "default")
        var uiState = RemoteUiState(openTabs = listOf(tab), activeTabId = tab.id)
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = FakeBridgeClient(),
            goToLanding = {},
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {},
        )

        delegate.removeSessionTabs(emptySet())

        assertEquals(listOf(tab.id), uiState.openTabs.map { it.id })
        assertEquals(tab.id, uiState.activeTabId)
    }

    // ── Geri jesti: sekmeleri tek tek tüket ────────────────────────────────
    private fun emptyTab(id: String) = AppTab(id, backend = "", title = "Yeni Sekme")
    private fun fullTab(id: String) = AppTab(id, backend = "claude-app", sessionId = "s-$id", title = "Repo")

    @Test
    fun backOnEmptyTabClosesItWhenOtherTabsExist() {
        val tabs = listOf(fullTab("a"), emptyTab("b"))
        assertEquals(
            ChatBackAction.CLOSE_EMPTY_TAB,
            chatBackAction(tabs, "b", hasActiveBackend = false),
        )
    }

    @Test
    fun backOnLastEmptyTabFallsThroughToSystem() {
        val tabs = listOf(emptyTab("a"))
        assertEquals(
            ChatBackAction.SYSTEM_BACK,
            chatBackAction(tabs, "a", hasActiveBackend = false),
        )
    }

    @Test
    fun backOnLoadedTabAsksSameConfirmAsCloseButton() {
        val tabs = listOf(emptyTab("a"), fullTab("b"))
        assertEquals(
            ChatBackAction.CONFIRM_CLOSE_TAB,
            chatBackAction(tabs, "b", hasActiveBackend = false),
        )
    }

    @Test
    fun activeBackendWithoutSessionIdStillCountsAsLoaded() {
        // Yeni başlamış oturum syncActiveTab'den önce onaysız kapanmamalı.
        val tabs = listOf(emptyTab("a"))
        assertEquals(
            ChatBackAction.CONFIRM_CLOSE_TAB,
            chatBackAction(tabs, "a", hasActiveBackend = true),
        )
    }

    @Test
    fun missingActiveTabFallsThroughToSystem() {
        assertEquals(
            ChatBackAction.SYSTEM_BACK,
            chatBackAction(emptyList(), "yok", hasActiveBackend = false),
        )
        assertEquals(
            ChatBackAction.SYSTEM_BACK,
            chatBackAction(listOf(emptyTab("a")), "baska-id", hasActiveBackend = false),
        )
    }

    @Test
    fun backConsumesTabsOneByOneDownToTheLastEmptyOne() {
        // [dolu, boş, boş] → geri: boşlar sırayla tüketilir, dolu onay ister,
        // kapanınca yerine boş sekme gelir, orada sistem devralır.
        var tabs = listOf(fullTab("a"), emptyTab("b"), emptyTab("c"))
        assertEquals(ChatBackAction.CLOSE_EMPTY_TAB, chatBackAction(tabs, "c", false))
        tabs = tabs.filterNot { it.id == "c" }
        assertEquals(ChatBackAction.CLOSE_EMPTY_TAB, chatBackAction(tabs, "b", false))
        tabs = tabs.filterNot { it.id == "b" }
        assertEquals(ChatBackAction.CONFIRM_CLOSE_TAB, chatBackAction(tabs, "a", false))
        // closeTab son sekmeyi kapatınca yerine boş sekme koyar (mevcut davranış).
        tabs = listOf(emptyTab("yeni"))
        assertEquals(ChatBackAction.SYSTEM_BACK, chatBackAction(tabs, "yeni", false))
    }

    @Test
    fun coworkSekmesiTekAtimlikBaglanmaHedefiYazar() = runBlocking {
        // Regresyon (20.08.2026): cowork, diger dort saglayicinin gectigi
        // duzeltmenin disinda kalmisti. activateTab yalniz lastCowork* yaziyor,
        // enterCoworkMode da HER GIRISTE ona geri bagleniyordu; bos sekmede
        // "Cowork" cipine dokunmak ayrildigin oturumu geri aciyor ve ayni
        // alanda ikinci bir oturum acilamiyordu. Hedef artik tek atimlik.
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = FakeBridgeClient(),
            goToLanding = { uiState = uiState.copy(backend = null) },
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {},
        )

        delegate.syncActiveTab("cowork", "opencode2-app", "cw-1", "Alan 1")
        val cowork1 = uiState.activeTabId
        delegate.newTab()
        val bosSekme = uiState.activeTabId

        // Cowork sekmesine gecis: hedef yazilir.
        delegate.activateTab(cowork1, notifyIfDead = false)
        assertEquals("cowork", uiState.pendingBindBackend)
        assertEquals("cw-1", uiState.pendingBindSessionId)
        // Saglayici da sekmeden gelir; enterCoworkMode hangi listeye bakacagini
        // buradan ogreniyor.
        assertEquals("opencode2-app", uiState.lastCoworkProvider)

        // BOS sekmeye gecis hedef YAZMAZ: yeni-oturum ekrani acik kalmali.
        // (Hedefi enterCoworkMode tuketiyor; burada delege sahte oldugu icin
        // elle temizleniyor.)
        uiState = uiState.copy(pendingBindBackend = "", pendingBindSessionId = "")
        delegate.activateTab(bosSekme, notifyIfDead = false)
        assertEquals("", uiState.pendingBindBackend)
        assertEquals("", uiState.pendingBindSessionId)
    }

    @Test
    fun agySekmesiTekAtimlikBaglanmaHedefiYazar() = runBlocking {
        // Regresyon (25.08.2026): agy son backend'di ki hala `lastAgySessionId`
        // uzerinden baglaniyordu. `enterAgyMode` pendingBind kuralina gecince bu
        // dal hedefi yazmasaydi sekmeye dokunmak oturumu geri getirmez, kurulum
        // ekranina duserdi (hermes'te aynen bu yasandi). Bilincli devralma yolu.
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState()
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = FakeBridgeClient(),
            goToLanding = { uiState = uiState.copy(backend = null) },
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = {},
            enterOpencode2AppMode = {},
            enterCoworkMode = {},
        )

        delegate.syncActiveTab("agy", "", "agy-1", "Antigravity")
        val agySekmesi = uiState.activeTabId
        delegate.newTab()
        val bosSekme = uiState.activeTabId

        delegate.activateTab(agySekmesi, notifyIfDead = false)
        assertEquals("agy", uiState.pendingBindBackend)
        assertEquals("agy-1", uiState.pendingBindSessionId)
        // "En son neredeydik" hafizasi da yazilmaya devam eder.
        assertEquals("agy-1", uiState.lastAgySessionId)

        // BOS sekmeye gecis hedef YAZMAZ: yeni-oturum ekrani acik kalmali.
        uiState = uiState.copy(pendingBindBackend = "", pendingBindSessionId = "")
        delegate.activateTab(bosSekme, notifyIfDead = false)
        assertEquals("", uiState.pendingBindBackend)
        assertEquals("", uiState.pendingBindSessionId)
    }

    /**
     * OPENCODE SEKMESINE DONUNCE OTURUM YUKLENIYOR MU.
     *
     * 26.09.2026, kullanici bildirdi: "opencode2 sekmesinden cikip geri
     * geldigimde oturum yuklenmiyor". Sebep `activateTab`'in iki `when`
     * blogunda da opencode2 dalinin HIC olmamasiydi — pendingBind yazilmiyor
     * ve enter* cagrilmiyordu, yani `goToLanding()` sonrasi ekran landing'de
     * kaliyordu. Iki ayagi da burada siniyoruz.
     * 30.09.2026: v1 sokuldu, tek durum ailesi kaldi — "v1 kutusu kirlenmesin"
     * iddiasi artik anlamsiz, yerine oturumun AILEYE yazildigi kilitlenir.
     */
    @Test
    fun opencode2SekmesineDonunceOturumBaglanir() = runBlocking {
        val fakePrefs = FakeKeyValueStore()
        var uiState = RemoteUiState(backend = "opencode2-app")
        var enterV2Sayaci = 0
        var enterV1Sayaci = 0
        val delegate = TabsDelegate(
            prefs = fakePrefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { uiState },
            update = { updater -> uiState = updater(uiState) },
            emit = {},
            client = FakeBridgeClient(),
            goToLanding = { uiState = uiState.copy(backend = null) },
            enterAgyMode = {},
            enterClaudeAppMode = {},
            enterCodexAppMode = {},
            enterOpencodeAppMode = { enterV1Sayaci++ },
            enterOpencode2AppMode = {
                enterV2Sayaci++
                uiState = uiState.copy(backend = "opencode2-app")
            },
            enterCoworkMode = {},
        )

        delegate.syncActiveTab("opencode2-app", "", "v2-oturum", "OpenCode")
        val v2Sekmesi = uiState.activeTabId
        delegate.newTab()

        delegate.activateTab(v2Sekmesi, notifyIfDead = false)

        assertEquals("opencode2-app", uiState.pendingBindBackend)
        assertEquals("v2-oturum", uiState.pendingBindSessionId)
        // Oturum OpenCode ailesine yazilir.
        assertEquals("v2-oturum", uiState.opencode.lastSessionId)
        assertEquals(1, enterV2Sayaci)
        // v1 girisi artik yok: eski enter kancasi hic cagrilmamali.
        assertEquals(0, enterV1Sayaci)
        assertEquals("opencode2-app", uiState.backend)
    }
}
