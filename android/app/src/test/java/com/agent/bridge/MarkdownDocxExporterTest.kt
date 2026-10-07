package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class MarkdownDocxExporterTest {

    @Test
    fun exportsMarkdownFormattingAlignmentAndBulletsToReadableDocx() {
        val bytes = MarkdownDocxExporter.export(
            """# Başlık
                |<div align="center">
                |**Kalın** ve *italik* <u>altı çizili</u>
                |</div>
                |- Birinci madde
            """.trimMargin(),
        )

        val docx = DocxLitePackage.open(bytes)
        val blocks = docx.blocks().filter { it.editable }

        assertEquals("Başlık", blocks[0].text)
        assertTrue(blocks[0].spans.all { it.style.bold })
        assertEquals(32, blocks[0].spans.first().style.sizeHalfPoints)
        assertEquals("center", blocks[1].alignment)
        assertTrue(blocks[1].spans.any { it.style.bold && it.textIn(blocks[1]) == "Kalın" })
        assertTrue(blocks[1].spans.any { it.style.italic && it.textIn(blocks[1]) == "italik" })
        assertTrue(blocks[1].spans.any { it.style.underline && it.textIn(blocks[1]) == "altı çizili" })
        assertTrue(blocks.flatMap { it.spans }.all { it.style.font == "Times New Roman" })
        assertTrue(blocks.last().text.startsWith("• "))
    }

    @Test
    fun exportsACompleteDocxPackage() {
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(MarkdownDocxExporter.export("Merhaba"))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.closeEntry()
            }
        }

        assertTrue("[Content_Types].xml" in names)
        assertTrue("_rels/.rels" in names)
        assertTrue("word/document.xml" in names)
        assertTrue("word/styles.xml" in names)
        assertTrue("word/_rels/document.xml.rels" in names)
    }

    // Canlıda görülen üç kusur (07.08): not dosyasının frontmatter'ı Word
    // belgesinin tepesine düşüyor, alıntı satırları ham ">" ile geliyor ve
    // dosya adındaki alt çizgiler italik sanılıp yutuluyordu.
    @Test
    fun frontmatterVeAlintiIsaretleriBelgeyeGirmez() {
        val bytes = MarkdownDocxExporter.export(
            """---
                |title: "Açıköğretim ve Ünvan Değişikliği"
                |source_screenshot: Screenshot_20260807_191420_com_agent_bridge.jpg
                |---
                |Özet paragrafı.
                |
                |## Ekrandaki metin
                |
                |> Alıntının ilk satırı
                |>
                |> Alıntının ikinci satırı
            """.trimMargin(),
        )

        val metinler = DocxLitePackage.open(bytes).blocks().map { it.text }
        assertTrue("frontmatter girmemeli: $metinler", metinler.none { it.contains("title:") })
        assertTrue("kaynak alanı girmemeli", metinler.none { it.contains("source_screenshot") })
        assertTrue("ham > işareti kalmamalı", metinler.none { it.trimStart().startsWith(">") })
        assertEquals("Özet paragrafı.", metinler.first { it.isNotBlank() })
        assertTrue("alıntı metni durmalı", metinler.any { it == "Alıntının ikinci satırı" })
    }

    @Test
    fun kelimeIcindekiAltCizgiItalikSayilmaz() {
        val bytes = MarkdownDocxExporter.export("Kaynak: Screenshot_2026_08_07.jpg dosyası")
        val block = DocxLitePackage.open(bytes).blocks().first { it.text.isNotBlank() }

        assertEquals("Kaynak: Screenshot_2026_08_07.jpg dosyası", block.text)
        assertTrue("italik olmamalı", block.spans.none { it.style.italic })
    }

    @Test
    fun ardArdaBosSatirlarTekBosParagrafaIner() {
        val bytes = MarkdownDocxExporter.export("Birinci\n\n\n\nİkinci")
        val metinler = DocxLitePackage.open(bytes).blocks().map { it.text }

        assertEquals(listOf("Birinci", "", "İkinci"), metinler)
    }

    private fun DocxSpan.textIn(block: DocxBlock): String = block.text.substring(start, end)
}
