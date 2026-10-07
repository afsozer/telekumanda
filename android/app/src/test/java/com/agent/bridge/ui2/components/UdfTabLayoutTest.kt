package com.agent.bridge.ui2.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sekmeli satır yerleşimi — [tabbedLineOffsets].
 *
 * Ölçüler nokta değil piksel: işlev ölçekten habersiz, çağıran ölçekli değeri
 * veriyor. Sayfa genişliği 500, varsayılan sekme aralığı 72 alındı.
 */
class UdfTabLayoutTest {

    private val sayfa = 500
    private val aralik = 72f

    @Test
    fun zoomParmakAltindakiBelgeNoktasiniSabitTutar() {
        val eskiKaydirma = 140f
        val odak = 500f
        val eskiBaslangic = 200f
        val yeniBaslangic = 80f
        val oran = 1.6f

        val yeniKaydirma = focalScrollTarget(
            scroll = eskiKaydirma,
            focal = odak,
            oldOrigin = eskiBaslangic,
            newOrigin = yeniBaslangic,
            zoomRatio = oran,
        )

        val eskiIcerikNoktasi = eskiKaydirma + odak - eskiBaslangic
        val yeniIcerikNoktasi = yeniKaydirma + odak - yeniBaslangic
        assertEquals(eskiIcerikNoktasi * oran, yeniIcerikNoktasi, 0.001f)
    }

    @Test
    fun sekmeDuragiOlmayanSatirVarsayilanAraliktaIlerler() {
        // "DAVACI" 40 geniş: ilk sekme 72'ye, ikinci 144'e taşır.
        val yerler = tabbedLineOffsets(listOf(40, 30, 60), emptyList(), aralik, sayfa)
        assertEquals(listOf(0, 72, 144), yerler.map { it.x })
        assertTrue(yerler.all { it.row == 0 })
    }

    @Test
    fun tanimliDurakVarsaOnaOturur() {
        val yerler = tabbedLineOffsets(listOf(40, 60), listOf(151f), aralik, sayfa)
        assertEquals(listOf(0, 151), yerler.map { it.x })
    }

    @Test
    fun ellidenFazlaSekmeYarimSayfaBoslukAcmaz() {
        // Canlı belge: "EK KARAR" altındaki satırda 53 sekme, ardından başlık.
        // Kullanılabilir durak yok (UYAP hepsini 0.0'a yazmıştı), başlık 200 geniş.
        val genislikler = List(53) { 0 } + listOf(200)
        val yerler = tabbedLineOffsets(genislikler, emptyList(), aralik, sayfa)
        assertEquals("satır kırılmamalı", 0, yerler.last().row)
        assertTrue("tüm parçalar tek görsel satırda", yerler.all { it.row == 0 })
        // Başlık sağ kenara yaslanır: 500 - 200.
        assertEquals(300, yerler.last().x)
    }

    @Test
    fun kenariGecenSekmeGeriyeCekmez() {
        // Sekme kenara sabitlendikten sonra x asla küçülmemeli.
        val yerler = tabbedLineOffsets(List(20) { 0 } + listOf(0), emptyList(), aralik, sayfa)
        val xler = yerler.map { it.x }
        assertEquals(xler.sorted(), xler)
        assertTrue(xler.all { it <= sayfa })
    }

    @Test
    fun sayfadanGenisSonParcaYaslanmaz_kendiSutunundaSarar() {
        // "KATILAN	: ONURCAN SAĞBAN, …" deseni: son parça satıra sığmıyor.
        // Sağa yaslanırsa sola kayıp etiketin üstüne biner (canlı hata).
        val yerler = tabbedLineOffsets(listOf(40, 600), listOf(151f), aralik, sayfa, 96)
        assertEquals(0, yerler[0].x)
        assertEquals("sekme durağında kalmalı", 151, yerler[1].x)
    }

    @Test
    fun sigmayanSonParcayaSaracakYerBirakilir() {
        // Sekmeler kenara dayandı; son parça sayfadan geniş. Olduğu yerde
        // kalsaydı sıfıra yakın genişlikte karakter karakter sarardı.
        val genislikler = List(20) { 0 } + listOf(900)
        val yerler = tabbedLineOffsets(genislikler, emptyList(), aralik, sayfa, 96)
        assertEquals(sayfa - 96, yerler.last().x)
    }

    @Test
    fun kenaraDayandiktanSonraGercekMetinVarsaSatirKirilir() {
        // Sekmeler kenara dayandı ama ARADA hâlâ dolu bir parça var: o parça
        // sayfa dışında kalmasın diye satır kırılır.
        val genislikler = List(10) { 0 } + listOf(80, 80)
        val yerler = tabbedLineOffsets(genislikler, emptyList(), aralik, sayfa)
        assertEquals(1, yerler[10].row)
        assertEquals(0, yerler[10].x)
    }

    @Test
    fun sinirsizGenislikteSekmelerSerbestIlerler() {
        val yerler = tabbedLineOffsets(listOf(0, 0, 0, 10), emptyList(), aralik, null)
        assertEquals(listOf(0, 72, 144, 216), yerler.map { it.x })
    }
}
