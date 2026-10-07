package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class FileMimeTest {

    // Asil hata: Android'in haritasi .udf'yi bilmiyor, cagiran */* gonderiyor ve
    // secicide UYAP Editor / UDF Editor Pro CIKMIYOR (canli, 31 Tem 2026).
    @Test
    fun `udf sistem bilmese de zip olarak cozulur`() {
        assertEquals(
            "application/zip",
            mimeTypeForExtension("udf") { null },
        )
    }

    // Olculmus deger sistemin tahminini EZER: ileride Android .udf icin baska
    // bir tip ogrenirse calistigini bildigimiz tek deger kaybolmasin.
    @Test
    fun `olculmus liste sistem haritasini ezer`() {
        assertEquals(
            "application/zip",
            mimeTypeForExtension("udf") { "application/vnd.uyap.udf" },
        )
    }

    // Listede olmayan uzantida sistem kazanir, davranis degismez.
    @Test
    fun `bilinen uzantida sistem degeri kullanilir`() {
        assertEquals(
            "application/pdf",
            mimeTypeForExtension("pdf") { "application/pdf" },
        )
    }

    @Test
    fun `ikisi de bilmiyorsa yedege duser`() {
        assertEquals("*/*", mimeTypeForExtension("zzz") { null })
    }

    // Buyuk harf ve bastaki nokta normalize edilir; lowercase Locale.ROOT ile
    // cagriliyor, tr-TR'de "TIF" -> "tıf" olup eslesmeyi sessizce bozmasin.
    @Test
    fun `uzanti normalize edilir`() {
        assertEquals("application/zip", mimeTypeForExtension(".UDF") { null })
        assertEquals("application/zip", mimeTypeForExtension("Udf") { null })
    }

    @Test
    fun `bos uzanti yedege duser`() {
        assertEquals("*/*", mimeTypeForExtension("") { "application/pdf" })
    }
}
