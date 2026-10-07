package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendOptionsTest {

    // 26.09.2026: opencode2-app dallari BackendOptions'ta hic yoktu; MODEL SEC
    // sheet'i bos listeyi "yukleniyor" sayip sonsuza dek doniyordu (kullanici
    // bildirdi). Bu iki test o dallarin varligini kilitler.
    // 30.09.2026: v1 sokuldu, iki durum ailesi teke indi — testler "kendi
    // ailesinden okuyor mu" yerine "OpenCode ailesinden okuyor mu" diyor.
    @Test
    fun testOpencodeModelOptionsAilesindenGelir() {
        val state = RemoteUiState().copy(
            opencode = OpencodeUiState(availableModels = listOf(BackendModel("Model", "saglayici/model"))),
        )
        assertEquals(listOf("saglayici/model"), state.backendModelOptions("opencode2-app").map { it.id })
    }

    @Test
    fun testOpencodeIzinVeAjanSecenekleriAilesinden() {
        val state = RemoteUiState().copy(
            opencode = OpencodeUiState(
                permissionModes = listOf(PermissionMode("ask", "Ask", "Her izin icin sor")),
                agents = listOf(BackendAgent("ajan")),
            ),
        )
        assertEquals(listOf("ask"), state.backendPermissionModeOptions("opencode2-app").map { it.id })
        assertTrue(state.backendAgentSupported("opencode2-app"))
        // Ilk satir her zaman "otomatik" (id bos), ardindan ailenin ajanlari.
        assertEquals(listOf("", "ajan"), state.backendAgentOptions("opencode2-app").map { it.id })
    }

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
            omp = OpencodeUiState(
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
            omp = OpencodeUiState(
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
            omp = OpencodeUiState(info = OpencodeAppInfo(
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

    @Test
    fun testOpencodeAgentOptionsStartWithAuto() {
        val state = RemoteUiState().copy(
            opencode = OpencodeUiState(agents = listOf(
                BackendAgent("build", "varsayılan", "primary", builtIn = true),
                BackendAgent("atlasjb", "jb testi", "primary", model = "deepseek/deepseek-v4-pro"),
            )),
        )
        val options = state.backendAgentOptions("opencode2-app")
        assertEquals(listOf("", "build", "atlasjb"), options.map { it.id })
        assertEquals("otomatik", options[0].label)
        // Kendi modeli olan ajanda model detayda görünür: seçince model çipi de değişecek.
        assertEquals("jb testi · model: deepseek/deepseek-v4-pro", options[2].detail)
        assertTrue(state.backendAgentSupported("opencode2-app"))
        assertEquals("otomatik", state.backendAgentLabel("opencode2-app"))
    }

    @Test
    fun testOpencodeAgentLabelShowsSelection() {
        val state = RemoteUiState().copy(opencode = OpencodeUiState(agent = "yerel"))
        assertEquals("yerel", state.backendAgentLabel("opencode2-app"))
    }

    // Oto kipte turu KÖPRÜ seçiyor ve bu karar telefonda başka hiçbir yerde
    // görünmüyordu. Çip artık kararı da yazar; köprü henüz bilmiyorsa (boş alan)
    // eski davranışa döner — tahmin uydurmaktansa yalnız "otomatik".
    @Test
    fun testOpencodeAgentLabelShowsResolvedInAutoMode() {
        val oto = RemoteUiState().copy(opencode = OpencodeUiState(resolvedAgent = "yerel"))
        assertEquals("otomatik · yerel", oto.backendAgentLabel("opencode2-app"))
        assertEquals(
            "modele göre köprü seçer · şu an: yerel",
            oto.backendAgentOptions("opencode2-app")[0].detail,
        )

        // Açık seçim varken çip seçimi yazar; sheet'teki "otomatik" satırı ise
        // "şu an" demez — oto kipte neyin koşacağını o an bilmiyoruz.
        val acik = RemoteUiState().copy(opencode = OpencodeUiState(agent = "plan", resolvedAgent = "plan"))
        assertEquals("plan", acik.backendAgentLabel("opencode2-app"))
        assertEquals("modele göre köprü seçer", acik.backendAgentOptions("opencode2-app")[0].detail)
    }

    @Test
    fun testAgentPillOnlyForOpencode() {
        val state = RemoteUiState()
        assertTrue(!state.backendAgentSupported("claude-app"))
        assertTrue(!state.backendAgentSupported("omp"))
        assertTrue(state.backendAgentOptions("codex-app").isEmpty())
        // Cowork'te yalnız sağlayıcı opencode iken.
        assertTrue(!state.copy(cowork = CoworkUiState(provider = "codex-app")).backendAgentSupported("cowork"))
        assertTrue(state.copy(cowork = CoworkUiState(provider = "opencode2-app")).backendAgentSupported("cowork"))
    }

    // "Değişiklikler" menü satırı: yalnız diff ucu olan backend'de. Köprü
    // kontratında da öyle (backend-contract.mjs sessionDiff: opencode-app +
    // codex-app). Cowork dalı ajan/skill dallarıyla AYNI olmalı — yoksa satır
    // çizilip dokununca hiçbir şey yapmayan bir sheet açılırdı.
    @Test
    fun testDiffViewForOpencodeAndCodex() {
        val state = RemoteUiState()
        assertTrue(state.backendDiffSupported("opencode2-app"))
        assertTrue(state.backendDiffSupported("codex-app"))
        assertTrue(!state.backendDiffSupported("claude-app"))
        assertTrue(!state.backendDiffSupported("omp"))
        assertTrue(!state.backendDiffSupported("agy"))
        assertTrue(!state.copy(cowork = CoworkUiState(provider = "claude-app")).backendDiffSupported("cowork"))
        assertTrue(state.copy(cowork = CoworkUiState(provider = "opencode2-app")).backendDiffSupported("cowork"))
        assertTrue(state.copy(cowork = CoworkUiState(provider = "codex-app")).backendDiffSupported("cowork"))
    }

    // Ekranın OKUDUĞU kutu backend'e göre seçilmeli. Bu ayrışırsa satır görünür,
    // sheet açılır ve sonsuza dek boş kalır — codex'in "Değişiklikler" yolu tam
    // olarak böyle yarım kalmıştı (veri geliyordu, hiçbir arayüz okumuyordu).
    @Test
    fun testDiffKutusuBackendeGoreSecilir() {
        val ocDiff = BackendSessionDiff(files = listOf(BackendDiffFile("oc.ts")))
        val cxDiff = BackendSessionDiff(files = listOf(BackendDiffFile("cx.ts")))
        val state = RemoteUiState(
            opencode = OpencodeUiState(diff = ocDiff, diffLoading = true),
            codex = CodexUiState(diff = cxDiff),
        )
        assertEquals(ocDiff, state.backendDiff("opencode2-app"))
        assertEquals(cxDiff, state.backendDiff("codex-app"))
        assertEquals(null, state.backendDiff("claude-app"))
        assertTrue(state.backendDiffLoading("opencode2-app"))
        assertTrue(!state.backendDiffLoading("codex-app"))
        // Cowork sunum katmanı: kutu seçili sağlayıcınınki.
        assertEquals(cxDiff, state.copy(cowork = CoworkUiState(provider = "codex-app")).backendDiff("cowork"))
        assertEquals(ocDiff, state.copy(cowork = CoworkUiState(provider = "opencode2-app")).backendDiff("cowork"))
        assertEquals(null, state.copy(cowork = CoworkUiState(provider = "claude-app")).backendDiff("cowork"))
    }

    // "Geri sar" menü satırı: yalnız checkpoint/revert ucu olan backend'de.
    // Köprü kontratında da öyle (sessionRevert yalnız opencode-app). "Bu mesaja
    // dön" (rewind) her yerde var ama o AYRI: görünümü sarıyor, geri alınamıyor.
    @Test
    fun testRevertOnlyForOpencode() {
        val state = RemoteUiState()
        assertTrue(state.backendRevertSupported("opencode2-app"))
        assertTrue(!state.backendRevertSupported("claude-app"))
        assertTrue(!state.backendRevertSupported("codex-app"))
        assertTrue(!state.backendRevertSupported("omp"))
        assertTrue(!state.backendRevertSupported("agy"))
        assertTrue(!state.copy(cowork = CoworkUiState(provider = "claude-app")).backendRevertSupported("cowork"))
        assertTrue(state.copy(cowork = CoworkUiState(provider = "opencode2-app")).backendRevertSupported("cowork"))
    }

    // "Paylaş" menü satırı: yalnız paylaşım ucu olan backend'de. Bu satır
    // İNTERNETE yayın yapan tek eylemi açıyor — desteklenmeyen bir backend'de
    // görünmesi, dokununca hiçbir şey yapmayan bir onay diyaloğu demek olurdu.
    // 30.09.2026: paylaşım ucu olan tek backend v1'di ve söküldü; v2 API'sinde
    // paylaşım linki yok. Yani satır artık HİÇBİR YERDE çizilmemeli.
    @Test
    fun testShareHicbirBackendde() {
        val state = RemoteUiState()
        assertTrue(!state.backendShareSupported("opencode2-app"))
        assertTrue(!state.backendShareSupported("claude-app"))
        assertTrue(!state.backendShareSupported("codex-app"))
        assertTrue(!state.backendShareSupported("omp"))
        assertTrue(!state.backendShareSupported("agy"))
        assertTrue(!state.copy(cowork = CoworkUiState(provider = "claude-app")).backendShareSupported("cowork"))
        assertTrue(!state.copy(cowork = CoworkUiState(provider = "opencode2-app")).backendShareSupported("cowork"))
    }

    // Özel komutlar ve AGENTS.md init: aynı kapı. Cowork dalı diff/revert
    // dallarıyla eş olmak ZORUNDA, yoksa yönlendirme dalı olmayan bir satır
    // çizilirdi (bu depoda tekrarlayan hata sınıfı).
    @Test
    fun testCommandsAndInitOnlyForOpencode() {
        val state = RemoteUiState()
        for (id in listOf("claude-app", "codex-app", "omp", "agy")) {
            assertTrue(!state.backendCommandsSupported(id))
            assertTrue(!state.backendAgentsInitSupported(id))
        }
        assertTrue(state.backendCommandsSupported("opencode2-app"))
        assertTrue(state.backendAgentsInitSupported("opencode2-app"))
        val coworkOpencode = state.copy(cowork = CoworkUiState(provider = "opencode2-app"))
        assertTrue(coworkOpencode.backendCommandsSupported("cowork"))
        assertTrue(coworkOpencode.backendAgentsInitSupported("cowork"))
        assertTrue(!state.copy(cowork = CoworkUiState(provider = "claude-app")).backendCommandsSupported("cowork"))
    }

    // Öneri şeridinin kaynağı: opencode'da GERÇEK komut kataloğu (seçilince
    // çalışır), diğerlerinde statik istem şablonları (seçilince yazılır).
    // opencode'da katalog boşsa statik tabloya DÜŞMEZ — o satırlar
    // çalıştırılamaz ve "seçtiğin komut koşar" sözünü bozardı.
    @Test
    fun testSlashSuggestionsSource() {
        val statik = listOf(SlashCommand("sablon", "istem"))
        val state = RemoteUiState(
            slashCommands = statik,
            opencode = OpencodeUiState(commands = listOf(SlashCommand("init", "AGENTS.md"))),
        )
        assertEquals(listOf("init"), state.backendSlashSuggestions("opencode2-app").map { it.name })
        assertEquals(listOf("sablon"), state.backendSlashSuggestions("claude-app").map { it.name })

        val katalogsuz = state.copy(opencode = OpencodeUiState(commands = emptyList()))
        assertEquals(emptyList<SlashCommand>(), katalogsuz.backendSlashSuggestions("opencode2-app"))
        assertEquals(listOf("sablon"), katalogsuz.backendSlashSuggestions("omp").map { it.name })
    }
}
