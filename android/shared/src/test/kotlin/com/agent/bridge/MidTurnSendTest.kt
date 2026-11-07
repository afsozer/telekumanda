package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tur sürerken gönderim kipinin BACKEND ADINA GÖMÜLÜ olmadığını kilitler.
 *
 * 25.08.2026'ya kadar iki ekran da `backend == "omp" || backend == "hermes-app"`
 * yazıyordu; backend'in kuyruk desteği köprüde varken telefonda hiç çıkmadı.
 * Buradaki testler kipi capability'den türetiyor.
 */
class MidTurnSendTest {

    private fun durum(backend: String, coworkProvider: String = "") = RemoteUiState(
        backend = backend,
        cowork = CoworkUiState(provider = coworkProvider),
    )

    // Tanınmayan bir kimlik ortak tur-içi kipe girmemeli.
    @Test
    fun taninmayanBackendOrtakKipeGirmez() {
        assertFalse(durum("taninmayan-saglayici").midTurnQueueSupported())
        assertFalse(durum("taninmayan-saglayici").midTurnSteerSupported())
    }

    @Test
    fun ompIkisiniDeGosterir() {
        for (backend in listOf("omp")) {
            assertTrue(backend, durum(backend).midTurnQueueSupported())
            assertTrue(backend, durum(backend).midTurnSteerSupported())
        }
    }

    // Claude'da hiçbir tur-içi uç yok; Codex'te /steer var ama /follow-up yok ve
    // telefonda kendi eski steer kipi yolunu kullanıyor — ortak kipe girmemeli.
    @Test
    fun claudeVeCodexOrtakKipeGirmez() {
        assertFalse(durum("claude-app").midTurnQueueSupported())
        assertFalse(durum("claude-app").midTurnSteerSupported())
        assertFalse(durum("codex-app").midTurnQueueSupported())
    }

    @Test
    fun coworkYeteneginiSaglayicidanAlir() {
        assertTrue(durum("cowork", "omp").midTurnQueueSupported())
        assertTrue(durum("cowork", "omp").midTurnSteerSupported())
        assertFalse(durum("cowork", "claude-app").midTurnQueueSupported())
    }

    // Dağıtım cowork sağlayıcısını AÇMALI; tanınmayan sağlayıcı cowork'te
    // Claude'a normalleşir.
    @Test
    fun dagitimCoworkSaglayicisiniCozer() {
        assertEquals("claude-app", midTurnSendBackend("cowork", "taninmayan-saglayici"))
        assertEquals("omp", midTurnSendBackend("cowork", "omp"))
        assertEquals("claude-app", midTurnSendBackend("cowork", ""))
        assertEquals("omp", midTurnSendBackend("omp", "codex-app"))
        assertEquals("", midTurnSendBackend(null, ""))
    }

    // Köprü kataloğu TEK KAYNAK: gömülü enum yalnız çevrimdışı yedek. Köprü
    // "steer yok" derse telefon yönlendir tuşunu çizmemeli.
    @Test
    fun kopruKatalogueGomuluEnumuEzer() {
        val katalog = BackendCatalogInfo(
            contractVersion = 1,
            entries = mapOf(
                "omp" to BackendCapabilities(userInputSteer = false, userInputQueue = true),
            ),
        )
        val s = durum("omp").copy(backendCatalog = katalog)
        assertTrue(s.midTurnQueueSupported())
        assertFalse(s.midTurnSteerSupported())
    }
}
