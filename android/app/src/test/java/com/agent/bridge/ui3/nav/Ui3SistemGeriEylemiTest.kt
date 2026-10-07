package com.agent.bridge.ui3.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class Ui3SistemGeriEylemiTest {
    @Test
    fun `lite guncellemeden geri sohbete doner`() {
        assertEquals(
            Ui3SistemGeriEylemi.SOHBETE_DON,
            ui3SistemGeriEylemi(lite = true, sheetAcik = false, oturumlarAcik = false, liteGuncellemeAcik = true),
        )
    }

    @Test
    fun `guncellemede once ustteki katmanlar kapanir`() {
        assertEquals(
            Ui3SistemGeriEylemi.SHEET_KAPAT,
            ui3SistemGeriEylemi(lite = true, sheetAcik = true, oturumlarAcik = true, liteGuncellemeAcik = true),
        )
        assertEquals(
            Ui3SistemGeriEylemi.OTURUMLARI_KAPAT,
            ui3SistemGeriEylemi(lite = true, sheetAcik = false, oturumlarAcik = true, liteGuncellemeAcik = true),
        )
    }

    @Test
    fun `tam surumun guncelleme geri akisi degismez`() {
        assertEquals(
            Ui3SistemGeriEylemi.TAM_SURUME_BIRAK,
            ui3SistemGeriEylemi(lite = false, sheetAcik = false, oturumlarAcik = false, liteGuncellemeAcik = true),
        )
    }

    @Test
    fun `lite geri kapali oturum cekmecesini acar`() {
        assertEquals(
            Ui3SistemGeriEylemi.OTURUMLARI_AC,
            ui3SistemGeriEylemi(lite = true, sheetAcik = false, oturumlarAcik = false),
        )
    }

    @Test
    fun `lite geri acik oturum cekmecesini kapatir`() {
        assertEquals(
            Ui3SistemGeriEylemi.OTURUMLARI_KAPAT,
            ui3SistemGeriEylemi(lite = true, sheetAcik = false, oturumlarAcik = true),
        )
    }

    @Test
    fun `sheet cekmeceden once kapanir`() {
        assertEquals(
            Ui3SistemGeriEylemi.SHEET_KAPAT,
            ui3SistemGeriEylemi(lite = true, sheetAcik = true, oturumlarAcik = true),
        )
    }

    @Test
    fun `tam surum bos katmanda mevcut geri akisina birakir`() {
        assertEquals(
            Ui3SistemGeriEylemi.TAM_SURUME_BIRAK,
            ui3SistemGeriEylemi(lite = false, sheetAcik = false, oturumlarAcik = false),
        )
    }
}
