package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DocxLiteTest {

    @Test
    fun realDocxRoundTripSmokeWhenConfigured() {
        val path = System.getenv("DOCX_SMOKE_PATH").orEmpty()
        assumeTrue(path.isNotBlank() && File(path).isFile)
        val bytes = File(path).readBytes()

        val opened = DocxLitePackage.open(bytes)
        println("DOCX_SMOKE blocks=${opened.blocks().size} editable=${opened.blocks().count { it.editable }} preserved=${opened.blocks().count { !it.editable }}")
        assertTrue(opened.blocks().isNotEmpty())
        assertTrue(opened.blocks().any { it.editable })

        val firstEditable = opened.blocks().first { it.editable }
        opened.updateParagraph(firstEditable.copy(text = firstEditable.text + " [telefon testi]"))
        opened.applyFontToAll("Times New Roman")
        val roundTripped = DocxLitePackage.open(opened.toBytes())
        assertEquals(opened.blocks().size, roundTripped.blocks().size)
        assertTrue(roundTripped.blocks().first { it.editable }.text.endsWith("[telefon testi]"))
        assertTrue(roundTripped.blocks().filter { it.editable }.flatMap { it.spans }.all { it.style.font == "Times New Roman" })
    }

    @Test
    fun readsFormatsEditsAndPreservesOpaquePackageEntries() {
        val original = sampleDocx()
        val docx = DocxLitePackage.open(original)
        val paragraph = docx.blocks().first { it.editable }

        assertEquals("Merhaba dünya", paragraph.text)
        assertTrue(paragraph.spans.first().style.bold)
        assertEquals("Arial", paragraph.spans.first().style.font)
        assertTrue(docx.blocks().any { it.kind == "table" && !it.editable })

        val edited = paragraph.copy(
            text = "Merhaba telefon",
            spans = listOf(DocxSpan(0, 16, DocxCharStyle(italic = true, underline = true, font = "Times New Roman", sizeHalfPoints = 24))),
            alignment = "center",
        )
        docx.updateParagraph(edited)
        val saved = docx.toBytes()
        val reopened = DocxLitePackage.open(saved)
        val result = reopened.blocks().first { it.editable }

        assertEquals("Merhaba telefon", result.text)
        assertEquals("center", result.alignment)
        assertTrue(result.spans.single().style.italic)
        assertTrue(result.spans.single().style.underline)
        assertEquals("Times New Roman", result.spans.single().style.font)
        assertEquals("keep-me", zipEntries(saved)["word/media/image1.bin"]?.toString(Charsets.UTF_8))
        assertTrue(reopened.blocks().any { it.kind == "table" })
    }

    @Test
    fun wholeDocumentFontConversionPreservesOtherRunFormatting() {
        val docx = DocxLitePackage.open(sampleDocx())
        docx.applyFontToAll("Times New Roman")
        val reopened = DocxLitePackage.open(docx.toBytes())
        val paragraph = reopened.blocks().first { it.editable }

        assertTrue(paragraph.spans.first().style.bold)
        assertEquals(22, paragraph.spans.first().style.sizeHalfPoints)
        assertTrue(paragraph.spans.all { it.style.font == "Times New Roman" })
        assertTrue(zipEntries(docx.toBytes()).getValue("word/header1.xml").toString(Charsets.UTF_8).contains("Times New Roman"))
    }

    @Test
    fun spanFormattingSplitsOnlyTheSelectedRange() {
        val block = DocxBlock(
            id = "p0", kind = "paragraph", text = "abcdef",
            spans = listOf(DocxSpan(0, 6, DocxCharStyle(font = "Arial"))), editable = true,
        )

        val result = applyDocxStyle(block, 2, 4) { it.copy(bold = true) }

        assertEquals(listOf(0 to 2, 2 to 4, 4 to 6), result.spans.map { it.start to it.end })
        assertFalse(result.spans[0].style.bold)
        assertTrue(result.spans[1].style.bold)
        assertFalse(result.spans[2].style.bold)
    }

    @Test
    fun mixedSelectionCanBeDetectedAndMadeUniform() {
        val block = DocxBlock(
            id = "p0", kind = "paragraph", text = "abcdef",
            spans = listOf(
                DocxSpan(0, 3, DocxCharStyle(bold = true)),
                DocxSpan(3, 6, DocxCharStyle(bold = false)),
            ), editable = true,
        )

        assertFalse(docxSelectionAll(block, 0, 6) { it.bold })
        val enabled = applyDocxStyle(block, 0, 6) { it.copy(bold = true) }
        assertTrue(docxSelectionAll(enabled, 0, 6) { it.bold })
    }

    @Test
    fun typingShiftsFollowingFormattingRanges() {
        val block = DocxBlock(
            id = "p0", kind = "paragraph", text = "abcXYZ",
            spans = listOf(
                DocxSpan(0, 3, DocxCharStyle(bold = true)),
                DocxSpan(3, 6, DocxCharStyle(italic = true)),
            ), editable = true,
        )

        val spans = adjustDocxSpansForTextChange(block, "abc123XYZ")

        assertTrue(spans.any { it.start == 6 && it.end == 9 && it.style.italic })
    }

    @Test
    fun rejectsDoctypeEvenWhenPlatformParserFeaturesDiffer() {
        val malicious = """<?xml version="1.0"?>
            <!DOCTYPE w:document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
            </w:document>""".trimIndent()

        val error = runCatching { DocxLitePackage.open(sampleDocx(malicious)) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("DTD/entity"))
    }

    // Word'un icerik tasimayan isaretleri paragrafi salt-okunura dusurmemeli.
    // 05.08.2026'da kullanicinin dilekcelerinde olculdu: salt-okunur isaretlenen
    // 27 paragrafin TAMAMI bu ikisi yuzundendi, hicbirinde gorsel/alan yoktu.
    @Test
    fun wordLayoutMarkersDoNotMakeParagraphReadOnly() {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:proofErr w:type="spellStart"/>
                  <w:r><w:lastRenderedPageBreak/><w:t>Yoklama fisi</w:t></w:r>
                  <w:proofErr w:type="spellEnd"/>
                </w:p>
                <w:sectPr/>
              </w:body>
            </w:document>""".trimIndent()

        val docx = DocxLitePackage.open(sampleDocx(xml))
        val block = docx.blocks().single()

        assertTrue("paragraf duzenlenebilir olmali", block.editable)
        assertEquals("Yoklama fisi", block.text)

        // Duzenleme sonrasi yeniden yazimda metin korunuyor; atilan iki isaret
        // Word tarafindan yeniden uretiliyor.
        docx.updateParagraph(block.copy(text = "Yoklama fisi (duzeltildi)"))
        val reopened = DocxLitePackage.open(docx.toBytes())
        assertEquals("Yoklama fisi (duzeltildi)", reopened.blocks().single().text)
    }

    // Gercek karmasik paragraf (gorsel/alan) salt-okunur KALMALI: yukaridaki
    // gevsetme onu da kapsasaydi kaydederken icerik silinirdi.
    @Test
    fun realComplexParagraphStaysReadOnly() {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:hyperlink w:id="rId1"><w:r><w:t>baglanti</w:t></w:r></w:hyperlink></w:p>
                <w:sectPr/>
              </w:body>
            </w:document>""".trimIndent()

        val block = DocxLitePackage.open(sampleDocx(xml)).blocks().single()

        assertFalse(block.editable)
    }

    private fun sampleDocx(documentOverride: String? = null): ByteArray {
        val documentXml = documentOverride ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:r><w:rPr><w:b/><w:rFonts w:ascii="Arial" w:hAnsi="Arial"/><w:sz w:val="22"/></w:rPr><w:t>Merhaba </w:t></w:r>
                  <w:r><w:rPr><w:i/><w:rFonts w:ascii="Calibri"/></w:rPr><w:t>dünya</w:t></w:r>
                </w:p>
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Tablo metni</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                <w:sectPr/>
              </w:body>
            </w:document>""".trimIndent()
        val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:styleId="Normal"><w:rPr><w:rFonts w:asciiTheme="minorHAnsi"/></w:rPr></w:style>
            </w:styles>""".trimIndent()
        val headerXml = """<?xml version="1.0" encoding="UTF-8"?>
            <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:p><w:r><w:t>Üstbilgi</w:t></w:r></w:p>
            </w:hdr>""".trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            mapOf(
                "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
                "word/document.xml" to documentXml,
                "word/styles.xml" to stylesXml,
                "word/header1.xml" to headerXml,
                "word/media/image1.bin" to "keep-me",
            ).forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
                zip.closeEntry()
            }
        }
    }
}
