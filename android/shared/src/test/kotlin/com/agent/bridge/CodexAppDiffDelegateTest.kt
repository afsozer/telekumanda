package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex'in "Değişiklikler" yolu — köprüden ekrana.
 *
 * Bu yol aylarca YARIM durdu: köprü ucu, istemci çağrısı ve delege metodu
 * vardı ama hiçbir arayüz çağırmıyordu. [OpencodeAppActionsDelegateTest]'in
 * diff testleriyle aynı davranış sözleşmesi burada da kilitleniyor, çünkü
 * telefonda ikisini de AYNI Compose gövdesi çiziyor: bir backend'de "hata
 * anında liste korunur" olup diğerinde olmaması, aynı ekranın sekmeye göre
 * farklı davranması demek olurdu.
 */
class CodexAppDiffDelegateTest {

    private fun delege(
        client: BridgeClient,
        state: () -> RemoteUiState,
        hataYut: Boolean = false,
        update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    ) = CodexAppActionsDelegate(
        client = client,
        scope = CoroutineScope(Dispatchers.Unconfined),
        state = state,
        update = update,
        emit = {},
        reportError = { _, e -> if (!hataYut) throw e },
        clearThoughts = {},
        closeSocket = {},
        openSocket = {},
        startPolling = {},
        refreshConversation = {},
        loadDiskSessions = {},
    )

    @Test
    fun diffOpencodeIleAyniSemadanOkunur() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = {
                JSONObject(
                    """{"ok":true,"turns":2,"additions":3,"deletions":1,"truncated":false,
                       "files":[
                         {"path":"src/a.ts","additions":2,"deletions":1,"status":"modified","patch":"@@\n-a\n+b\n+c\n"},
                         {"path":"yeni.md","additions":1,"deletions":0,"status":"added","patch":"+selam\n","truncated":true}
                       ]}""",
                )
            }
        }
        var state = RemoteUiState(codex = CodexUiState(sessionId = "cx-1"))
        delege(client, { state }) { reducer -> state = reducer(state) }.loadDiff()

        // Uç codex'in kendi ucu; şema opencode'unkiyle aynı olduğu için
        // ayrıştırıcı ortak.
        assertEquals("/codex-app/diff?session=cx-1", client.recordedRequests.single().path)
        val diff = state.codex.diff!!
        assertEquals(listOf("src/a.ts", "yeni.md"), diff.files.map { it.path })
        assertEquals(2, diff.turns)
        assertEquals(3, diff.additions)
        assertEquals("added", diff.files[1].status)
        assertTrue(diff.files[1].truncated)
        assertEquals(false, state.codex.diffLoading)
    }

    // Dosya değiştirmeyen oturum HATA değil: boş kayıt yazılmalı ki arayüz "hiç
    // istenmedi" (null) ile "gerçekten değişmedi" (boş liste) ayrımını yapabilsin.
    @Test
    fun bosDiffBosKayitOlarakYazilir() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { JSONObject("""{"ok":true,"turns":0,"files":[]}""") }
        }
        var state = RemoteUiState(codex = CodexUiState(sessionId = "cx-1"))
        delege(client, { state }) { reducer -> state = reducer(state) }.loadDiff()

        assertEquals(emptyList<BackendDiffFile>(), state.codex.diff?.files)
        assertEquals(false, state.codex.diffLoading)
    }

    // Boş liste + historyGap = "bilmiyorum", "değişmedi" DEĞİL. Bayrak yolda
    // düşerse arayüz yanlış cümleyi kurar; taşındığı burada kilitleniyor.
    @Test
    fun gecmisBoslugunuBildirenYukBayragiTasir() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { JSONObject("""{"ok":true,"files":[],"historyGap":true}""") }
        }
        var state = RemoteUiState(codex = CodexUiState(sessionId = "cx-1"))
        delege(client, { state }) { reducer -> state = reducer(state) }.loadDiff()

        assertTrue(state.codex.diff!!.historyGap)
    }

    @Test
    fun oturumYokkenIstenmez() {
        val client = FakeBridgeClient()
        var state = RemoteUiState(codex = CodexUiState(sessionId = ""))
        delege(client, { state }) { reducer -> state = reducer(state) }.loadDiff()

        assertEquals(emptyList<String>(), client.recordedRequests.map { it.path })
        assertEquals(null, state.codex.diff)
    }

    // Hata anında ÖNCEKİ liste korunur: boşaltmak "bu oturum dosya değiştirmedi"
    // yalanına dönüşürdü. Yükleniyor bayrağı yine de düşmeli.
    @Test
    fun hataDurumundaOncekiListeKorunur() {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { JSONObject().put("ok", false).put("error", "oturum yok") }
        }
        val eski = BackendSessionDiff(files = listOf(BackendDiffFile("eski.ts", 1, 0, "modified")))
        var state = RemoteUiState(codex = CodexUiState(sessionId = "cx-1", diff = eski))
        delege(client, { state }, hataYut = true) { reducer -> state = reducer(state) }.loadDiff()

        assertEquals(listOf("eski.ts"), state.codex.diff?.files?.map { it.path })
        assertEquals(false, state.codex.diffLoading)
    }
}
