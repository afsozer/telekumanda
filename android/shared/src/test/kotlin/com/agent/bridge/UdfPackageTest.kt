package com.agent.bridge

import com.agent.bridge.udf.UdfLineRef
import com.agent.bridge.udf.UdfParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class UdfPackageTest {

    // ── satır bölme: iki gerileme buradan doğmuştu ────────────────────────

    @Test
    fun `paragrafi bitiren satir sonu hayalet bos satir uretmez`() {
        // "YETKİ BELGESİ\n" tek satırdır: sondaki \n paragrafın sonlandırıcısı.
        // Eskiden ikinci (boş) satır üretiliyordu ve belgedeki boş kart sayısı
        // ikiye katlanıyordu.
        assertEquals(listOf(0 to 13), UdfPackage.splitLines("YETKİ BELGESİ\n"))
    }

    @Test
    fun `aradaki bos satirlar korunur`() {
        assertEquals(listOf(0 to 1, 2 to 2, 3 to 4), UdfPackage.splitLines("a\n\nb\n"))
    }

    @Test
    fun `yalniz satir sonundan olusan paragraf tek bos satirdir`() {
        assertEquals(listOf(0 to 0), UdfPackage.splitLines("\n"))
    }

    @Test
    fun `son paragrafta satir sonu olmayabilir`() {
        assertEquals(listOf(0 to 3), UdfPackage.splitLines("abc"))
    }

    // ── belge bloklari ────────────────────────────────────────────────────

    @Test
    fun `bos satir kart degil bosluk olarak isaretlenir`() {
        // Sentetik korpusta boş satır yok; gerçek UYAP belgelerinde bolca var
        // (yetki belgesinde 27 paragrafın 11'i). Fixture burada kurulur.
        val blocks = UdfPackage.open(blankLineDocument()).blocks()

        // Eski sürümde bu belge 10 blok üretiyordu: her boş satır bir kart, ve
        // paragrafı bitiren \n her paragrafa bir hayalet boş satır daha
        // ekliyordu. Doğrusu 5.
        assertEquals(5, blocks.size)
        assertEquals(
            listOf("blank", "blank", "paragraph", "blank", "paragraph"),
            blocks.map { it.kind },
        )
        assertEquals(listOf("BAŞLIK", "Gövde metni"), blocks.filter { it.kind == "paragraph" }.map { it.text })
        // Boş bloklar metin taşımaz ve altlarına açıklama yazılmaz: eskiden
        // her biri "[Korunan belge öğesi] / İmzalı belge — düzenleme kapalı"
        // yazan gri bir kart oluyordu.
        assertTrue(blocks.filter { it.kind == "blank" }.all { it.text.isBlank() && it.detail.isBlank() })
    }

    @Test
    fun `imzali belge imzali olarak acilir`() {
        assertTrue(UdfPackage.open(blankLineDocument(signed = true)).signed)
    }

    @Test
    fun `tablo tek salt okunur blok olarak gelir`() {
        val blocks = UdfPackage.open(fixture("06-tablolu-belge.udf")).blocks()
        val tables = blocks.filter { it.kind == "table" }
        assertEquals(1, tables.size)
        assertTrue("Tablo metni hücreleri taşımalı", tables.single().text.contains("|"))
    }

    @Test
    fun `metin bloklari icerigi ve bicimi tasir`() {
        val blocks = UdfPackage.open(fixture("03-karisik-span.udf")).blocks()
        val paragraphs = blocks.filter { it.kind == "paragraph" }
        assertTrue(paragraphs.isNotEmpty())
        assertTrue("Metin boş gelmemeli", paragraphs.all { it.text.isNotBlank() })
        assertTrue(
            "Karışık biçimli fixture'da span bekleniyordu",
            paragraphs.any { block -> block.spans.any { it.style.bold || it.style.italic } },
        )
    }

    // ── salt okunurluk ────────────────────────────────────────────────────

    @Test
    fun `blok listesinden duzenleme yok`() {
        // Düzenleme yazdırma düzeninden yapılıyor (kullanıcı kararı 16.08.2026);
        // kart listesi UDF'te salt okunur kalır.
        val pkg = UdfPackage.open(fixture("01-arial-12pt.udf"))
        assertTrue(pkg.blocks().none { it.editable })
    }

    @Test
    fun `imzali belge duzenlenemez`() {
        // Metni değiştirmek sign.sgn içindeki imzayı geçersiz kılar.
        val bytes = blankLineDocument(signed = true)
        val pkg = UdfPackage.open(bytes)
        assertTrue(pkg.readOnly)
        assertFalse(pkg.replaceLine(UdfLineRef(2, 0), "değişti"))
        assertArrayEqualsBytes(bytes, pkg.toBytes())
    }

    @Test
    fun `imzasiz belgede satir yazdirma duzeninden duzenlenir`() {
        val pkg = UdfPackage.open(blankLineDocument())
        assertFalse(pkg.readOnly)

        val target = pkg.document.blocks
            .filterIsInstance<UdfParagraphBlock>()
            .first { it.paragraph.elements.joinToString("") { e -> e.textRun } == "BAŞLIK" }

        assertTrue(pkg.replaceLine(target.line!!, "YENİ BAŞLIK"))
        assertTrue(pkg.blocks().any { it.text == "YENİ BAŞLIK" })
        // Değişiklik gerçekten diske gidiyor mu?
        assertTrue(UdfPackage.open(pkg.toBytes()).blocks().any { it.text == "YENİ BAŞLIK" })
    }

    @Test
    fun `imzasiz korpus fixture'lari imzali gorunmez`() {
        assertFalse(UdfPackage.open(fixture("01-arial-12pt.udf")).signed)
    }

    @Test
    fun `toBytes acilan baytlari aynen dondurur`() {
        // Yazma yolu yok: belge yeniden kurulsaydı şablon alanları ve tablo
        // ofsetleri bozulurdu (bkz. UdfPackage başlığı).
        val bytes = fixture("07-uzun-3plus-sayfa.udf")
        assertArrayEqualsBytes(bytes, UdfPackage.open(bytes).toBytes())
    }

    @Test
    fun `duzenleme cagrilari belgeyi degistirmez`() {
        val bytes = fixture("01-arial-12pt.udf")
        val pkg = UdfPackage.open(bytes)
        val before = pkg.blocks()
        pkg.updateParagraph(before.first().copy(text = "değişti"))
        pkg.applyFontToAll("Arial")
        assertEquals(before, pkg.blocks())
        assertArrayEqualsBytes(bytes, pkg.toBytes())
    }

    /**
     * Boş satır taşıyan asgari bir UDF: metnin tamamı tek CDATA gövdesinde,
     * her paragraf oraya (startOffset, length) ile işaret eder.
     *
     * Gövde: `\n` `\n` `BAŞLIK\n` `\n` `Gövde metni\n`
     */
    private fun blankLineDocument(signed: Boolean = false): ByteArray {
        val text = "\n\nBAŞLIK\n\nGövde metni\n"
        // Gövde trimIndent SONRASI yerleştirilir: CDATA'ya giren girintisiz
        // satırlar ortak girintiyi sıfırlıyor ve <?xml?> bildirimi satır başında
        // kalmıyordu (XML ayrıştırıcı bunu reddediyor).
        val contentXml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <template format_id="1.8">
            <content><![CDATA[@BODY@]]></content>
            <elements resolver="hvl-default">
            <paragraph><content startOffset="0" length="1" /></paragraph>
            <paragraph><content startOffset="1" length="1" /></paragraph>
            <paragraph Alignment="1"><content bold="true" startOffset="2" length="7" /></paragraph>
            <paragraph><content startOffset="9" length="1" /></paragraph>
            <paragraph><content startOffset="10" length="12" /></paragraph>
            </elements>
            </template>
        """.trimIndent().replace("@BODY@", text)

        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("content.xml"))
            zip.write(contentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (signed) {
                zip.putNextEntry(java.util.zip.ZipEntry("sign.sgn"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) =
        assertTrue("Baytlar değişmemeliydi", expected.contentEquals(actual))

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
