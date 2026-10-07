package com.agent.bridge.udf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Satır düzenleme + yazma. Buradaki asıl risk kaybetmek: bir satırı yeniden
 * yazarken şablon alanlarını, tabloları ya da komşu satırları bozmak.
 */
class UdfEditTest {

    @Test
    fun `satir yeniden yazilir ve komsu satirlar yerinde kalir`() {
        val document = parse(singleParagraphDocument("Birinci\nİkinci\nÜçüncü\n"))
        val target = document.lineRefOf("İkinci")

        val edited = document.replaceLineText(target, "İkinci satır değişti")

        assertEquals(
            listOf("Birinci", "İkinci satır değişti", "Üçüncü"),
            edited.lineTexts(),
        )
    }

    @Test
    fun `duzenleme diske yazilip geri okunabiliyor`() {
        val document = parse(singleParagraphDocument("Birinci\nİkinci\nÜçüncü\n"))
        val edited = document.replaceLineText(document.lineRefOf("İkinci"), "değişti")

        val reopened = parse(write(edited))

        assertEquals(listOf("Birinci", "değişti", "Üçüncü"), reopened.lineTexts())
        // Gövde metni ile çalıştırma ofsetleri tutarlı kalmalı: tutmazsa belge
        // UYAP Editörü'nde kayık açılır.
        assertOffsetsConsistent(reopened)
    }

    @Test
    fun `bos satira yazilabilir ve satir sayisi korunur`() {
        val document = parse(singleParagraphDocument("Başlık\n\nGövde\n"))
        val edited = document.replaceLineText(UdfLineRef(0, 1), "araya yazıldı")

        assertEquals(listOf("Başlık", "araya yazıldı", "Gövde"), edited.lineTexts())
        assertEquals(listOf("Başlık", "araya yazıldı", "Gövde"), parse(write(edited)).lineTexts())
    }

    @Test
    fun `satir bosaltilabilir`() {
        val document = parse(singleParagraphDocument("Başlık\nsilinecek\nGövde\n"))
        val edited = document.replaceLineText(document.lineRefOf("silinecek"), "")

        assertEquals(listOf("Başlık", "", "Gövde"), edited.lineTexts())
        assertEquals(listOf("Başlık", "", "Gövde"), parse(write(edited)).lineTexts())
    }

    @Test
    fun `yeni metindeki satir sonu yeni satir acar`() {
        val document = parse(singleParagraphDocument("Başlık\nGövde\n"))
        val edited = document.replaceLineText(document.lineRefOf("Gövde"), "birinci\nikinci")

        assertEquals(listOf("Başlık", "birinci", "ikinci"), edited.lineTexts())
        assertEquals(listOf("Başlık", "birinci", "ikinci"), parse(write(edited)).lineTexts())
    }

    @Test
    fun `sablon alani tasiyan satir kilitlidir`() {
        val document = parse(fieldDocument())
        val fieldLine = document.paragraphBlocks().first { it.paragraph.plainText().contains("Ahmet") }

        assertFalse("Alan taşıyan satır düzenlenebilir görünmemeli", fieldLine.editable)
        // Yine de çağrılırsa belge DEĞİŞMEZ: aynı örnek geri döner.
        assertSame(document, document.replaceLineText(fieldLine.line!!, "başka isim"))
    }

    @Test
    fun `alan tasimayan satir ayni belgede duzenlenebilir`() {
        val document = parse(fieldDocument())
        val plain = document.paragraphBlocks().first { it.paragraph.plainText().startsWith("Düz") }

        assertTrue(plain.editable)
        val edited = document.replaceLineText(plain.line!!, "Düz satır değişti")
        assertTrue(edited.lineTexts().contains("Düz satır değişti"))
        // Alan çalıştırması etiketiyle birlikte hayatta kalmalı.
        val reopened = parse(write(edited))
        assertTrue(
            "field çalıştırması korunmalıydı",
            reopened.paragraphs.flatMap { it.elements }.any { it.kind == UdfElementKind.FIELD },
        )
        assertTrue(reopened.lineTexts().contains("Düz satır değişti"))
    }

    @Test
    fun `satir icindeki bicim ilk parcaya duzlesir ama komsu satirin bicimi durur`() {
        // Karışık biçimli satır yeniden yazılınca tek çalıştırma olur; bu bilinçli
        // bir taviz. Komşu satırın kalını etkilenmemeli.
        val document = parse(mixedFormatDocument())
        val edited = document.replaceLineText(UdfLineRef(0, 0), "düz yazıldı")

        val lines = edited.paragraphBlocks()
        assertEquals(1, lines[0].paragraph.elements.size)
        assertTrue("İkinci satırın kalın parçası durmalıydı", lines[1].paragraph.elements.any { it.bold })
    }

    @Test
    fun `satir duzenlemek ayni belgedeki tabloyu bozmaz`() {
        // Tablo ham XML olarak duruyor ve kendi dilimlerini ÖZGÜN gövde metninden
        // geri okuyor; paragrafı değiştirmek onu kaydırmamalı.
        val document = parse(tableDocument())
        assertEquals(1, document.blocks.count { it is UdfTableBlock })

        val reopened = parse(write(document.replaceLineText(document.lineRefOf("Giriş"), "Giriş değişti")))

        assertEquals(listOf("Giriş değişti", "Son"), reopened.lineTexts())
        val table = reopened.blocks.filterIsInstance<UdfTableBlock>().single()
        assertTrue(
            "Hücre metni korunmalıydı",
            table.rows.single().cells.single().blocks
                .filterIsInstance<UdfParagraphBlock>()
                .any { it.paragraph.plainText().contains("Hücre") },
        )
    }

