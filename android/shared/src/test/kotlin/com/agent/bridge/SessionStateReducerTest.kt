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

    // Ajan seçimi snapshot'ta taşınır (yan cihazda değiştirilince çip güncellensin),
    // ama YALNIZ opencode aktifken — başka backend'in snapshot'ı opencode state'ini
    // kirletmemeli.
    @Test
    fun ajanSecimiYalnizOpencodeAktifkenYazilir() {
        val meta = BackendStreamSnapshot(
            seq = 1L, transcript = null, messages = null, running = null,
            awaitingApproval = null, awaitingFirstOutput = null, choices = null,
            contextTokens = null, contextWindow = null, agent = "atlasjb",
        )
        val opencode = RemoteUiState(backend = "opencode2-app", opencode = OpencodeUiState(agent = "build"))
        assertEquals("atlasjb", SessionStateReducer.reduceStreamSnapshot(opencode, meta).opencode.agent)

        // "" geçerli bir değer (otomatik) — boş diye yok sayılmamalı.
        assertEquals("", SessionStateReducer.reduceStreamSnapshot(opencode, meta.copy(agent = "")).opencode.agent)

        // Alan hiç yoksa (eski köprü) mevcut seçime dokunma.
        assertEquals("build", SessionStateReducer.reduceStreamSnapshot(opencode, meta.copy(agent = null)).opencode.agent)

        val claude = RemoteUiState(backend = "claude-app", opencode = OpencodeUiState(agent = "build"))
        assertEquals("build", SessionStateReducer.reduceStreamSnapshot(claude, meta).opencode.agent)

        val coworkOpencode = RemoteUiState(
            backend = "cowork",
            cowork = CoworkUiState(provider = "opencode2-app"),
            opencode = OpencodeUiState(agent = "build"),
        )
        assertEquals("atlasjb", SessionStateReducer.reduceStreamSnapshot(coworkOpencode, meta).opencode.agent)
    }

    // Canlı arıza (15 Ağu 2026): sekme değişiminde gelen geç bir Claude karesi
    // opencode state'ine permissionMode="auto" yazdı; sonraki devam çağrısı
    // köprüden "invalid permission mode: auto" alıp oturumu açtırmadı.
    @Test
    fun yabanciIzinKipiSaglayiciStateiniKirletmez() {
        val opencode = RemoteUiState(
            backend = "opencode2-app",
            opencode = OpencodeUiState(permissionMode = "yolo"),
        )
        // "auto" Claude'un kipi — opencode'da yok, yok sayılmalı.
        assertEquals("yolo", SessionStateReducer.reduceBackendOptions(opencode, permissionMode = "auto").opencode.permissionMode)
        assertEquals("yolo", SessionStateReducer.reduceBackendOptions(opencode, permissionMode = "acceptEdits").opencode.permissionMode)
        // Kendi kipleri geçmeli.
        assertEquals("ask", SessionStateReducer.reduceBackendOptions(opencode, permissionMode = "ask").opencode.permissionMode)
        assertEquals("plan", SessionStateReducer.reduceBackendOptions(opencode, permissionMode = "plan").opencode.permissionMode)

        // Köprüden canlı liste geldiyse otorite odur; listede olmayan reddedilir.
        val withList = opencode.copy(
            opencode = opencode.opencode.copy(permissionModes = listOf(PermissionMode("yolo"), PermissionMode("ask"))),
        )
        assertEquals("yolo", SessionStateReducer.reduceBackendOptions(withList, permissionMode = "plan").opencode.permissionMode)

        // OMP'de de aynı kapı: "plan" OMP'nin kipi değil.
        val omp = RemoteUiState(backend = "omp", omp = OpencodeUiState(permissionMode = "yolo"))
        assertEquals("yolo", SessionStateReducer.reduceBackendOptions(omp, permissionMode = "plan").omp.permissionMode)
        assertEquals("write", SessionStateReducer.reduceBackendOptions(omp, permissionMode = "write").omp.permissionMode)
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

    // GÖREV PANOSU — soket yolu. Bu depoda tekrarlayan hata sınıfı "delege
    // yazıldı, okuma dalı unutuldu"; pano iki taşımadan da (WS snapshot ve
    // delta setMeta) geçtiği için okuma dalı burada kilitleniyor.
    @Test
    fun gorevPanosuYalnizOpencodeAktifkenYazilir() {
        val liste = listOf(
            OpencodeTodo("Köprüyü oku", "completed"),
            OpencodeTodo("Panoyu ekle", "in_progress"),
        )
        val meta = emptyCodexSnapshot().copy(todos = liste, contextPctPresent = true, contextPct = 42)

        val opencode = RemoteUiState(backend = "opencode2-app")
        val yazildi = SessionStateReducer.reduceStreamSnapshot(opencode, meta)
        assertEquals(liste, yazildi.opencode.todos)
        assertEquals(42, yazildi.opencode.contextPct)

        // Cowork + opencode aynı oturum: pano oraya da yazılmalı.
        val cowork = RemoteUiState(backend = "cowork", cowork = CoworkUiState(provider = "opencode2-app"))
        assertEquals(liste, SessionStateReducer.reduceStreamSnapshot(cowork, meta).opencode.todos)

        // Başka backend'in geç gelen karesi opencode panosunu EZMEZ.
        val claude = RemoteUiState(backend = "claude-app", opencode = OpencodeUiState(todos = liste, contextPct = 42))
        val korundu = SessionStateReducer.reduceStreamSnapshot(claude, meta.copy(todos = emptyList(), contextPct = 5))
        assertEquals(liste, korundu.opencode.todos)
        assertEquals(42, korundu.opencode.contextPct)
    }

    @Test
    fun gorevPanosuAlanYokkenMevcutHalineDokunmaz() {
        val liste = listOf(OpencodeTodo("Tek madde", "pending"))
        val state = RemoteUiState(
            backend = "opencode2-app",
            opencode = OpencodeUiState(todos = liste, contextPct = 63),
        )

        // Delta karesinde alanlar hiç yoksa (değişmedi) pano OLDUĞU GİBİ kalır.
        val dokunulmadi = SessionStateReducer.reduceStreamSnapshot(state, emptyCodexSnapshot())
        assertEquals(liste, dokunulmadi.opencode.todos)
        assertEquals(63, dokunulmadi.opencode.contextPct)

        // Boş liste GERÇEK bir bilgi: ajan todo'ları temizledi.
        val temizlendi = SessionStateReducer.reduceStreamSnapshot(
            state,
            emptyCodexSnapshot().copy(todos = emptyList()),
        )
        assertTrue(temizlendi.opencode.todos.isEmpty())

        // contextPct'te null tek başına yetmiyor: "alan geldi ve ölçülemiyor"
        // ancak present bayrağıyla ifade edilebiliyor.
        val olculemez = SessionStateReducer.reduceStreamSnapshot(
            state,
            emptyCodexSnapshot().copy(contextPctPresent = true, contextPct = null),
        )
        assertEquals(null, olculemez.opencode.contextPct)
    }

    // CHECKPOINT GERİ SARMA ŞERİDİ — soket yolu. Pano ile aynı gerekçe: alan
    // iki taşımadan da (WS snapshot ve delta setMeta) geçiyor ve üç-durumlu.
    @Test
    fun geriSarmaSeridiYalnizOpencodeAktifkenYazilir() {
        val durum = OpencodeRevertState(messageID = "msg_2", filesReverted = true, files = 3)
        val meta = emptyCodexSnapshot().copy(revertedPresent = true, reverted = durum)

        val opencode = RemoteUiState(backend = "opencode2-app")
        assertEquals(durum, SessionStateReducer.reduceStreamSnapshot(opencode, meta).opencode.reverted)

        // Cowork + opencode aynı oturum: şerit oraya da yazılmalı.
        val cowork = RemoteUiState(backend = "cowork", cowork = CoworkUiState(provider = "opencode2-app"))
        assertEquals(durum, SessionStateReducer.reduceStreamSnapshot(cowork, meta).opencode.reverted)

        // Başka backend'in geç gelen karesi opencode şeridini EZMEZ.
        val claude = RemoteUiState(backend = "claude-app", opencode = OpencodeUiState(reverted = durum))
        val korundu = SessionStateReducer.reduceStreamSnapshot(
            claude,
            emptyCodexSnapshot().copy(revertedPresent = true, reverted = null),
        )
        assertEquals(durum, korundu.opencode.reverted)
    }

    @Test
    fun geriSarmaSeridiAlanYokkenDurur_alanNullGelinceDuser() {
        val durum = OpencodeRevertState(messageID = "msg_2")
        val state = RemoteUiState(backend = "opencode2-app", opencode = OpencodeUiState(reverted = durum))

        // Alan hiç yoksa (değişmemiş delta / eski köprü) şerit olduğu gibi kalır.
        assertEquals(durum, SessionStateReducer.reduceStreamSnapshot(state, emptyCodexSnapshot()).opencode.reverted)

        // Alan geldi ve null: yeni tur geri almayı kalıcı kıldı — şerit inmeli.
        val dustu = SessionStateReducer.reduceStreamSnapshot(
            state,
            emptyCodexSnapshot().copy(revertedPresent = true, reverted = null),
        )
        assertEquals(null, dustu.opencode.reverted)
    }

    // PAYLAŞIM LİNKİ — soket yolu. Şeritle aynı gerekçe ve aynı üç-durum:
    // alan hiç yoksa dokunma (değişmemiş delta / eski köprü), "" gelirse yayın
    // kalkmış demek. Bu alan menüde "Paylaşımı kaldır" satırını yaşatıyor;
    // okuma dalı unutulsaydı satır ilk kareye çivilenirdi.
    @Test
    fun paylasimLinkiYalnizOpencodeAktifkenYazilir() {
        val meta = emptyCodexSnapshot().copy(share = "https://opncd.ai/share/A1")

        val opencode = RemoteUiState(backend = "opencode2-app")
        assertEquals("https://opncd.ai/share/A1", SessionStateReducer.reduceStreamSnapshot(opencode, meta).opencode.share)

        val cowork = RemoteUiState(backend = "cowork", cowork = CoworkUiState(provider = "opencode2-app"))
        assertEquals("https://opncd.ai/share/A1", SessionStateReducer.reduceStreamSnapshot(cowork, meta).opencode.share)

        // Başka backend'in geç gelen karesi opencode linkini EZMEZ.
        val claude = RemoteUiState(backend = "claude-app", opencode = OpencodeUiState(share = "https://opncd.ai/share/A1"))
        val korundu = SessionStateReducer.reduceStreamSnapshot(claude, emptyCodexSnapshot().copy(share = ""))
        assertEquals("https://opncd.ai/share/A1", korundu.opencode.share)
    }

    @Test
    fun paylasimLinkiAlanYokkenDurur_bosGelinceDuser() {
        val state = RemoteUiState(backend = "opencode2-app", opencode = OpencodeUiState(share = "https://opncd.ai/share/A1"))

        // Alan hiç yoksa link olduğu gibi kalır.
        assertEquals("https://opncd.ai/share/A1", SessionStateReducer.reduceStreamSnapshot(state, emptyCodexSnapshot()).opencode.share)

        // Alan geldi ve boş: yayın kalkmış (başka cihazdan ya da TUI'den).
        assertEquals("", SessionStateReducer.reduceStreamSnapshot(state, emptyCodexSnapshot().copy(share = "")).opencode.share)
    }

    // ÇÖZÜMLENEN AJAN — soket yolu. Oto kipte turu kimin koşacağı köprüde
    // kararlaşıyor; okuma dalı unutulsaydı çip sonsuza dek "otomatik" derdi.
    @Test
    fun cozumlenenAjanYalnizOpencodeAktifkenYazilir() {
        val meta = emptyCodexSnapshot().copy(resolvedAgent = "yerel")

        val opencode = RemoteUiState(backend = "opencode2-app")
        assertEquals("yerel", SessionStateReducer.reduceStreamSnapshot(opencode, meta).opencode.resolvedAgent)

        val cowork = RemoteUiState(backend = "cowork", cowork = CoworkUiState(provider = "opencode2-app"))
        assertEquals("yerel", SessionStateReducer.reduceStreamSnapshot(cowork, meta).opencode.resolvedAgent)

        // Başka backend'in geç gelen karesi opencode göstergesini EZMEZ.
        val claude = RemoteUiState(backend = "claude-app", opencode = OpencodeUiState(resolvedAgent = "yerel"))
        val korundu = SessionStateReducer.reduceStreamSnapshot(claude, emptyCodexSnapshot().copy(resolvedAgent = ""))
        assertEquals("yerel", korundu.opencode.resolvedAgent)
    }

    @Test
    fun cozumlenenAjanAlanYokkenDurur_bosGelinceDuser() {
        val state = RemoteUiState(backend = "opencode2-app", opencode = OpencodeUiState(resolvedAgent = "yerel"))

        // Alan hiç yoksa (değişmemiş delta / eski köprü) değer olduğu gibi kalır.
        assertEquals("yerel", SessionStateReducer.reduceStreamSnapshot(state, emptyCodexSnapshot()).opencode.resolvedAgent)

        // Alan geldi ve boş: köprü artık bilmiyor — çip "otomatik"e dönmeli.
        assertEquals("", SessionStateReducer.reduceStreamSnapshot(state, emptyCodexSnapshot().copy(resolvedAgent = "")).opencode.resolvedAgent)
    }

    // ALT AJAN KARTLARI — soket yolu. Pano/şeritle aynı gerekçe: alan iki
    // taşımadan da (WS snapshot ve delta setMeta) geçiyor ve üç-durumlu.
    @Test
    fun altAjanlarYalnizOpencodeAktifkenYazilir() {
        val liste = listOf(
            OpencodeSubagent(id = "ses_a", title = "dosya sayimi", status = "running", lastText = "Dizini okuyorum"),
            OpencodeSubagent(id = "ses_b", title = "selam metni", status = "idle", lastText = "merhaba"),
        )
        val meta = emptyCodexSnapshot().copy(subagents = liste)

        val opencode = RemoteUiState(backend = "opencode2-app")
        assertEquals(liste, SessionStateReducer.reduceStreamSnapshot(opencode, meta).opencode.subagents)

        // Cowork + opencode aynı oturum: kartlar oraya da yazılmalı.
        val cowork = RemoteUiState(backend = "cowork", cowork = CoworkUiState(provider = "opencode2-app"))
        assertEquals(liste, SessionStateReducer.reduceStreamSnapshot(cowork, meta).opencode.subagents)

        // Başka backend'in geç gelen karesi kart yığınını SÜPÜRMEZ.
        val claude = RemoteUiState(backend = "claude-app", opencode = OpencodeUiState(subagents = liste))
        val korundu = SessionStateReducer.reduceStreamSnapshot(claude, meta.copy(subagents = emptyList()))
        assertEquals(liste, korundu.opencode.subagents)
    }

    @Test
    fun altAjanlarAlanYokkenDurur_bosListeGercekTemizlik() {
        val liste = listOf(OpencodeSubagent(id = "ses_a", title = "tek", status = "idle"))
        val state = RemoteUiState(backend = "opencode2-app", opencode = OpencodeUiState(subagents = liste))

        // Alan hiç yoksa (değişmemiş delta / eski köprü) yığın olduğu gibi kalır.
        assertEquals(liste, SessionStateReducer.reduceStreamSnapshot(state, emptyCodexSnapshot()).opencode.subagents)

        // Boş liste GERÇEK bilgi: oturum değişti ya da köprü yığını temizledi.
        val temizlendi = SessionStateReducer.reduceStreamSnapshot(
            state,
            emptyCodexSnapshot().copy(subagents = emptyList()),
        )
        assertTrue(temizlendi.opencode.subagents.isEmpty())
    }
}
