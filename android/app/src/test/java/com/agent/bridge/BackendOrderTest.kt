package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backend siralama listelerinin bekcisi.
 *
 * 30.09.2026: toplu kimlik donusumu `defaultOrder` ve `visible_backends`
 * gocunde AYNI kimligi iki kez birakti. Kotlin bunu hata saymiyor; telefonda
 * "Yeni oturum" ekraninda ayni backend'in IKI cipi cizildi (kullanici
 * bildirdi, ekran goruntusu). Bu testler tekrar eden kimligi ve katalog disi
 * kimligi yakalar.
 */
class BackendOrderTest {
    // 24.10.2026: Codex lite'tan çıktı, tam sürümde kalıyor.
    @Test
    fun liteYalnizClaudeSunar() {
        assertEquals(listOf("claude-app"), BACKEND_LITE_ORDER)
        assertTrue("codex-app" in BACKEND_DEFAULT_ORDER)
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

    // Sökülmüş bir backend kimliği hiçbir listede kalmamalı, yoksa ölü bir çip
    // çizilir: kimlik ne sırada, ne etiketlerde, ne enum'da, ne UI kataloğunda.
    @Test
    fun herListeKimligiAyniKumedenGelir() {
        val katalog = BACKEND_CATALOG.map { it.id }.toSet()
        assertEquals(katalog, Backend.entries.map { it.id }.toSet())
        assertEquals(katalog, BACKEND_DEFAULT_ORDER.toSet())
        assertEquals(katalog, BACKEND_LABELS.keys.toSet())
        assertEquals(katalog, BACKEND_SHORT_LABELS.keys.toSet())
    }
}
