package com.agent.bridge.ui3.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composer şeridinin yer hesabı.
 *
 * Bu hesap bir kez elle yapılmış bir aritmetiğe dayanıyordu ve tek cihazda
 * doğruydu; saf fonksiyona çıkarılınca sayılar da bağlanabildi. Ölçüler:
 * düğme 36dp, aralık 6dp, çipin okunabilir asgarisi 60dp, çip tavanı 119dp.
 */
class Ui3SeritYerlesimiTest {
    // Dp bir float sarmalayıcısı: 317.7 - 180 - 36 tam olarak 101.7 etmiyor
    // (101.69999…). Tam eşitlik yerine tolerans.
    private fun esit(beklenen: Float, olculen: androidx.compose.ui.unit.Dp) =
        assertEquals(beklenen, olculen.value, 0.01f)

    // Magic7 Pro'da ÖLÇÜLEN composer iç genişliği (Ui3Composer'daki geometri notu).
    private val telefon = 317.7.dp

    @Test
    fun idlePhoneShowsEverythingAndCapsTheChip() {
        val y = ui3SeritYerlesimi(telefon, yonlendirVar = false, seritTam = false)

        assertTrue(y.yenile)
        assertTrue(y.kullanim)
        // 317.7 - 36*4 - 6*5 = 143.7 → tavana kırpılır.
        esit(119f, y.cipMaks)
    }

    // ASIL KAZANIM (22.08.2026): "＋" yazı kutusuna, kuyruk ⋯ menüsüne inince
    // en dar durumda bile ikisi birden kalıyor. Eskiden burada Kullanım
    // gizleniyordu, üstelik çipe 59.7dp kalıp Yenile de düşüyordu.
    @Test
    fun runningWithSteerStillKeepsRefreshAndUsage() {
        val y = ui3SeritYerlesimi(telefon, yonlendirVar = true, seritTam = false)

        assertTrue(y.yenile)
        assertTrue(y.kullanim)
        // 317.7 - 36*5 - 6*6 = 101.7
        esit(101.7f, y.cipMaks)
        assertTrue(y.cipMaks >= 60.dp)
    }

    // Dar cihazda sıra: önce Kullanım düşer.
    @Test
    fun narrowScreenDropsUsageFirst() {
        val y = ui3SeritYerlesimi(250.dp, yonlendirVar = true, seritTam = false)

        assertTrue(y.yenile)
        assertFalse(y.kullanim)
        // 250 - 36*4 - 6*5 = 76
        esit(76f, y.cipMaks)
        assertTrue(y.cipMaks >= 60.dp)
    }

    // Daha da darsa Yenile de düşer ve yer çipe gider — çip okunmaz bir "▾"
    // olmasın diye sıralama bu.
    @Test
    fun veryNarrowScreenDropsRefreshToo() {
        val y = ui3SeritYerlesimi(200.dp, yonlendirVar = true, seritTam = false)

        assertFalse(y.yenile)
        assertFalse(y.kullanim)
        // 200 - 36*3 - 6*4 = 68
        esit(68f, y.cipMaks)
    }

    // Tablette yarış yok: ölçü ne derse desin ikisi de kalır.
    @Test
    fun wideStripKeepsBothRegardless() {
        val y = ui3SeritYerlesimi(700.dp, yonlendirVar = true, seritTam = true)

        assertTrue(y.yenile)
        assertTrue(y.kullanim)
        esit(119f, y.cipMaks)
    }

    // Patolojik dar: negatif genişlik üretmemeli.
    @Test
    fun chipNeverGoesNegative() {
        val y = ui3SeritYerlesimi(80.dp, yonlendirVar = true, seritTam = false)

        assertTrue(y.cipMaks >= 0.dp)
    }

    // ÇALIŞMA KLASÖRÜ ŞERİTTE (25.08.2026): cowork'te boştaki telefon şeridi
    // klasör tuşunu da taşır — çip tavandan iner ama asgarinin üstünde kalır.
    @Test
    fun idlePhoneCarriesFolderButtonToo() {
        val y = ui3SeritYerlesimi(telefon, yonlendirVar = false, seritTam = false, klasorVar = true)

        assertTrue(y.yenile)
        assertTrue(y.kullanim)
        assertTrue(y.klasor)
        // 317.7 - 36*5 - 6*6 = 101.7
        esit(101.7f, y.cipMaks)
    }

    // En sıkışık durumda (tur + metin + steer) klasör KALIR, Kullanım düşer:
    // 18.08 kararının sırası klasörle genişledi — Kullanım → Klasör → Yenile.
    @Test
    fun runningWithSteerDropsUsageButKeepsFolder() {
        val y = ui3SeritYerlesimi(telefon, yonlendirVar = true, seritTam = false, klasorVar = true)

        assertTrue(y.yenile)
        assertFalse(y.kullanim)
        assertTrue(y.klasor)
        // Altısı birden 59.7dp bırakıyordu (< 60); Kullanım düşünce 101.7.
        esit(101.7f, y.cipMaks)
    }

    // Dar cihazda klasör Yenile'den ÖNCE düşer: tur ortasında tazelemenin en
    // son düşme garantisi (18.08) klasör eklenince de geçerli.
    @Test
    fun narrowScreenDropsFolderBeforeRefresh() {
        val y = ui3SeritYerlesimi(250.dp, yonlendirVar = true, seritTam = false, klasorVar = true)

        assertTrue(y.yenile)
        assertFalse(y.kullanim)
        assertFalse(y.klasor)
        // 250 - 36*4 - 6*5 = 76
        esit(76f, y.cipMaks)
    }

    // Tablette klasör de yarışsız kalır.
    @Test
    fun wideStripKeepsFolderRegardless() {
        val y = ui3SeritYerlesimi(700.dp, yonlendirVar = true, seritTam = true, klasorVar = true)

        assertTrue(y.klasor)
        esit(119f, y.cipMaks)
    }

    // Klasör verilmemişse (cowork değil) hiçbir hesap değişmez — klasorVar'sız
    // çağrılar eski davranışın birebir aynısını almalı.
    @Test
    fun withoutFolderLayoutIsUnchanged() {
        val eski = ui3SeritYerlesimi(telefon, yonlendirVar = true, seritTam = false)
        val acik = ui3SeritYerlesimi(telefon, yonlendirVar = true, seritTam = false, klasorVar = false)

        assertEquals(eski, acik)
        assertFalse(eski.klasor)
    }

    @Test
    fun liteLayoutDoesNotReserveOrShowUsageButton() {
        val normal = ui3SeritYerlesimi(telefon, yonlendirVar = false, seritTam = false)
        val lite = ui3SeritYerlesimi(
            telefon,
            yonlendirVar = false,
            seritTam = false,
            kullanimVar = false,
        )

        assertFalse(lite.kullanim)
        assertTrue(lite.cipMaks >= normal.cipMaks)
    }

    // Şeridin daralması yalnız yönlendirme tuşuyla olur; onsuz her zaman daha
    // geniş kalır (tuş sayısı bir eksik).
    @Test
    fun steerButtonOnlyEverCostsSpace() {
        val steersiz = ui3SeritYerlesimi(260.dp, yonlendirVar = false, seritTam = false)
        val steerli = ui3SeritYerlesimi(260.dp, yonlendirVar = true, seritTam = false)

        assertTrue(steersiz.cipMaks >= steerli.cipMaks)
        assertTrue(steersiz.kullanim || !steerli.kullanim)
    }
}
