package com.agent.bridge

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private const val DOCX_W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val DOCX_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val DOCX_XML_NS = "http://www.w3.org/XML/1998/namespace"

/**
 * Dosya başındaki YAML frontmatter'ı ("---\ntitle: …\n---") söker.
 *
 * Ortak yerde: hem önizleme hem DOCX aktarımı kullanıyor. Aktarımda YOKTU ve
 * `title:` / `source_screenshot:` satırları Word belgesinin tepesine düşüyordu
 * (07.08'de canlı görüldü); üstelik dosya adındaki alt çizgiler markdown
 * italiği sanılıp metni de bozuyordu.
 */
fun stripMarkdownFrontmatter(md: String): String {
    if (!md.startsWith("---")) return md
    val lines = md.lines()
    if (lines.firstOrNull()?.trim() != "---") return md
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) return md
    return lines.drop(end + 2).joinToString("\n").trimStart('\n')
}

object MarkdownDocxExporter {
    private const val DEFAULT_FONT = "Times New Roman"
    // Alıntı paragrafının sol girintisi (twip; 360 = 0,25 inç).
    private const val QUOTE_INDENT = "360"

    fun export(markdown: String): ByteArray {
        val document = newDocument()
        val root = document.createElementNS(DOCX_W_NS, "w:document").also {
            it.setAttribute("xmlns:r", DOCX_REL_NS)
            document.appendChild(it)
        }
        val body = document.createElementNS(DOCX_W_NS, "w:body").also(root::appendChild)
        var alignment = "left"
        val lines = stripMarkdownFrontmatter(markdown)
            .replace("\r\n", "\n").replace('\r', '\n').split('\n')
        // Boş paragraf sayacı: markdown'da ard arda gelen boş satırlar (ve
        // gövdesi boş "> " alıntı satırları) Word'de üst üste boş paragraf
        // yığıyordu. Biri tutulur, gerisi atılır; baştaki ve sondaki hiç yazılmaz.
        var bosBekliyor = false
        var yazilanVar = false
        for (raw in lines) {
            val trimmed = raw.trim()
            val openedAlignment = parseAlignmentOpen(trimmed)
            if (openedAlignment != null) {
                alignment = openedAlignment
                continue
            }
            if (trimmed.equals("</div>", ignoreCase = true)) {
                alignment = "left"
                continue
            }
            // Yatay çizgi Word'de karşılıksız; boş paragrafa çevirmektense atlanır.
            if (Regex("""^\s*([-*_])\s*(\1\s*){2,}$""").matches(raw)) continue
            val block = parseBlock(raw)
            if (block.text.isBlank()) {
                bosBekliyor = true
                continue
            }
            if (bosBekliyor && yazilanVar) {
                appendParagraph(document, body, "", alignment, 0, quote = false)
            }
            bosBekliyor = false
            yazilanVar = true
            appendParagraph(document, body, block.text, alignment, block.headingLevel, block.quote)
        }
        body.appendChild(document.createElementNS(DOCX_W_NS, "w:sectPr"))

        val entries = linkedMapOf(
            "[Content_Types].xml" to contentTypes().toByteArray(),
            "_rels/.rels" to rootRelationships().toByteArray(),
            "word/document.xml" to xmlBytes(document),
            "word/styles.xml" to stylesXml().toByteArray(),
            "word/_rels/document.xml.rels" to documentRelationships().toByteArray(),
        )
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun appendParagraph(
        document: Document,
        body: Element,
        text: String,
        alignment: String,
        headingLevel: Int,
        quote: Boolean,
    ) {
        val paragraph = document.createElementNS(DOCX_W_NS, "w:p").also(body::appendChild)
        val pPr = document.createElementNS(DOCX_W_NS, "w:pPr").also(paragraph::appendChild)
        // Alıntı: Word'de ">" diye bir işaret yok, girintiyle gösterilir.
        if (quote) {
            pPr.appendChild(document.createElementNS(DOCX_W_NS, "w:ind").also {
                it.setAttributeNS(DOCX_W_NS, "w:left", QUOTE_INDENT)
            })
        }
        if (alignment != "left") {
            pPr.appendChild(document.createElementNS(DOCX_W_NS, "w:jc").also {
                it.setAttributeNS(DOCX_W_NS, "w:val", alignment)
            })
        }
        if (headingLevel > 0) {
            pPr.appendChild(document.createElementNS(DOCX_W_NS, "w:pStyle").also {
                it.setAttributeNS(DOCX_W_NS, "w:val", "Heading$headingLevel")
            })
        }
        val runs = parseInline(text)
        if (runs.isEmpty()) {
            paragraph.appendChild(document.createElementNS(DOCX_W_NS, "w:r").also { run ->
                run.appendChild(document.createElementNS(DOCX_W_NS, "w:t"))
            })
            return
        }
        for (part in runs) {
            val run = document.createElementNS(DOCX_W_NS, "w:r").also(paragraph::appendChild)
            val rPr = document.createElementNS(DOCX_W_NS, "w:rPr").also(run::appendChild)
            rPr.appendChild(document.createElementNS(DOCX_W_NS, "w:rFonts").also { fonts ->
                listOf("ascii", "hAnsi", "eastAsia", "cs").forEach {
                    fonts.setAttributeNS(DOCX_W_NS, "w:$it", DEFAULT_FONT)
                }
            })
            if (part.bold || headingLevel > 0) rPr.appendChild(document.createElementNS(DOCX_W_NS, "w:b"))
            if (part.italic) rPr.appendChild(document.createElementNS(DOCX_W_NS, "w:i"))
            if (part.underline) rPr.appendChild(document.createElementNS(DOCX_W_NS, "w:u").also {
                it.setAttributeNS(DOCX_W_NS, "w:val", "single")
            })
            if (headingLevel > 0) {
                val halfPoints = when (headingLevel) { 1 -> 32; 2 -> 28; else -> 24 }
                rPr.appendChild(document.createElementNS(DOCX_W_NS, "w:sz").also {
                    it.setAttributeNS(DOCX_W_NS, "w:val", halfPoints.toString())
                })
            }
            run.appendChild(document.createElementNS(DOCX_W_NS, "w:t").also { node ->
                if (part.text.firstOrNull()?.isWhitespace() == true || part.text.lastOrNull()?.isWhitespace() == true) {
                    node.setAttributeNS(DOCX_XML_NS, "xml:space", "preserve")
                }
                node.textContent = part.text
            })
        }
    }

    private data class InlinePart(
        val text: String,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
    )

    private fun parseInline(text: String): List<InlinePart> {
        val out = mutableListOf<InlinePart>()
        val buffer = StringBuilder()
        var bold = false
        var italic = false
        var underline = false
        fun flush() {
            if (buffer.isNotEmpty()) {
                out += InlinePart(buffer.toString(), bold, italic, underline)
                buffer.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            when {
                text.regionMatches(i, "<u>", 0, 3, ignoreCase = true) -> {
                    flush(); underline = true; i += 3
                }
                text.regionMatches(i, "</u>", 0, 4, ignoreCase = true) -> {
                    flush(); underline = false; i += 4
                }
                text.startsWith("**", i) -> {
                    flush(); bold = !bold; i += 2
                }
                text.startsWith("__", i) && !kelimeIci(text, i, 2) -> {
                    flush(); bold = !bold; i += 2
                }
                text[i] == '*' -> {
                    flush(); italic = !italic; i++
                }
                // Kelime İÇİNDEKİ alt çizgi biçim işareti DEĞİLDİR (markdown'ın
                // kendi kuralı). Bu kontrol yokken `source_screenshot` ve
                // `Screenshot_2026_…` gibi adlar italiğe dönüp alt çizgileri
                // yutuluyordu — DOCX çıktısında canlı görüldü (07.08).
                text[i] == '_' && !kelimeIci(text, i, 1) -> {
                    flush(); italic = !italic; i++
                }
                text[i] == '\\' && i + 1 < text.length -> {
                    buffer.append(text[i + 1]); i += 2
                }
                else -> buffer.append(text[i++])
            }
        }
        flush()
        return out
    }

    // İşaretin iki yanı da harf/rakamsa kelime içindeyiz.
    private fun kelimeIci(text: String, start: Int, len: Int): Boolean {
        val onceki = text.getOrNull(start - 1) ?: return false
        val sonraki = text.getOrNull(start + len) ?: return false
        return onceki.isLetterOrDigit() && sonraki.isLetterOrDigit()
    }

    private fun parseAlignmentOpen(line: String): String? {
        val match = Regex("""^<div\s+align=[\"'](left|center|right)[\"']\s*>$""", RegexOption.IGNORE_CASE)
            .matchEntire(line) ?: return null
        return match.groupValues[1].lowercase()
    }

    private data class Block(val text: String, val headingLevel: Int, val quote: Boolean)

    private fun parseBlock(line: String): Block {
        // Alıntı işareti önce sökülür: "> ## Başlık" gibi iç içe yazımda
        // başlık da tanınsın. Yalnız ">" olan satır boş alıntı satırıdır.
        var body = line
        var quote = false
        val quoteMatch = Regex("""^\s{0,3}>\s?(.*)$""").matchEntire(body)
        if (quoteMatch != null) {
            quote = true
            body = quoteMatch.groupValues[1]
        }
        val heading = Regex("""^\s{0,3}(#{1,3})\s+(.*)$""").matchEntire(body)
        if (heading != null) {
            return Block(heading.groupValues[2], heading.groupValues[1].length, quote)
        }
        val bullet = Regex("""^\s*[-+*]\s+(.*)$""").matchEntire(body)
        if (bullet != null) return Block("• ${bullet.groupValues[1]}", 0, quote)
        return Block(body, 0, quote)
    }

    private fun newDocument(): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().newDocument()

    private fun xmlBytes(document: Document): ByteArray {
        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(document), StreamResult(out))
        return out.toByteArray()
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
          <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
        </Types>""".trimIndent()

    private fun rootRelationships() = """<?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>""".trimIndent()

    private fun documentRelationships() = """<?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>""".trimIndent()

    private fun stylesXml() = """<?xml version="1.0" encoding="UTF-8"?>
        <w:styles xmlns:w="$DOCX_W_NS">
          <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="$DEFAULT_FONT" w:hAnsi="$DEFAULT_FONT" w:eastAsia="$DEFAULT_FONT" w:cs="$DEFAULT_FONT"/></w:rPr></w:rPrDefault></w:docDefaults>
          <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
          <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:qFormat/></w:style>
          <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:qFormat/></w:style>
          <w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:qFormat/></w:style>
        </w:styles>""".trimIndent()
}
