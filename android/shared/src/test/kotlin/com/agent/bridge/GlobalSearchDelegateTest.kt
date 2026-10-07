package com.agent.bridge

import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spotlight araması — iptalin hata sayılmaması.
 *
 * Canlı hata (20.08.2026): kullanıcı "yakup" yazdı, palet sonuç yerine
 * "StandaloneCoroutine was cancelled" gösterdi ve bir daha hiç sonuç vermedi.
 * Her tuş vuruşu önceki işi `cancel()` ediyor, `runCatching` iptali de yakalıyor
 * ve iptal mesajını `error` alanına yazıyordu — üstelik YENİ aramanın taze
 * durumunun üstüne. Spotlight `error` dalını sonuç dalından önce çizdiği için
 * arama tamamen bozulmuş görünüyordu.
 *
 * Testler tek iş parçacıklı `runBlocking` içinde: delege çağrıları da bekleyişler
 * de aynı sıradan akar, yarış yok.
 */
class GlobalSearchDelegateTest {

    /** İstek `kapi` açılana kadar asılı kalır — iptali tam o noktada yakalayabilelim. */
    private class AsiliIstemci(
        private val kapi: CompletableDeferred<Unit>,
        private val cevap: JSONObject = JSONObject("""{"ok":true,"hits":[]}"""),
    ) : FakeBridgeClient() {
        override suspend fun getJson(settings: BridgeSettings, path: String): JSONObject {
            kapi.await()
            return cevap
        }
    }

    private class HataliIstemci : FakeBridgeClient() {
        override suspend fun getJson(settings: BridgeSettings, path: String): JSONObject =
            JSONObject("""{"ok":false,"error":"köprü kapalı"}""")
    }

    @Test
    fun `tus vurusunda iptal edilen arama hata yazmaz`() = runBlocking {
        var durum = RemoteUiState()
        val kapi = CompletableDeferred<Unit>()
        val delege = GlobalSearchDelegate(
            client = AsiliIstemci(kapi),
            scope = this,
            state = { durum },
            update = { degistir -> durum = degistir(durum) },
            reportError = { _, _ -> },
        )

        delege.updateQuery("yak")
        // Debounce (350ms) geçsin, istek asılı kalsın.
        delay(500)
        // İkinci tuş: birinci iş TAM İSTEĞİN İÇİNDEYKEN iptal edilir.
        delege.updateQuery("yakup")
        delay(100)
        kapi.complete(Unit)
        delay(600)

        assertEquals("", durum.search.error)
        assertEquals("yakup", durum.search.query)
        assertTrue(!durum.search.loading)
    }

    @Test
    fun `basarili cevap onceki hatayi siler`() = runBlocking {
        var durum = RemoteUiState()
        val hatali = GlobalSearchDelegate(
            client = HataliIstemci(),
            scope = this,
            state = { durum },
            update = { degistir -> durum = degistir(durum) },
            reportError = { _, _ -> },
        )
        hatali.updateQuery("yakup")
        delay(600)
        assertTrue("hata gösterilmeliydi", durum.search.error.isNotBlank())

        val kapi = CompletableDeferred(Unit)
        val saglam = GlobalSearchDelegate(
            client = AsiliIstemci(kapi),
            scope = this,
            state = { durum },
            update = { degistir -> durum = degistir(durum) },
            reportError = { _, _ -> },
        )
        saglam.updateQuery("yakup2")
        delay(600)
        assertEquals("", durum.search.error)
    }

    @Test
    fun `kisa sorgu aramayi hic baslatmaz`() = runBlocking {
        var durum = RemoteUiState()
        val kapi = CompletableDeferred(Unit)
        val istemci = AsiliIstemci(kapi)
        val delege = GlobalSearchDelegate(
            client = istemci,
            scope = this,
            state = { durum },
            update = { degistir -> durum = degistir(durum) },
            reportError = { _, _ -> },
        )
        delege.updateQuery("y")
        delay(600)
        assertTrue(istemci.recordedRequests.isEmpty())
        assertTrue(!durum.search.loading)
    }
}
