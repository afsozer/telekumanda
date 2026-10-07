package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backend siralama listelerinin bekcisi.
 *
 * 30.09.2026: v1 sokulurken "opencode-app" -> "opencode2-app" toplu donusumu
 * `defaultOrder` ve `visible_backends` gocunde AYNI kimligi iki kez birakti.
 * Kotlin bunu hata saymiyor; telefonda "Yeni oturum" ekraninda IKI tane
 * "OpenCode" cipi cizildi (kullanici bildirdi, ekran goruntusu).
 * Bu testler tekrar eden kimligi ve katalog disi kimligi yakalar.
 */
class BackendOrderTest {
    @Test
    fun liteYalnizUcSaglayiciSunarNormalOpencodeKorunur() {
        assertEquals(listOf("claude-app", "codex-app", "agy"), BACKEND_LITE_ORDER)
        assertTrue("opencode2-app" in BACKEND_DEFAULT_ORDER)
    }

    @Test
    fun varsayilanSiraMukerrerKimlikIcermez() {
        assertEquals(
            "defaultOrder'da tekrar eden backend kimligi var",
            BACKEND_DEFAULT_ORDER.size,
            BACKEND_DEFAULT_ORDER.distinct().size,
        )
        assertEquals(
            "liteOrder'da tekrar eden backend kimligi var",
            BACKEND_LITE_ORDER.size,
            BACKEND_LITE_ORDER.distinct().size,
        )
    }

    @Test
    fun varsayilanSiradakiHerKimlikGercekBirBackend() {
        for (id in BACKEND_DEFAULT_ORDER + BACKEND_LITE_ORDER) {
            assertTrue("katalogda olmayan backend kimligi: $id", Backend.from(id) != null)
        }
    }

    // v1 sokuldu: kimlik hicbir listede kalmamali, yoksa olu bir cip cizilir.
    @Test
    fun v1KimligiHicbirListedeYok() {
        assertTrue("opencode-app" !in BACKEND_DEFAULT_ORDER)
        assertTrue("opencode-app" !in BACKEND_LITE_ORDER)
        assertTrue("opencode-app" !in BACKEND_LABELS.keys)
        assertTrue("opencode-app" !in BACKEND_SHORT_LABELS.keys)
        assertTrue(Backend.from("opencode-app") == null)
    }

    // Arayuz katalogu (ikonlu cip listesi) da tek OpenCode girisi gostermeli.
    @Test
    fun uiKatalogundaTekOpencode() {
        val opencodeGirisleri = BACKEND_CATALOG.filter { it.id.startsWith("opencode") }
        assertEquals("UI katalogunda birden fazla OpenCode girisi var", 1, opencodeGirisleri.size)
        assertEquals("opencode2-app", opencodeGirisleri.single().id)
        assertEquals("OpenCode", opencodeGirisleri.single().label)
    }
}
