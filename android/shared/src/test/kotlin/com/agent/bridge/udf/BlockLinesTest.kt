package com.agent.bridge.udf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Satır bölmesi sayfalamanın ve (yakında) dokun-düzenlemenin birimini belirler:
 * bir satır fazla ya da eksik üretmek belgeyi yanlış yerden sayfalar.
 */
class BlockLinesTest {

    @Test
    fun `paragraf sert satirlarina bolunur`() {
        val lines = paragraphOf("Birinci satır\nİkinci satır\nÜçüncü satır").splitIntoLines()
        assertEquals(listOf("Birinci satır", "İkinci satır", "Üçüncü satır"), lines.map { it.plainText() })
    }

    @Test
    fun `paragrafi bitiren satir sonu hayalet satir uretmez`() {
        // UdfPackage.splitLines ile aynı kural: sondaki \n sonlandırıcıdır.
        assertEquals(listOf("YETKİ BELGESİ"), paragraphOf("YETKİ BELGESİ\n").splitIntoLines().map { it.plainText() })
    }

    @Test
    fun `aradaki bos satirlar korunur`() {
        // Boş satır belgede gerçek dikey yer kaplar; yutulursa metin yukarı kayar.
        assertEquals(listOf("a", "", "b"), paragraphOf("a\n\nb\n").splitIntoLines().map { it.plainText() })
    }

    @Test
    fun `yalniz satir sonundan olusan paragraf tek bos satirdir`() {
        assertEquals(listOf(""), paragraphOf("\n").splitIntoLines().map { it.plainText() })
    }

    @Test
    fun `satir sonu tasimayan paragraf aynen doner`() {
        // Ham dilim ve ofsetler burada korunmalı: gereksiz yere kopyalamıyoruz.
        val paragraph = paragraphOf("tek satır")
        val lines = paragraph.splitIntoLines()
        assertEquals(1, lines.size)
        assertSame(paragraph, lines.single())
    }

    @Test
    fun `bicim satir sinirini asarsa her satirda korunur`() {
        val paragraph = UdfParagraph(
            elements = listOf(
                element("kalın başlık\nkalın devam", bold = true),
                element("\nnormal satır"),
            )
        )
        val lines = paragraph.splitIntoLines()
        assertEquals(listOf("kalın başlık", "kalın devam", "normal satır"), lines.map { it.plainText() })
        assertTrue("İlk iki satır kalın kalmalıydı", lines.take(2).all { line -> line.elements.all { it.bold } })
        assertTrue("Son satır kalınlaşmamalıydı", lines.last().elements.none { it.bold })
    }

    @Test
    fun `satir ici ofsetler yeniden numaralanir`() {
        // Çizim tarafı çalıştırmaları startOffset'e göre sıralıyor; satır içinde
        // artan gitmezlerse metin karışır.
        val lines = UdfParagraph(
            elements = listOf(element("bir\niki "), element("üç", bold = true))
        ).splitIntoLines()

        val second = lines[1].elements
        assertEquals(listOf(0, 4), second.map { it.startOffset })
        assertEquals(listOf(4, 2), second.map { it.length })
    }

    @Test
    fun `tek paragrafa sigdirilmis belge satir satir bloklanir`() {
        // Ölçülen gerçek dilekçelerin şekli: gövdenin tamamı TEK <paragraph>,
        // satır sonları CDATA içinde. Bölme olmadan sayfa başına dilimleme böyle
        // bir belgede hiçbir şey kazandırmıyor — tek blok her sayfanın aralığına
        // giriyor ve her yaprağa yeniden diziliyor.
        val document = UdfParser.readUdf(
            singleParagraphDocument("Birinci satır\nİkinci satır\n\nDördüncü satır\n").inputStream()
        )

        assertEquals(1, document.paragraphs.size)
        assertEquals(
            listOf("Birinci satır", "İkinci satır", "", "Dördüncü satır"),
            document.blocks.filterIsInstance<UdfParagraphBlock>().map { it.paragraph.plainText() },
        )
    }

    @Test
    fun `yumusak sarma bolme noktasi degildir`() {
        // 07 numaralı fixture tek satırdır, yalnız uzundur: sayfalara YERLEŞİM
        // sararak yayılır. Sert satır sonu olmadığı için bölünecek yer de yok —
        // bölme yerleşimi taklit etmez, yalnız belgenin kendi sınırlarını izler.
        val document = Files.newInputStream(corpusDirectory().resolve("07-uzun-3plus-sayfa.udf")).use {
            UdfParser.readUdf(it)
        }
        assertEquals(1, document.blocks.size)
    }

    private fun UdfParagraph.plainText() = elements.joinToString("") { it.textRun }

    /** Gövdesi tek `<paragraph>` olan asgari UDF (baytları ZIP'lenmiş). */
    private fun singleParagraphDocument(text: String): ByteArray {
        // Gövde trimIndent SONRASI konur: CDATA'ya giren girintisiz satırlar
        // ortak girintiyi sıfırlayıp <?xml?> bildirimini satır başından kaydırır.
        val contentXml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <template format_id="1.8">
            <content><![CDATA[@BODY@]]></content>
            <elements resolver="hvl-default">
            <paragraph><content startOffset="0" length="@LEN@" /></paragraph>
            </elements>
            </template>
        """.trimIndent().replace("@LEN@", text.length.toString()).replace("@BODY@", text)

        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("content.xml"))
            zip.write(contentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun paragraphOf(text: String) = UdfParagraph(elements = listOf(element(text)))

    private fun element(text: String, bold: Boolean = false) =
        UdfElement(textRun = text, rawText = text, length = text.length, bold = bold)

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
