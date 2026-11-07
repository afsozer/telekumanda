package com.agent.bridge.udf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// UDF dışarıdan geliyor (WhatsApp, e-posta, dosya yöneticisi): açılınca
// şişen bir zip ve DTD/entity taşıyan content.xml okuma sırasında reddedilir.
// Normal belgeler (korpus fikstürleri UdfCorpusTest'te) geçmeye devam eder.
class UdfZipSiniriTest {
    // Yazıcının ürettiği gerçek content.xml: ek parça testlerinde içerik
    // geçerli olmalı ki ret içerikten değil sınırdan gelsin.
    private val gecerliIcerik: ByteArray by lazy {
        val udf = ByteArrayOutputStream().also { UdfParser.writeUdf(it, UdfDocument()) }.toByteArray()
        ZipInputStream(ByteArrayInputStream(udf)).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "content.xml" }
            z.readBytes()
        }
    }

    private fun zip(vararg girdiler: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                for ((ad, veri) in girdiler) {
                    z.putNextEntry(ZipEntry(ad))
                    z.write(veri)
                    z.closeEntry()
                }
            }
        }.toByteArray()

    private fun hata(udf: ByteArray): Throwable? =
        runCatching { UdfParser.readUdf(ByteArrayInputStream(udf)) }.exceptionOrNull()

    // Sıkıştırılmış hâli birkaç on KB; açılınca sınırı aşıyor.
    private fun sisen(boyut: Int) = ByteArray(boyut) { ' '.code.toByte() }

    @Test
    fun normalBelgeVeEkParcalarOkunur() {
        val belge = UdfParser.readUdf(
            ByteArrayInputStream(zip("content.xml" to gecerliIcerik, "resim.png" to ByteArray(512 * 1024) { 7 })),
        )
        assertEquals(1, belge.extraParts.size)
        assertEquals(512 * 1024, belge.extraParts.single().bytes.size)
    }

    @Test
    fun asiriBuyukAcilanContentReddedilir() {
        val udf = zip("content.xml" to sisen(21 * 1024 * 1024))
        assertTrue("zip küçük kalmalı: ${udf.size}", udf.size < 1024 * 1024)
        val e = hata(udf)
        assertTrue("$e", e is IllegalArgumentException)
    }

    @Test
    fun asiriBuyukAcilanEkParcaReddedilir() {
        val e = hata(zip("content.xml" to gecerliIcerik, "resim.png" to sisen(21 * 1024 * 1024)))
        assertTrue("$e", e is IllegalArgumentException)
    }

    // Tek tek sınırın altında kalan girdiler toplamda da sınırlanır.
    @Test
    fun toplamAcilmisBoyutSinirlanir() {
        val parca = sisen(19 * 1024 * 1024)
        val girdiler = arrayOf("content.xml" to gecerliIcerik) +
            (1..5).map { "ek$it.bin" to parca }.toTypedArray()
        val e = hata(zip(*girdiler))
        assertTrue("$e", e is IllegalArgumentException)
    }

    @Test
    fun doctypeliIcerikReddedilir() {
        val xxe = """<?xml version="1.0"?>
            <!DOCTYPE template [<!ENTITY xxe SYSTEM "file:///data/data/com.agent.bridge/shared_prefs/settings.xml">]>
            <template><content>&xxe;</content></template>""".trimIndent()
        val e = hata(zip("content.xml" to xxe.toByteArray()))
        assertTrue("$e", e is IllegalArgumentException)
        assertTrue("${e?.cause}", e?.cause?.message.orEmpty().contains("DTD"))
    }

    @Test
    fun kucukHarfVeUtf16DoctypeDaYakalanir() {
        val kucuk = """<?xml version="1.0"?><!doctype template><template><content>x</content></template>"""
        assertTrue(hata(zip("content.xml" to kucuk.toByteArray())) is IllegalArgumentException)
        val utf16 = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE t><template/>""".toByteArray(Charsets.UTF_16LE)
        assertTrue(hata(zip("content.xml" to utf16)) is IllegalArgumentException)
    }
}
