package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateReducerTest {

    @Test
    fun codexPlanIsVisibleOnlyWhileItsProviderIsRunning() {
        val plan = listOf(PlanItem("Eski plan", "pending"))
        val runningCodex = RemoteUiState(
            backend = Backend.CODEX_APP.id,
            running = true,
            codex = CodexUiState(plan = plan),
        )

        assertTrue(SessionStateReducer.shouldShowCodexPlan(runningCodex))
        assertFalse(SessionStateReducer.shouldShowCodexPlan(runningCodex.copy(running = false)))
        assertFalse(SessionStateReducer.shouldShowCodexPlan(runningCodex.copy(backend = Backend.CLAUDE_APP.id)))
        assertFalse(SessionStateReducer.shouldShowCodexPlan(runningCodex.copy(codex = CodexUiState())))
    }

    @Test
    fun testShouldRefreshCoworkOutputs() {
        val stateBase = RemoteUiState(
            backend = "cowork",
            cowork = CoworkUiState(provider = "codex-app"),
            running = true
        )
        val snapshotBase = BackendStreamSnapshot(
            seq = 1L,
            transcript = "",
            messages = emptyList(),
            running = false,
            awaitingApproval = false,
            awaitingFirstOutput = false,
            choices = emptyList(),
            contextTokens = 0,
            contextWindow = 0
        )

        // 1. Should refresh when backend is cowork, provider is not claude-app, state is running, snapshot is not running
        assertTrue(SessionStateReducer.shouldRefreshCoworkOutputs(stateBase, snapshotBase))

        // 2. Should NOT refresh when backend is NOT cowork
        assertFalse(SessionStateReducer.shouldRefreshCoworkOutputs(stateBase.copy(backend = "claude-app"), snapshotBase))

        // 3. Should NOT refresh when provider is claude-app
        assertFalse(SessionStateReducer.shouldRefreshCoworkOutputs(stateBase.copy(cowork = stateBase.cowork.copy(provider = "claude-app")), snapshotBase))

        // 4. Should NOT refresh when state is NOT running
        assertFalse(SessionStateReducer.shouldRefreshCoworkOutputs(stateBase.copy(running = false), snapshotBase))

        // 5. Should NOT refresh when snapshot is running
        assertFalse(SessionStateReducer.shouldRefreshCoworkOutputs(stateBase, snapshotBase.copy(running = true)))
    }

    // Canlı arıza (15 Ağu 2026): sekme değişiminde gelen
    // geç bir Claude karesi sağlayıcı state'ine permissionMode="auto" yazdı; sonraki devam çağrısı
    // köprüden "invalid permission mode: auto" alıp oturumu açtırmadı.
    @Test
    fun yabanciIzinKipiSaglayiciStateiniKirletmez() {
        // OMP'de: "plan" ve "auto" OMP'nin kipi değil, yok sayılmalı.
        val omp = RemoteUiState(backend = "omp", omp = OmpUiState(permissionMode = "yolo"))
        assertEquals("yolo", SessionStateReducer.reduceBackendOptions(omp, permissionMode = "plan").omp.permissionMode)
        assertEquals("yolo", SessionStateReducer.reduceBackendOptions(omp, permissionMode = "auto").omp.permissionMode)
        // Kendi kipleri geçmeli.
        assertEquals("write", SessionStateReducer.reduceBackendOptions(omp, permissionMode = "write").omp.permissionMode)

        // Köprüden canlı liste geldiyse otorite odur; listede olmayan reddedilir.
        val withList = omp.copy(
            omp = omp.omp.copy(permissionModes = listOf(PermissionMode("yolo"), PermissionMode("ask"))),
        )
        assertEquals("yolo", SessionStateReducer.reduceBackendOptions(withList, permissionMode = "write").omp.permissionMode)
    }

    // Prefill (staleConversation) yalnız GERÇEK mesaj listesi taşıyan snapshot'la
    // düşer; meta-only delta (messages=null) bayat görüntüyü "taze" sayamaz.
    @Test
    fun staleBayragiYalnizMesajliSnapshotlaDuser() {
        val stale = RemoteUiState(
            backend = "claude-app",
            claude = ClaudeUiState(sessionId = "s1"),
            messagesList = listOf(ChatMessage("agent", "prefill", rowId = "1")),
            staleConversation = true,
        )
        val metaOnly = BackendStreamSnapshot(
            seq = 1L, transcript = null, messages = null, running = true,
            awaitingApproval = null, awaitingFirstOutput = null, choices = null,
            contextTokens = null, contextWindow = null,
        )
        assertTrue(SessionStateReducer.reduceStreamSnapshot(stale, metaOnly).staleConversation)

        val full = metaOnly.copy(messages = listOf(ChatMessage("agent", "taze", rowId = "2")))
        val reduced = SessionStateReducer.reduceStreamSnapshot(stale, full)
        assertFalse(reduced.staleConversation)
        assertEquals("taze", reduced.messagesList.single().text)
    }

    @Test
    fun testReduceStreamSnapshotRekeyed() {
        // A1 Defense: active backend is claude-app, sessionId changes
        val initialMessages = listOf(ChatMessage("user", "hello", rowId = "1"))
        val state = RemoteUiState(
            backend = "claude-app",
            claudeAppSessionId = "old-session-id",
            messagesList = initialMessages,
            transcript = "Old transcript",
            running = true
        )

        val newMessages = listOf(ChatMessage("user", "rekeyed hello", rowId = "2"))
        val snapshot = BackendStreamSnapshot(
            seq = 1L,
            sessionId = "new-session-id",
            transcript = "New transcript",
            messages = newMessages,
            running = false,
            awaitingApproval = false,
            awaitingFirstOutput = false,
            choices = emptyList(),
            contextTokens = 0,
            contextWindow = 0
        )

        val reduced = SessionStateReducer.reduceStreamSnapshot(state, snapshot)

        // It should accept new sessionId, new messages list, and new transcript
        assertEquals("new-session-id", reduced.claudeAppSessionId)
        assertEquals(newMessages, reduced.messagesList)
        assertEquals("New transcript", reduced.transcript)
        assertFalse(reduced.running)
    }

    @Test
    fun testReduceStreamSnapshotNormal() {
        val state = RemoteUiState(
            backend = "claude-app",
            claudeAppSessionId = "session-id",
            messagesList = listOf(ChatMessage("user", "hello", rowId = "1")),
            transcript = "Initial transcript",
            running = false
        )

        val snapshot = BackendStreamSnapshot(
            seq = 2L,
            sessionId = "session-id", // Same session ID, no rekey
            transcript = "Updated transcript",
            messages = null, // null should fall back to state messages
            running = true,
            awaitingApproval = true,
            awaitingFirstOutput = true,
            choices = listOf("Option A", "Option B"),
            contextTokens = 1500,
            contextWindow = 8000,
            cost = 0.05
        )

        val reduced = SessionStateReducer.reduceStreamSnapshot(state, snapshot)

        assertEquals("session-id", reduced.claudeAppSessionId)
        assertEquals(state.messagesList, reduced.messagesList) // Fallback to state messages
        assertEquals("Updated transcript", reduced.transcript)
        assertTrue(reduced.running)
        assertTrue(reduced.awaitingApproval)
        assertTrue(reduced.awaitingFirstOutput)
        assertEquals(listOf("Option A", "Option B"), reduced.choices)
        assertEquals(0.05, reduced.cost, 0.0001)
    }

    @Test
    fun testReduceStreamSnapshotEffort() {
        // Effort logic tests
        // 1. Backend is claude-app
        val stateClaude = RemoteUiState(backend = "claude-app", claudeAppEffort = "low")
        val snapshotClaude = BackendStreamSnapshot(
            seq = 1L,
            effort = "high",
            transcript = null,
            messages = null,
            running = null,
            awaitingApproval = null,
            awaitingFirstOutput = null,
            choices = null,
            contextTokens = null,
            contextWindow = null
        )
        val reducedClaude = SessionStateReducer.reduceStreamSnapshot(stateClaude, snapshotClaude)
        assertEquals("high", reducedClaude.claudeAppEffort)
        assertEquals("", reducedClaude.codexAppEffort)

        // 2. Backend is cowork, provider is codex-app
        val stateCoworkCodex = RemoteUiState(backend = "cowork", cowork = CoworkUiState(provider = "codex-app"), codexAppEffort = "low")
        val reducedCoworkCodex = SessionStateReducer.reduceStreamSnapshot(stateCoworkCodex, snapshotClaude)
        assertEquals("high", reducedCoworkCodex.codexAppEffort)
    }

    @Test
    fun testReduceStreamSnapshotContextPercent() {
        val state = RemoteUiState(backend = "codex-app", codexAppContextPercent = 0)
        val snapshot = BackendStreamSnapshot(
            seq = 1L,
            contextTokens = 2000,
            contextWindow = 8000,
            transcript = null,
            messages = null,
            running = null,
            awaitingApproval = null,
            awaitingFirstOutput = null,
            choices = null
        )
        val reduced = SessionStateReducer.reduceStreamSnapshot(state, snapshot)
        assertEquals(25, reduced.codexAppContextPercent)
    }

    private fun emptyCodexSnapshot(): BackendStreamSnapshot = BackendStreamSnapshot(
        seq = 1L,
        transcript = null,
        messages = null,
        running = null,
        awaitingApproval = null,
        awaitingFirstOutput = null,
        choices = null,
        contextTokens = null,
        contextWindow = null,
    )

    @Test
    fun testReduceStreamSnapshotGoal() {
        val goal = CodexGoal(threadId = "t1", objective = "Testleri geçir", status = "active", tokensUsed = 42)

        // 1. Codex aktif + goalPresent + dolu goal -> yazılır
        val stateCodex = RemoteUiState(backend = "codex-app")
        val withGoal = SessionStateReducer.reduceStreamSnapshot(
            stateCodex,
            emptyCodexSnapshot().copy(goalPresent = true, goal = goal),
        )
        assertEquals(goal, withGoal.codexAppGoal)

        // 2. goalPresent=false (eski köprü) -> mevcut hedef KORUNUR
        val stateWithExisting = stateCodex.copy(codex = CodexUiState(goal = goal))
        val preserved = SessionStateReducer.reduceStreamSnapshot(
            stateWithExisting,
            emptyCodexSnapshot().copy(goalPresent = false, goal = null),
        )
        assertEquals(goal, preserved.codexAppGoal)

        // 3. goalPresent=true + null goal -> TEMİZLENİR
        val cleared = SessionStateReducer.reduceStreamSnapshot(
            stateWithExisting,
            emptyCodexSnapshot().copy(goalPresent = true, goal = null),
        )
        assertEquals(null, cleared.codexAppGoal)

        // 4. Yalnız codex backend'de yazılır: claude-app aktifken hedef dokunulmaz
        val stateClaude = RemoteUiState(backend = "claude-app")
        val claudeReduced = SessionStateReducer.reduceStreamSnapshot(
            stateClaude,
            emptyCodexSnapshot().copy(goalPresent = true, goal = goal),
        )
        assertEquals(null, claudeReduced.codexAppGoal)
    }
}
