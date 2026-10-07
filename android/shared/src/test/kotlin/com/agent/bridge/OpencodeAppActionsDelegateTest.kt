package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OpencodeAppActionsDelegateTest {
    @Test
    fun v2IlkBosAjanYanitiniYenidenDener() = runBlocking {
        var calls = 0
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                assertEquals("/opencode2-app/agents", request.path)
                calls++
                JSONObject().put("agents", if (calls == 1) JSONArray() else JSONArray().put(JSONObject().put("name", "build")))
            }
        }
        var state = RemoteUiState(backend = "opencode2-app")
        val delegate = createDelegate(client, { state }, scope = this, backendId = "opencode2-app") {
            reducer -> state = reducer(state)
        }

        delegate.loadAgents().join()

        assertEquals(2, calls)
        assertEquals(listOf("build"), state.opencode.agents.map { it.name })
    }

    @Test
    fun yeniOturumSeciliIzinModuylaAcilir() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                if (request.path == "/opencode2-app/new") {
                    JSONObject().put("ok", true).put("sessionId", "oc-new")
                } else {
                    JSONObject().put("ok", true)
                }
            }
        }
        var state = RemoteUiState(opencode = OpencodeUiState(permissionMode = "ask"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.startSession("C:/repo", "runpod/runpod")

        val request = client.recordedRequests.single { it.path == "/opencode2-app/new" }
        assertEquals("ask", request.body?.optString("permissionMode"))
        assertEquals("oc-new", state.opencode.sessionId)
    }

    @Test
    fun disktekiOturumSeciliIzinModuylaDevamEder() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                when (request.path) {
                    "/opencode2-app/adopt" -> JSONObject().put("ok", true).put("sessionId", "oc-adopted")
                    "/opencode2-app/permission-mode" -> JSONObject().put("ok", true).put("permissionMode", "ask")
                    else -> JSONObject().put("ok", true)
                }
            }
        }
        var state = RemoteUiState(opencode = OpencodeUiState(permissionMode = "ask"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.resumeDiskSession(
            AppDiskSession("disk-1", "C:/repo", "Oturum", "", 1, 1L),
        )

        assertEquals(
            listOf("/opencode2-app/adopt", "/opencode2-app/permission-mode"),
            client.recordedRequests.map { it.path },
        )
        assertEquals("ask", client.recordedRequests.last().body?.optString("permissionMode"))
        assertEquals("oc-adopted", state.opencode.sessionId)
    }

    // Kirlenmiş state ile devam ederken köprüye geçersiz kip GÖNDERİLMEZ:
    // "auto" gidince adopt sonrası çağrı 400 dönüp oturumu hiç açtırmıyordu.
    @Test
    fun gecersizIzinKipiVarsayilanaDuser() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                if (request.path == "/opencode2-app/adopt") JSONObject().put("ok", true).put("sessionId", "oc-1")
                else JSONObject().put("ok", true).put("permissionMode", "yolo")
            }
        }
        var state = RemoteUiState(opencode = OpencodeUiState(permissionMode = "auto"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.resumeDiskSession(AppDiskSession("disk-1", "C:/repo", "Oturum", "", 1, 1L))

        val request = client.recordedRequests.single { it.path == "/opencode2-app/permission-mode" }
        assertEquals("yolo", request.body?.optString("permissionMode"))
        assertEquals("oc-1", state.opencode.sessionId)
    }

    @Test
    fun ajanSecimiModelCipiniDeGunceller() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = {
                JSONObject().put("ok", true).put("agent", "atlasjb").put("model", "deepseek/deepseek-v4-pro")
            }
        }
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = "oc-1", model = "deepseek/deepseek-flash"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.setAgent("atlasjb")

        val request = client.recordedRequests.single { it.path == "/opencode2-app/agent" }
        assertEquals("atlasjb", request.body?.optString("agent"))
        assertEquals("atlasjb", state.opencode.agent)
        // Ajanin kendi modeli oturuma da yazilmali; yoksa cip flash der ama tur
        // pro ile kosar (ya da tersi) — sessiz uyusmazlik.
        assertEquals("deepseek/deepseek-v4-pro", state.opencode.model)
    }

    @Test
    fun oturumYokkenAjanSecimiYerelKalir() {
        val client = FakeBridgeClient()
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = ""))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.setAgent("plan")

        assertEquals("plan", state.opencode.agent)
        assertEquals(emptyList<String>(), client.recordedRequests.map { it.path })
    }

    // Tur sürerken gönderim: OpenCode'da YALNIZ kuyruk var. /steer diye bir uç
    // YOK; interrupt=true gelse bile istek follow-up'a gitmeli, yoksa köprüde
    // olmayan bir uca 404 atardık.
    @Test
    fun turSurerkenGonderimKuyrukUcunaGider() {
        val client = FakeBridgeClient().apply { jsonResponseProvider = { JSONObject().put("ok", true) } }
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = "oc-1"), running = true)
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.sendDuringTurn("bunu da hallet", interrupt = false)

        val request = client.recordedRequests.single()
        assertEquals("/opencode2-app/follow-up", request.path)
        assertEquals("oc-1", request.body?.optString("sessionId"))
        assertEquals("bunu da hallet", request.body?.optString("text"))
    }

    // 30.09.2026: v1 sokuldu. v1'de steer ucu YOKTU ve interrupt istegi bile
    // kuyruga dusuyordu; v2'de /steer var ve delege interrupt=true'yu ORAYA
    // gonderiyor. Test o yonlendirmeyi kilitler.
    @Test
    fun yonlendirmeIstenirse_steerUcunaGider() {
        val client = FakeBridgeClient().apply { jsonResponseProvider = { JSONObject().put("ok", true) } }
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = "oc-1"), running = true)
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.sendDuringTurn("dur, şunu yap", interrupt = true)

        assertEquals(listOf("/opencode2-app/steer"), client.recordedRequests.map { it.path })
    }

    // Kullanıcı satırını KÖPRÜ ekliyor; delege state'e mesaj yazarsa satır iki
    // kez görünür (omp/hermes'te ölçülmüş ders).
    @Test
    fun turIciGonderimKonusmayaSatirEklemez() {
        val client = FakeBridgeClient().apply { jsonResponseProvider = { JSONObject().put("ok", true) } }
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = "oc-1"), running = true)
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.sendDuringTurn("sıraya", interrupt = false)

        assertEquals(emptyList<ChatMessage>(), state.messagesList)
    }

    @Test
    fun oturumYokkenTurIciGonderimUcaGitmez() {
        val client = FakeBridgeClient()
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = ""), running = true)
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.sendDuringTurn("kayıp mesaj", interrupt = false)

        assertEquals(emptyList<String>(), client.recordedRequests.map { it.path })
    }

    // ── Değişiklikler (loadDiff) ───────────────────────────────────────────

    @Test
    fun degisiklikleriYuklerVeDurumaYazar() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = {
                JSONObject(
                    """
                    {"ok":true,"turns":2,"additions":4,"deletions":1,"truncated":false,
                     "files":[
                       {"path":"src/a.ts","additions":3,"deletions":1,"status":"modified",
                        "patch":"@@ -1 +1 @@\n-a\n+b\n","truncated":false},
                       {"path":"yeni.md","additions":1,"deletions":0,"status":"added",
                        "patch":"@@ -0,0 +1 @@\n+x\n","truncated":true}
                     ]}
                    """.trimIndent(),
                )
            }
        }
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = "oc-1"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.loadDiff()

        assertEquals("/opencode2-app/diff?session=oc-1", client.recordedRequests.single().path)
        val diff = state.opencode.diff!!
        assertEquals(2, diff.turns)
        assertEquals(4, diff.additions)
        assertEquals(listOf("src/a.ts", "yeni.md"), diff.files.map { it.path })
        assertEquals("added", diff.files[1].status)
        // Kırpma bayrağı DOSYA başına taşınmalı: arayüz "yama kırpıldı" notunu
        // yalnız o dosyada çiziyor.
        assertEquals(true, diff.files[1].truncated)
        assertEquals(false, state.opencode.diffLoading)
    }

    // Dosya değiştirmeyen oturum HATA değil: boş kayıt yazılmalı ki arayüz
    // "hiç istenmedi" (null) ile "gerçekten değişmedi" (boş liste) ayrımını
    // yapabilsin ve boş durum metnini ancak ölçtükten sonra yazsın.
    @Test
    fun bosDiffBosKayitOlarakYazilir() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { JSONObject("""{"ok":true,"turns":1,"files":[]}""") }
        }
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = "oc-1"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.loadDiff()

        assertEquals(emptyList<BackendDiffFile>(), state.opencode.diff?.files)
        assertEquals(false, state.opencode.diffLoading)
    }

    @Test
    fun oturumYokkenDegisiklikIstenmez() {
        val client = FakeBridgeClient()
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = ""))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.loadDiff()

        assertEquals(emptyList<String>(), client.recordedRequests.map { it.path })
        assertEquals(null, state.opencode.diff)
    }

    // Hata anında ÖNCEKİ liste korunur: boşaltmak "bu oturum dosya değiştirmedi"
    // yalanına dönüşürdü. Yükleniyor bayrağı yine de düşmeli.
    @Test
    fun hataDurumundaOncekiListeKorunur() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { JSONObject().put("ok", false).put("error", "serve düştü") }
        }
        val eski = BackendSessionDiff(files = listOf(BackendDiffFile("eski.ts", 1, 0, "modified")))
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = "oc-1", diff = eski))
        val delegate = OpencodeAppActionsDelegate(
            client = client,
            scope = CoroutineScope(Dispatchers.Unconfined),
            state = { state },
            update = { reducer -> state = reducer(state) },
            emit = {},
            // Bu testte hata BEKLENİYOR: fırlatan varsayılan raporlayıcı testi
            // düşürürdü.
            reportError = { _, _ -> },
            clearThoughts = {},
            openSocket = {},
            startPolling = {},
            refreshConversation = {},
            syncActiveTab = { _, _, _ -> },
        )

        delegate.loadDiff()

        assertEquals(listOf("eski.ts"), state.opencode.diff?.files?.map { it.path })
        assertEquals(false, state.opencode.diffLoading)
    }

    // Oturum değişince diff DE düşer: taşınırsa önceki koşunun dosya listesi
    // yeni sohbetin "Değişiklikler"i olarak görünür ve onu yalanlayacak hiçbir
    // şey yok (liste kendiliğinden tazelenmiyor).
    @Test
    fun oturumDegisincePanoyleBirlikteDiffDeSifirlanir() {
        val dolu = OpencodeUiState(
            todos = listOf(OpencodeTodo("madde", "completed")),
            contextPct = 42,
            diff = BackendSessionDiff(files = listOf(BackendDiffFile("a.ts"))),
            diffLoading = true,
        )
        val temiz = dolu.panoSifirla()
        assertEquals(emptyList<OpencodeTodo>(), temiz.todos)
        assertEquals(null, temiz.contextPct)
        assertEquals(null, temiz.diff)
        assertEquals(false, temiz.diffLoading)
    }

    // Alt-ajan kartları da oturuma ait: taşınırsa yeni sohbetin üstünde ÖNCEKİ
    // koşunun ajanları asılı kalır ve karta dokunmak alakasız bir oturumun
    // transkriptini istemeye çalışırdı.
    @Test
    fun oturumDegisinceAltAjanKartlariVeTranskriptDeSifirlanir() {
        val dolu = OpencodeUiState(
            subagents = listOf(OpencodeSubagent(id = "ses_a", title = "dosya sayimi", status = "running")),
            subagentTranscript = OpencodeSubagentTranscript(childId = "ses_a", title = "dosya sayimi"),
            subagentTranscriptLoading = true,
        )
        val temiz = dolu.panoSifirla()
        assertEquals(emptyList<OpencodeSubagent>(), temiz.subagents)
        assertEquals(null, temiz.subagentTranscript)
        assertEquals(false, temiz.subagentTranscriptLoading)
    }

    // Çözümlenen ajan da oturuma ait: yeni oturumun modeli farklı olabilir ve
    // taşınan değer, köprünün ilk karesine kadar yanlış ajanı gösterirdi.
    @Test
    fun oturumDegisinceCozumlenenAjanDaSifirlanir() {
        assertEquals("", OpencodeUiState(resolvedAgent = "yerel").panoSifirla().resolvedAgent)
    }

    // BAYAT PENCERE %100 YALANI (canlıda görüldü). Üst-düzey contextTokens ve
    // contextWindow ayrı kanallardan geliyor: token her karede tazeleniyor,
    // pencere ancak köprü model kataloğunu ısıtınca. Yeni oturuma geçerken
    // sıfırlanmazlarsa ikisi FARKLI oturumdan kalıyor ve bölümleri tavana
    // yapışıyordu — eski oturumun 12k penceresi, yeni oturumun 44k token'ı.
    @Test
    fun yeniOturumBayatBaglamSayacinaSarkmaz() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                if (request.path == "/opencode2-app/new") {
                    JSONObject().put("ok", true).put("sessionId", "oc-yeni")
                } else {
                    JSONObject().put("ok", true)
                }
            }
        }
        // Önceki oturum: küçük pencereli yerel model, dolu sayaç.
        var state = RemoteUiState(
            contextTokens = 44_555,
            contextWindow = 12_000,
            opencode = OpencodeUiState(contextPct = 91),
        )
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.startSession("C:/repo", "runpod/runpod")

        assertEquals("oc-yeni", state.opencode.sessionId)
        assertEquals(0, state.contextTokens)
        assertEquals(0, state.contextWindow)
        // panoSifirla zinciri: köprünün yüzdesi de "henüz bilinmiyor"a döner.
        assertEquals(null, state.opencode.contextPct)
    }

    // Aynı kural disk oturumu devralırken de geçerli — orada da yeni bir
    // oturuma geçiliyor ve transkript boşalıyor.
    @Test
    fun disktenDevamEdilenOturumDaBaglamSayaciniSifirlar() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                if (request.path == "/opencode2-app/adopt") {
                    JSONObject().put("ok", true).put("sessionId", "oc-devam")
                } else {
                    JSONObject().put("ok", true)
                }
            }
        }
        var state = RemoteUiState(
            contextTokens = 44_555,
            contextWindow = 12_000,
            opencode = OpencodeUiState(contextPct = 91),
        )
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.resumeDiskSession(AppDiskSession("disk-1", "C:/repo", "Oturum", "", 1, 1L))

        assertEquals(0, state.contextTokens)
        assertEquals(0, state.contextWindow)
        assertEquals(null, state.opencode.contextPct)
    }

    // ── Checkpoint geri sarma ────────────────────────────────────────────────

    @Test
    fun geriSarmaNoktalariCekilir() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = {
                JSONObject().put("ok", true).put(
                    "items",
                    org.json.JSONArray()
                        .put(JSONObject().put("messageID", "msg_1").put("text", "bir").put("turn", 1))
                        .put(JSONObject().put("messageID", "msg_2").put("text", "iki").put("turn", 2)),
                )
            }
        }
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = "oc-1"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.loadCheckpoints()

        assertEquals(listOf("msg_1", "msg_2"), state.opencode.checkpoints.map { it.messageID })
        assertEquals(listOf(1, 2), state.opencode.checkpoints.map { it.turn })
        assertEquals(false, state.opencode.checkpointsLoading)
    }

    @Test
    fun geriSarKimligiGovdedeGider() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = {
                JSONObject().put("ok", true).put("messageID", "msg_2").put("filesReverted", true).put("files", 2)
            }
        }
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = "oc-1"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.revertTo("msg_2")

        val request = client.recordedRequests.single { it.path == "/opencode2-app/revert" }
        assertEquals("oc-1", request.body?.optString("sessionId"))
        assertEquals("msg_2", request.body?.optString("messageID"))
    }

    // Tur sürerken köprü zaten reddediyor; istek HİÇ gitmemeli ki kullanıcı
    // sebebi ("önce bitmesini bekle") yerine ham hata görmesin.
    @Test
    fun turSurerkenGeriSarilmaz() {
        val client = FakeBridgeClient().apply { jsonResponseProvider = { JSONObject().put("ok", true) } }
        var state = RemoteUiState(running = true, opencode = OpencodeUiState(sessionId = "oc-1"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.revertTo("msg_2")

        assertEquals(emptyList<String>(), client.recordedRequests.map { it.path })
    }

    @Test
    fun geriAlUcaGider() {
        val client = FakeBridgeClient().apply { jsonResponseProvider = { JSONObject().put("ok", true) } }
        var state = RemoteUiState(opencode = OpencodeUiState(sessionId = "oc-1"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.unrevert()

        val request = client.recordedRequests.single { it.path == "/opencode2-app/unrevert" }
        assertEquals("oc-1", request.body?.optString("sessionId"))
    }

    // Oturum değişince şerit ve liste DE düşer: taşınırsa "Geri Al" alakasız
    // bir oturumu bozardı (diff/panoyla aynı gerekçe).
    @Test
    fun oturumDegisinceGeriSarmaDurumuDaSifirlanir() {
        val dolu = OpencodeUiState(
            checkpoints = listOf(OpencodeCheckpoint("msg_1", "bir", 1)),
            checkpointsLoading = true,
            reverted = OpencodeRevertState("msg_1"),
        )
        val temiz = dolu.panoSifirla()
        assertEquals(emptyList<OpencodeCheckpoint>(), temiz.checkpoints)
        assertEquals(false, temiz.checkpointsLoading)
        assertEquals(null, temiz.reverted)
    }

    // ── Paylaşım / komut / AGENTS.md ────────────────────────────────────────

    @Test
    fun paylasimLinkiHemDurumaYazilirHemGeriCagriylaVerilir() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { JSONObject().put("ok", true).put("url", "https://opncd.ai/share/A1") }
        }
        var state = RemoteUiState(backend = "opencode2-app", opencode = OpencodeUiState(sessionId = "oc-1"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        var link = ""
        delegate.share { link = it }

        assertEquals("/opencode2-app/share", client.recordedRequests.single().path)
        // Durum snapshot'tan da geliyor ama paylaşım sayfası linki ANINDA
        // istiyor: iki yol da dolmalı.
        assertEquals("https://opncd.ai/share/A1", state.opencode.share)
        assertEquals("https://opncd.ai/share/A1", link)
    }

    @Test
    fun paylasimKaldirilincaDurumTemizlenir() {
        val client = FakeBridgeClient()
        var state = RemoteUiState(
            backend = "opencode2-app",
            opencode = OpencodeUiState(sessionId = "oc-1", share = "https://opncd.ai/share/A1"),
        )
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.unshare()

        assertEquals("/opencode2-app/unshare", client.recordedRequests.single().path)
        assertEquals("", state.opencode.share)
    }

    // Oturumsuzken köprüye HİÇ gidilmemeli: sessionId boşken /share çağrısı
    // başka bir oturumu paylaşmaz ama köprüde anlamsız 400 üretirdi.
    @Test
    fun oturumsuzkenPaylasimUcunaGidilmez() {
        val client = FakeBridgeClient()
        var state = RemoteUiState(backend = "opencode2-app", opencode = OpencodeUiState(sessionId = ""))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.share()
        delegate.unshare()

        assertEquals(emptyList<String>(), client.recordedRequests.map { it.path })
    }

    @Test
    fun komutListesiDurumaYazilir() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = {
                JSONObject().put("ok", true).put(
                    "commands",
                    org.json.JSONArray().put(
                        JSONObject().put("name", "init").put("desc", "AGENTS.md kurulumu").put("hint", "\$ARGUMENTS"),
                    ),
                )
            }
        }
        var state = RemoteUiState(backend = "opencode2-app", opencode = OpencodeUiState(sessionId = "oc-1"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.loadCommands()

        assertEquals("/opencode2-app/commands", client.recordedRequests.single().path)
        assertEquals(listOf("init"), state.opencode.commands.map { it.name })
    }

    @Test
    fun komutCalistirilincaGovdeGiderVeComposerBosalir() {
        val client = FakeBridgeClient()
        var state = RemoteUiState(
            backend = "opencode2-app",
            input = "/review HEAD~1",
            opencode = OpencodeUiState(sessionId = "oc-1"),
        )
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.runCommand("/review", "HEAD~1")

        val istek = client.recordedRequests.single { it.path == "/opencode2-app/command" }
        // Baştaki eğik çizgi köprüye taşınmaz.
        assertEquals("review", istek.body?.optString("command"))
        assertEquals("HEAD~1", istek.body?.optString("arguments"))
        assertEquals("", state.input)
    }

    // Tur sürerken köprü zaten reddediyor; telefon oraya hiç gitmemeli, yoksa
    // kullanıcı satırı köprüde yazılmadan hata dönerdi.
    @Test
    fun turSurerkenKomutVeInitGonderilmez() {
        val client = FakeBridgeClient()
        var state = RemoteUiState(backend = "opencode2-app", running = true, opencode = OpencodeUiState(sessionId = "oc-1"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.runCommand("init", "")
        delegate.initAgents()

        assertEquals(emptyList<String>(), client.recordedRequests.map { it.path })
    }

    @Test
    fun agentsInitUcaGider() {
        val client = FakeBridgeClient()
        var state = RemoteUiState(backend = "opencode2-app", opencode = OpencodeUiState(sessionId = "oc-1"))
        val delegate = createDelegate(client, { state }) { reducer -> state = reducer(state) }

        delegate.initAgents()

        assertEquals("/opencode2-app/init", client.recordedRequests.single().path)
    }

    // Paylaşım linki OTURUMA ait: taşınırsa yeni oturumun menüsü "Paylaşımı
    // kaldır" der ve dokunuş ÖNCEKİ oturumun yayınını indirirdi. Komut listesi
    // ise kuruluma ait, taşınması doğru.
    @Test
    fun oturumDegisincePaylasimSifirlanirKomutListesiKalir() {
        val dolu = OpencodeUiState(
            share = "https://opncd.ai/share/A1",
            commands = listOf(SlashCommand("init", "AGENTS.md")),
        )
        val temiz = dolu.panoSifirla()
        assertEquals("", temiz.share)
        assertEquals(listOf("init"), temiz.commands.map { it.name })
    }

    private fun createDelegate(
        client: FakeBridgeClient,
        state: () -> RemoteUiState,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        backendId: String = Backend.OPENCODE2_APP.id,
        update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    ) = OpencodeAppActionsDelegate(
        client = client,
        scope = scope,
        state = state,
        update = update,
        emit = {},
        reportError = { _, error -> throw error },
        clearThoughts = {},
        openSocket = {},
        startPolling = {},
        refreshConversation = {},
        syncActiveTab = { _, _, _ -> },
        backendId = backendId,
    )
}
