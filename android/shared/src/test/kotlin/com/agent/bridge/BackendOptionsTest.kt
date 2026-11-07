package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendOptionsTest {

    @Test
    fun testClaudeAppModelOptions() {
        val state = RemoteUiState().copy(
            claudeModels = listOf(ClaudeModel("Opus", "opus-4.8"))
        )
        val options = state.backendModelOptions("claude-app")
        assertEquals(1, options.size)
        assertEquals("opus-4.8", options[0].id)
        assertEquals("Opus", options[0].label)
    }

    @Test
    fun testClaudeAppPermissionModeOptionsWithNullInfo() {
        val state = RemoteUiState().copy(
            claudeAppInfo = null
        )
        val options = state.backendPermissionModeOptions("claude-app")
        assertEquals(5, options.size)
        assertEquals("", options[0].id)
        assertEquals("Normal", options[0].label)
        assertEquals("auto", options[1].id)
        assertEquals("Otomatik", options[1].label)
        assertEquals("plan", options[2].id)
        assertEquals("Plan", options[2].label)
        assertEquals("acceptEdits", options[3].id)
        assertEquals("Düzenlemeleri otomatik kabul", options[3].label)
        assertEquals("bypassPermissions", options[4].id)
        assertEquals("İzinleri atla", options[4].label)
    }

    @Test
    fun testCoworkModelOptionsDelegation() {
        val state = RemoteUiState().copy(
            cowork = CoworkUiState(provider = "codex-app"),
            codexModels = listOf(CodexModel("Terra", "gpt-5.6-terra"))
        )
        val options = state.backendModelOptions("cowork")
        assertEquals(1, options.size)
        assertEquals("gpt-5.6-terra", options[0].id)
        assertEquals("Terra", options[0].label)
    }

    @Test
    fun testAgyOptionsEmpty() {
        val state = RemoteUiState()
        val efforts = state.backendEffortOptions("agy")
        val modes = state.backendPermissionModeOptions("agy")
        assertTrue(efforts.isEmpty())
        assertTrue(modes.isEmpty())
    }

    @Test
    fun testEffortSupportDoesNotDependOnOptionsBeingLoaded() {
        val emptyState = RemoteUiState()
        assertTrue(emptyState.backendEffortSupported("claude-app"))
        assertTrue(emptyState.backendEffortSupported("codex-app"))
        assertTrue(
            emptyState.copy(cowork = CoworkUiState(provider = "codex-app"))
                .backendEffortSupported("cowork")
        )
        assertTrue(
            emptyState.copy(cowork = CoworkUiState(provider = "claude-app"))
                .backendEffortSupported("cowork")
        )
        assertEquals("", emptyState.backendEffortOptions("claude-app").first().id)
        assertEquals("", emptyState.backendEffortOptions("codex-app").first().id)
    }

    @Test
    fun testCodexEffortsAreModelAwareAndShowResolvedDefault() {
        val state = RemoteUiState().copy(
            codexAppModel = "gpt-5.6-sol",
            codexAppEfforts = listOf("minimal", "low", "medium", "high"),
            codexAppEffortsByModel = mapOf("gpt-5.6-sol" to listOf("low", "medium", "high", "xhigh")),
            codexAppModelDefaultEfforts = mapOf("gpt-5.6-sol" to "medium"),
        )
        val options = state.backendEffortOptions("codex-app")
        assertEquals(listOf("", "low", "medium", "high", "xhigh"), options.map { it.id })
        assertEquals("default · medium", options.first().label)
        assertEquals("default·medium", state.backendEffortLabel("codex-app"))
    }

    // OMP'de kademeler modele bagli: statik 7'li liste modelin desteklemedigi
    // seviyeleri gosteriyordu. Varsayilanin neye cozuldugu de yaninda yazmali.
    @Test
    fun testOmpEffortsAreModelAwareAndShowResolvedDefault() {
        val state = RemoteUiState().copy(
            omp = OmpUiState(
                model = "deepseek/deepseek-flash",
                availableModels = listOf(
                    BackendModel("deepseek-flash", "deepseek/deepseek-flash", listOf("off", "low", "high", "max"), "high"),
                    BackendModel("Qwen3 Max", "qwen/qwen3-max", emptyList(), ""),
                ),
            )
        )
        val options = state.backendEffortOptions("omp")
        assertEquals(listOf("", "off", "low", "high", "max"), options.map { it.id })
        assertEquals("varsayılan · high", options.first().label)
        assertEquals("varsayılan·high", state.backendEffortLabel("omp"))
        assertTrue(state.backendEffortSupported("omp"))
    }

    // Reasoning'i olmayan modelde efor cipi hic cizilmemeli.
    @Test
    fun testOmpEffortUnsupportedForNonReasoningModel() {
        val state = RemoteUiState().copy(
            omp = OmpUiState(
                model = "qwen/qwen3-max",
                availableModels = listOf(BackendModel("Qwen3 Max", "qwen/qwen3-max", emptyList(), "")),
            )
        )
        assertTrue(state.backendEffortOptions("omp").drop(1).isEmpty())
        assertTrue(!state.backendEffortSupported("omp"))
    }

    @Test
    fun testUnknownBackendOptionsEmpty() {
        val state = RemoteUiState()
        val models = state.backendModelOptions("unknown-backend")
        val modes = state.backendPermissionModeOptions("unknown-backend")
        val efforts = state.backendEffortOptions("unknown-backend")
        assertTrue(models.isEmpty())
        assertTrue(modes.isEmpty())
        assertTrue(efforts.isEmpty())
    }

    @Test
    fun testOmpSkillOptionsUseNativeInfo() {
        val state = RemoteUiState().copy(
            omp = OmpUiState(info = OmpAppInfo(
                skills = listOf("bro"),
                skillDetails = listOf(SkillInfo("bro", "Basitleştir")),
            )),
        )
        val options = state.backendSkillOptions("omp")
        assertEquals(listOf("bro"), options.map { it.id })
        assertEquals("Basitleştir", options.single().detail)
        // Liste dolsa bile çip gate'e takılırsa mobilde hiç görünmez: ikisi birlikte.
        assertTrue(state.backendSkillsSupported("omp"))
    }

    // "Değişiklikler" menü satırı: yalnız diff ucu olan backend'de. Köprü
    // kontratında da öyle (backend-contract.mjs sessionDiff: codex-app). Cowork
    // dalı skill dallarıyla AYNI olmalı, yoksa satır çizilip dokununca hiçbir
    // şey yapmayan bir sheet açılırdı.
    @Test
    fun testDiffViewOnlyForCodex() {
        val state = RemoteUiState()
        assertTrue(state.backendDiffSupported("codex-app"))
        assertTrue(!state.backendDiffSupported("claude-app"))
        assertTrue(!state.backendDiffSupported("omp"))
        assertTrue(!state.backendDiffSupported("agy"))
        assertTrue(!state.copy(cowork = CoworkUiState(provider = "claude-app")).backendDiffSupported("cowork"))
        assertTrue(state.copy(cowork = CoworkUiState(provider = "codex-app")).backendDiffSupported("cowork"))
    }

    // Ekranın OKUDUĞU kutu backend'e göre seçilmeli. Bu ayrışırsa satır görünür,
    // sheet açılır ve sonsuza dek boş kalır — codex'in "Değişiklikler" yolu tam
    // olarak böyle yarım kalmıştı (veri geliyordu, hiçbir arayüz okumuyordu).
    @Test
    fun testDiffKutusuBackendeGoreSecilir() {
        val cxDiff = BackendSessionDiff(files = listOf(BackendDiffFile("cx.ts")))
        val state = RemoteUiState(codex = CodexUiState(diff = cxDiff, diffLoading = true))
        assertEquals(cxDiff, state.backendDiff("codex-app"))
        assertEquals(null, state.backendDiff("claude-app"))
        assertTrue(state.backendDiffLoading("codex-app"))
        assertTrue(!state.backendDiffLoading("claude-app"))
        // Cowork sunum katmanı: kutu seçili sağlayıcınınki.
        assertEquals(cxDiff, state.copy(cowork = CoworkUiState(provider = "codex-app")).backendDiff("cowork"))
        assertEquals(null, state.copy(cowork = CoworkUiState(provider = "claude-app")).backendDiff("cowork"))
    }

    // Öneri şeridinin kaynağı her backend'de statik istem şablonları (seçilince
    // composer'a yazılır).
    @Test
    fun testSlashSuggestionsSource() {
        val statik = listOf(SlashCommand("sablon", "istem"))
        val state = RemoteUiState(slashCommands = statik)
        assertEquals(listOf("sablon"), state.backendSlashSuggestions("claude-app").map { it.name })
        assertEquals(listOf("sablon"), state.backendSlashSuggestions("omp").map { it.name })
        assertEquals(listOf("sablon"), state.copy(cowork = CoworkUiState(provider = "codex-app")).backendSlashSuggestions("cowork").map { it.name })
    }
}