    @Test
    fun `kalin secim yalniz hedef araligi bicimlendirir ve diske yazilir`() {
        val document = parse(singleParagraphDocument("abcdef\n"))
        val line = document.lineRefOf("abcdef")

        val formatted = document
            .toggleLineStyle(line, 2, 4, "bold")
            .toggleLineStyle(line, 2, 4, "italic")
            .toggleLineStyle(line, 2, 4, "underline")
        val reopened = parse(write(formatted))
        val elements = reopened.paragraphs.single().elements.filter { it.textRun.isNotEmpty() }

        assertEquals(listOf("ab", "cd", "ef\n"), elements.map { it.textRun })
        assertFalse(elements[0].bold)
        assertTrue(elements[1].bold)
        assertTrue(elements[1].italic)
        assertTrue(elements[1].underline)
        assertFalse(elements[2].bold)
    }

    @Test
    fun `secim yoksa punto satirin tamamina uygulanir`() {
        val document = parse(singleParagraphDocument("Birinci\nİkinci\n"))
        val edited = document.setLineFontSize(document.lineRefOf("İkinci"), 2, 2, 14)
        val second = edited.paragraphBlocks().first { it.paragraph.plainText() == "İkinci" }

        assertTrue(second.paragraph.elements.filter { it.textRun.removeSuffix("\n").isNotEmpty() }.all { it.size == 14 })
        assertEquals(12, edited.paragraphBlocks().first().paragraph.elements.first().size)
    }

    @Test
    fun `hizalama dokunulan satirin paragrafina uygulanir`() {
        val document = parse(singleParagraphDocument("Birinci\nİkinci\n"))
        val edited = document.setLineAlignment(document.lineRefOf("İkinci"), 3)

        // İki görsel satır aynı UDF paragrafına ait olduğu için paragraf düzeyi
        // hizalama ikisine de yansır.
        assertTrue(edited.paragraphBlocks().all { it.paragraph.alignment == 3 })
        assertTrue(parse(write(edited)).paragraphBlocks().all { it.paragraph.alignment == 3 })
    }

    // ── yardımcılar ───────────────────────────────────────────────────────

    private fun UdfDocument.paragraphBlocks() = blocks.filterIsInstance<UdfParagraphBlock>()

    private fun UdfDocument.lineTexts() = paragraphBlocks().map { it.paragraph.plainText() }

    private fun UdfDocument.lineRefOf(text: String): UdfLineRef =
        paragraphBlocks().first { it.paragraph.plainText() == text }.line!!

    private fun parse(bytes: ByteArray) = UdfParser.readUdf(bytes.inputStream())

    private fun write(document: UdfDocument): ByteArray =
        ByteArrayOutputStream().also { UdfParser.writeUdf(it, document) }.toByteArray()

    /** Her çalıştırmanın (startOffset, length) dilimi gerçekten kendi metnini göstermeli. */
    private fun assertOffsetsConsistent(document: UdfDocument) {
        for (element in document.paragraphs.flatMap { it.elements }) {
            val end = element.startOffset + element.length
            assertTrue("Ofset gövdenin dışına taşıyor", end <= document.text.length)
            assertEquals(
                "Ofset yanlış metni gösteriyor",
                element.rawText,
                document.text.substring(element.startOffset, end),
            )
        }
    }

    private fun singleParagraphDocument(text: String): ByteArray = udf(
        """
        <paragraph><content startOffset="0" length="@LEN@" /></paragraph>
        """.trimIndent().replace("@LEN@", text.length.toString()),
        text,
    )

    /** Bir satırı `<field>` ile bağlı, bir satırı düz olan belge. */
    private fun fieldDocument(): ByteArray {
        val text = "Müvekkil: Ahmet Yılmaz\nDüz satır\n"
        return udf(
            """
            <paragraph>
            <content startOffset="0" length="10" />
            <field startOffset="10" length="10" fieldName="ad" fieldGroupName="muvekkil" />
            <content startOffset="20" length="11" />
            </paragraph>
            """.trimIndent(),
            text,
            data = "<data><muvekkil><ad>Ahmet Yılmaz</ad></muvekkil></data>",
        )
    }

    /** İlk satırı yarı kalın, ikinci satırı kalın parçalı belge. */
    private fun mixedFormatDocument(): ByteArray {
        val text = "düz KALIN\nikinci KALIN\n"
        return udf(
            """
            <paragraph>
            <content startOffset="0" length="4" />
            <content bold="true" startOffset="4" length="6" />
            <content startOffset="10" length="7" />
            <content bold="true" startOffset="17" length="6" />
            </paragraph>
            """.trimIndent(),
            text,
        )
    }

    /** Bir paragraf, ardından tek hücreli bir tablo. */
    private fun tableDocument(): ByteArray = udf(
        """
        <paragraph><content startOffset="0" length="10" /></paragraph>
        <table columnCount="1" columnSpans="100"><row><cell><paragraph><content startOffset="10" length="6" /></paragraph></cell></row></table>
        """.trimIndent(),
        "Giriş\nSon\nHücre\n",
    )

    private fun udf(elementsXml: String, body: String, data: String = ""): ByteArray {
        // Gövde trimIndent SONRASI konur: CDATA'ya giren girintisiz satırlar ortak
        // girintiyi sıfırlayıp <?xml?> bildirimini satır başından kaydırıyor.
        val contentXml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <template format_id="1.8">
            <content><![CDATA[@BODY@]]></content>
            <elements resolver="hvl-default">
            @ELEMENTS@
            </elements>
            @DATA@
            </template>
        """.trimIndent()
            .replace("@ELEMENTS@", elementsXml)
            .replace("@DATA@", data)
            .replace("@BODY@", body)

        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("content.xml"))
            zip.write(contentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
