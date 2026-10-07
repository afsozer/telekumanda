package com.agent.bridge.udf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Yazma yolunun KAYIPSIZ olduğunu bekçileyen testler.
 *
 * Serbest düzenleme (kullanıcı kararı 16.08.2026) kaydetmeyi zorunlu kılıyor;
 * kaydetme belgeyi baştan kurduğu için buradaki her madde bir veri kaybı
 * ihtimalidir. Hepsi gerçek UYAP belgelerinde karşılığı olan durumlar.
 */
class UdfWriterTest {

    @Test
    fun `tablo belge sirasindaki yerini korur`() {
        // Korpustaki fixture'da tablo İLK öğe, ardından 4 paragraf geliyor.
        val before = read(fixture("06-tablolu-belge.udf"))
        assertTrue("Fixture değişmiş: ilk blok tablo olmalıydı", before.blocks.first() is UdfTableBlock)

        val after = read(roundTrip(before))
        assertEquals(
            "Blok türleri ve sırası korunmalı",
            before.blocks.map { it::class.simpleName },
            after.blocks.map { it::class.simpleName },
        )
    }

    @Test
    fun `sablon alani duz metne donusmez`() {
        val original = fieldDocument()
        val written = roundTrip(read(original))
        val xml = contentXml(written)

        assertTrue("<field> etiketi korunmalı", xml.contains("<field"))
        assertTrue("fieldName özniteliği korunmalı", xml.contains("fieldName=\"davaci\""))
        assertTrue("<tab> etiketi korunmalı", xml.contains("<tab"))
    }

    @Test
    fun `tur atlatma metni ve ofsetleri bozmaz`() {
        for (name in listOf("01-arial-12pt.udf", "03-karisik-span.udf", "06-tablolu-belge.udf", "07-uzun-3plus-sayfa.udf")) {
            val before = read(fixture(name))
            val after = read(roundTrip(before))

            assertEquals("$name: gövde metni değişmemeli", before.text, after.text)
            for ((index, paragraph) in after.paragraphs.withIndex()) {
                for (element in paragraph.elements) {
                    val slice = after.text.substring(
                        element.startOffset,
                        (element.startOffset + element.length).coerceAtMost(after.text.length),
                    )
                    assertEquals(
                        "$name: $index. paragrafta ofset gövdedeki metne işaret etmeli",
                        element.rawText,
                        slice,
                    )
                }
            }
        }
    }

    // ── yardımcılar ───────────────────────────────────────────────────────

    private fun read(bytes: ByteArray): UdfDocument =
        ByteArrayInputStream(bytes).use { UdfParser.readUdf(it) }

    private fun roundTrip(doc: UdfDocument): ByteArray =
        ByteArrayOutputStream().also { UdfParser.writeUdf(it, doc) }.toByteArray()

    private fun contentXml(udf: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(udf)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "content.xml") return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("content.xml yok")
    }

    /** `<field>` ve `<tab>` çalıştırması taşıyan asgari belge. */
    private fun fieldDocument(): ByteArray {
        val text = "DAVACI\t: Ahmet Yılmaz\n"
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <template format_id="1.8">
            <content><![CDATA[@BODY@]]></content>
            <elements resolver="hvl-default">
            <paragraph><content startOffset="0" length="6" /><tab startOffset="6" length="1" /><content startOffset="7" length="2" /><field fieldName="davaci" fieldGroupName="taraflar" startOffset="9" length="12" /><content startOffset="21" length="1" /></paragraph>
            </elements>
            </template>
        """.trimIndent().replace("@BODY@", text)

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("content.xml"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
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
        error("udf-korpus fixture klasörü bulunamadı")
    }
}
