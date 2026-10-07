package com.agent.bridge

import com.agent.bridge.udf.UdfParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * Mobil imzanın taşıyıcı sözleşmesi: İMZALANAN içerik ile DİSKE YAZILAN içerik
 * birebir aynı olmalı. Aynı olmazsa imza doğrulanmaz ve bunu ancak UYAP
 * reddettiğinde öğreniriz — burada yakalanması şart.
 */
class UdfPackageImzaTest {

    @Test
    fun imzaliVeImzasizBaytlarAyniIcerigiTasir() {
        val pkg = UdfPackage.open(fixture("02-times-12pt.udf"))
        val imzasiz = pkg.unsignedBytes()
        val imzali = pkg.signedBytes("SAHTE-IMZA".toByteArray())

        assertArrayEquals(
            "content.xml imzalı ve imzasız kopyada aynı olmalı",
            girdi(imzasiz, "content.xml"),
            girdi(imzali, "content.xml"),
        )
        assertEquals(
            "tek fark sign.sgn olmalı",
            girdiler(imzasiz) + "sign.sgn",
            girdiler(imzali),
        )
    }

    @Test
    fun `imzasiz kopyada imza kalintisi yok`() {
        // Halihazırda imzalı bir belge yeniden imzalanacaksa eski imza
        // gönderilen bayta karışmamalı.
        val pkg = UdfPackage.open(fixture("02-times-12pt.udf"))
        val imzaliBaytlar = pkg.signedBytes("ESKI".toByteArray())
        val yeniden = UdfPackage.open(imzaliBaytlar)
        assertTrue(yeniden.signed)
        assertFalse("imzasız kopya sign.sgn taşımamalı", "sign.sgn" in girdiler(yeniden.unsignedBytes()))
    }

    @Test
    fun imzaGomulduktenSonraBelgeImzaliAcilir() {
        val pkg = UdfPackage.open(fixture("02-times-12pt.udf"))
        assertFalse(pkg.signed)
        val imza = byteArrayOf(1, 2, 3, 4, 5)
        val yeniden = UdfPackage.open(pkg.signedBytes(imza))
        assertTrue(yeniden.signed)
        assertTrue("imzalı belge salt okunur olmalı", yeniden.readOnly)
        assertArrayEquals(imza, yeniden.document.signatureBytes)
    }

    @Test
    fun `imza atilan belgenin metni korunur`() {
        val pkg = UdfPackage.open(fixture("05-tabset-dilekce-basligi.udf"))
        val once = UdfParser.readUdf(ByteArrayInputStream(pkg.unsignedBytes())).text
        val sonra = UdfParser.readUdf(ByteArrayInputStream(pkg.signedBytes(byteArrayOf(9)))).text
        assertEquals(once, sonra)
    }

    private fun girdiler(bytes: ByteArray): Set<String> {
        val out = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out.add(entry.name)
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun girdi(bytes: ByteArray, ad: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == ad) return zip.readBytes()
                entry = zip.nextEntry
            }
        }
        error("$ad girdisi yok")
    }

    private fun fixture(name: String): ByteArray = Files.readAllBytes(corpusDirectory().resolve(name))

    private fun corpusDirectory(): Path {
        val relative = Path.of("src", "test", "resources", "udf-korpus")
        var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            for (candidate in listOf(
                directory.resolve(relative),
                directory.resolve("android").resolve("shared").resolve(relative),
            )) {
                if (Files.isDirectory(candidate)) return candidate
            }
            directory = directory.parent ?: return@repeat
        }
        error("udf-korpus bulunamadi")
    }
}
