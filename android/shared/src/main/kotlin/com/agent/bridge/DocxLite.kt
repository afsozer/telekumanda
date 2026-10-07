package com.agent.bridge

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

data class DocxCharStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val font: String = "",
    val sizeHalfPoints: Int = 0,
)

data class DocxSpan(val start: Int, val end: Int, val style: DocxCharStyle)

data class DocxBlock(
    val id: String,
    val kind: String,
    val text: String,
    val spans: List<DocxSpan> = emptyList(),
    val alignment: String = "left",
    val editable: Boolean = false,
    val detail: String = "",
)

data class DocxEditorState(
    val loading: Boolean = false,
    val name: String = "",
    val path: String = "",
    val blocks: List<DocxBlock> = emptyList(),
    val hash: String = "",
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val conflict: Boolean = false,
    val error: String = "",
    /** Elektronik imzalı belge — arayüzde tek bir şeritle bildirilir. */
    val signed: Boolean = false,
    /** İmzalayanların adları (varsa); imza DOĞRULANMAZ, yalnız okunur. */
    val signers: List<String> = emptyList(),
    /**
     * UDF ise çözümlenmiş belge: yazdırma düzeni görünümü blok listesi yerine
     * bunu çizer (sayfa, kenar boşluğu, sekme durakları, tablo ızgarası).
     */
    val udfDocument: com.agent.bridge.udf.UdfDocument? = null,
    /** Düzenleme kapalı: biçim çubuğu ve "PC'ye kaydet" gösterilmez. */
    val readOnly: Boolean = false,
    /**
     * Belge TELEFONDAKİ bir dosyadan açıldı (köprüden değil): dosya
     * yöneticisinden, WhatsApp'tan ya da e-postadan gelen .udf böyle gelir.
     * Kaydetme hedefi de o dosyanın kendisi — arayüz "PC'ye kaydet" demesin.
     */
    val phoneLocal: Boolean = false,
)

/**
 * Blok editörünün açabildiği belge paketi. DOCX ve UDF aynı editörü besler
 * (kullanıcı kararı 06.08.2026), bu yüzden ViewModel tek tip üzerinden çalışır.
 */
interface EditableDocumentPackage {
    fun blocks(): List<DocxBlock>
    fun updateParagraph(block: DocxBlock)
    fun applyFontToAll(font: String)
    fun toBytes(): ByteArray
    /** İmzalı belge düzenlemeye kapalıdır; DOCX'te imza kavramı yok. */
    val signed: Boolean get() = false
    /**
     * Belge yalnızca okunabiliyor: biçim çubuğu ve kaydetme gizlenir. UDF bu
     * durumda ([UdfPackage]); DOCX düzenlenebilir.
     */
    val readOnly: Boolean get() = false
}

class DocxLitePackage private constructor(
    private val entries: LinkedHashMap<String, ByteArray>,
    private val document: Document,
) : EditableDocumentPackage {
    private val editableParagraphs = LinkedHashMap<String, Element>()
    private var currentBlocks: List<DocxBlock> = scanBlocks()

    override fun blocks(): List<DocxBlock> = currentBlocks

    override fun updateParagraph(block: DocxBlock) {
        val paragraph = editableParagraphs[block.id] ?: return
        rewriteParagraph(document, paragraph, block)
        currentBlocks = currentBlocks.map { if (it.id == block.id) block else it }
    }

    override fun applyFontToAll(font: String) {
        val clean = font.trim().takeIf { it.isNotBlank() } ?: return
        setTextRunFonts(document, clean)
        entries.entries
            .filter { (name, _) -> name != DOCUMENT_XML && isWordTextPart(name) }
            .forEach { (name, raw) ->
                runCatching {
                    val part = parseXml(raw)
                    setTextRunFonts(part, clean)
                    entries[name] = xmlBytes(part)
                }
            }
        entries[STYLES_XML]?.let { raw ->
            runCatching {
                val styles = parseXml(raw)
                val nodes = styles.getElementsByTagNameNS(W_NS, "style")
                for (i in 0 until nodes.length) {
                    val style = nodes.item(i) as? Element ?: continue
                    val rPr = style.firstChildW("rPr") ?: styles.createElementNS(W_NS, "w:rPr")
                        .also { style.appendChild(it) }
                    setFontsElement(styles, rPr, clean)
                }
                val docDefaults = styles.getElementsByTagNameNS(W_NS, "docDefaults").item(0) as? Element
                val rPrDefault = docDefaults?.firstChildW("rPrDefault")
                    ?: docDefaults?.ownerDocument?.createElementNS(W_NS, "w:rPrDefault")?.also(docDefaults::appendChild)
                val defaultRPr = rPrDefault?.firstChildW("rPr")
                    ?: rPrDefault?.ownerDocument?.createElementNS(W_NS, "w:rPr")?.also(rPrDefault::appendChild)
                if (defaultRPr != null) setFontsElement(styles, defaultRPr, clean)
                entries[STYLES_XML] = xmlBytes(styles)
            }
        }
        // DOM düğümleri aynı kaldı; yeni font bilgisini UI modeline tekrar çıkar.
        currentBlocks = scanBlocks()
    }

    override fun toBytes(): ByteArray {
        entries[DOCUMENT_XML] = xmlBytes(document)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                zip.putNextEntry(entry)
                if (!name.endsWith('/')) zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun scanBlocks(): List<DocxBlock> {
        editableParagraphs.clear()
        val body = document.getElementsByTagNameNS(W_NS, "body").item(0) as? Element
            ?: throw IllegalArgumentException("DOCX document body bulunamadı")
        val result = mutableListOf<DocxBlock>()
        var index = 0
        var child = body.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val el = child as Element
                when (el.localName) {
                    "p" -> {
                        val id = "p${index++}"
                        val editable = isSimpleParagraph(el)
                        val block = parseParagraph(id, el, editable)
                        result += block
                        if (editable) editableParagraphs[id] = el
                    }
                    "tbl" -> result += DocxBlock(
                        id = "table${index++}",
                        kind = "table",
                        text = collectText(el).ifBlank { "[Tablo]" },
                        editable = false,
                        detail = "Tablo bu sürümde salt okunur; dosyada korunur.",
                    )
                    "sectPr" -> Unit
                    else -> result += DocxBlock(
                        id = "opaque${index++}",
                        kind = "complex",
                        text = collectText(el).ifBlank { "[Desteklenmeyen belge öğesi]" },
                        editable = false,
                        detail = "Bu öğe düzenlenmeden DOCX içinde korunur.",
                    )
                }
            }
            child = child.nextSibling
        }
        return result
    }

    companion object {
        private const val MAX_ENTRIES = 2_000
        private const val MAX_ENTRY_BYTES = 20 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 80 * 1024 * 1024

        fun open(bytes: ByteArray): DocxLitePackage {
            require(bytes.isNotEmpty()) { "DOCX boş" }
            val entries = LinkedHashMap<String, ByteArray>()
            var count = 0
            var total = 0L
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    require(count <= MAX_ENTRIES) { "DOCX çok fazla paket girdisi içeriyor" }
                    val data = readBounded(zip, MAX_ENTRY_BYTES)
                    total += data.size
                    require(total <= MAX_TOTAL_BYTES) { "DOCX açılmış boyutu çok büyük" }
                    entries[entry.name] = data
                    zip.closeEntry()
                }
            }
            val xml = entries[DOCUMENT_XML] ?: throw IllegalArgumentException("Geçerli DOCX document.xml bulunamadı")
            return DocxLitePackage(entries, parseXml(xml))
        }

        private fun readBounded(input: ZipInputStream, max: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                require(total <= max) { "DOCX paket girdisi çok büyük" }
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}

fun applyDocxStyle(
    block: DocxBlock,
    start: Int,
    end: Int,
    transform: (DocxCharStyle) -> DocxCharStyle,
): DocxBlock {
    if (!block.editable || block.text.isEmpty()) return block
    val from = minOf(start, end).coerceIn(0, block.text.length)
    val to = maxOf(start, end).coerceIn(from, block.text.length)
    if (from == to) return block
    val base = normalizedSpans(block)
    val out = mutableListOf<DocxSpan>()
    for (span in base) {
        if (span.end <= from || span.start >= to) {
            out += span
            continue
        }
        if (span.start < from) out += span.copy(end = from)
        out += DocxSpan(maxOf(span.start, from), minOf(span.end, to), transform(span.style))
        if (span.end > to) out += span.copy(start = to)
    }
    return block.copy(spans = mergeAdjacentSpans(out))
}

fun docxSelectionAll(
    block: DocxBlock,
    start: Int,
    end: Int,
    predicate: (DocxCharStyle) -> Boolean,
): Boolean {
    if (!block.editable || block.text.isEmpty()) return false
    val from = minOf(start, end).coerceIn(0, block.text.length)
    val to = maxOf(start, end).coerceIn(from, block.text.length)
    if (from == to) return false
    return normalizedSpans(block)
        .filter { it.end > from && it.start < to }
        .all { predicate(it.style) }
}

fun adjustDocxSpansForTextChange(block: DocxBlock, newText: String): List<DocxSpan> {
    val oldText = block.text
    if (oldText == newText) return block.spans
    var prefix = 0
    while (prefix < oldText.length && prefix < newText.length && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (
        suffix < oldText.length - prefix && suffix < newText.length - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) suffix++
    val oldEnd = oldText.length - suffix
    val newEnd = newText.length - suffix
    val delta = newEnd - oldEnd
    val normalized = normalizedSpans(block)
    val inheritedIndex = when {
        oldEnd == prefix && prefix > 0 -> prefix - 1
        prefix < oldText.length -> prefix
        prefix > 0 -> prefix - 1
        else -> 0
    }
    val inherited = normalized.firstOrNull { inheritedIndex >= it.start && inheritedIndex < it.end }?.style
        ?: DocxCharStyle()
    val out = mutableListOf<DocxSpan>()
    for (span in normalized) {
        when {
            span.end <= prefix -> out += span
            span.start >= oldEnd -> out += span.copy(start = span.start + delta, end = span.end + delta)
            else -> {
                if (span.start < prefix) out += span.copy(end = prefix)
                if (span.end > oldEnd) out += span.copy(start = newEnd, end = span.end + delta)
            }
        }
    }
    if (newEnd > prefix) out += DocxSpan(prefix, newEnd, inherited)
    return mergeAdjacentSpans(out.filter { it.start < it.end }.sortedBy { it.start })
}

private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
private const val DOCUMENT_XML = "word/document.xml"
private const val STYLES_XML = "word/styles.xml"

private fun isWordTextPart(name: String): Boolean {
    if (!name.startsWith("word/") || !name.endsWith(".xml")) return false
    val leaf = name.substringAfterLast('/')
    return leaf == "document.xml" ||
        leaf == "footnotes.xml" || leaf == "endnotes.xml" || leaf == "comments.xml" ||
        leaf.startsWith("header") || leaf.startsWith("footer")
}

private fun setTextRunFonts(document: Document, font: String) {
    val runs = document.getElementsByTagNameNS(W_NS, "r")
    for (i in 0 until runs.length) {
        val run = runs.item(i) as? Element ?: continue
        val hasText = run.getElementsByTagNameNS(W_NS, "t").length > 0 ||
            run.getElementsByTagNameNS(W_NS, "instrText").length > 0 ||
            run.getElementsByTagNameNS(W_NS, "tab").length > 0 ||
            run.getElementsByTagNameNS(W_NS, "br").length > 0 ||
            run.getElementsByTagNameNS(W_NS, "cr").length > 0
        if (hasText) setRunFont(document, run, font)
    }
}

private fun parseXml(bytes: ByteArray): Document {
    require(!containsXmlToken(bytes, "<!DOCTYPE") && !containsXmlToken(bytes, "<!ENTITY")) {
        "DOCX XML içinde DTD/entity kullanılamaz"
    }
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    // Android'in platform XML sağlayıcısı bazı JVM/Xerces feature URI'lerini
    // tanımıyor ve setFeature sırasında ParserConfigurationException fırlatıyor.
    // DTD yukarıda byte düzeyinde kesin reddedilir; desteklenen savunmalar da
    // best-effort etkinleştirilir. Böylece güvenlik korunurken Android'de açılış
    // yalnız desteklenmeyen bir feature adı yüzünden kesilmez.
    factory.tryFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.tryFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.tryFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.tryFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    factory.tryFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
    runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
    runCatching { factory.isXIncludeAware = false }
    runCatching { factory.isExpandEntityReferences = false }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> throw SAXException("Harici XML entity erişimi reddedildi") }
    return builder.parse(ByteArrayInputStream(bytes))
}

private fun DocumentBuilderFactory.tryFeature(name: String, enabled: Boolean) {
    runCatching { setFeature(name, enabled) }
}

private fun containsXmlToken(bytes: ByteArray, token: String): Boolean {
    val ascii = token.uppercase().toByteArray(Charsets.US_ASCII)
    val utf16Le = token.uppercase().toByteArray(Charsets.UTF_16LE)
    val utf16Be = token.uppercase().toByteArray(Charsets.UTF_16BE)
    fun contains(pattern: ByteArray): Boolean {
        if (pattern.isEmpty() || bytes.size < pattern.size) return false
        outer@ for (i in 0..bytes.size - pattern.size) {
            for (j in pattern.indices) {
                val actual = bytes[i + j].toInt() and 0xff
                val expected = pattern[j].toInt() and 0xff
                val folded = if (actual in 'a'.code..'z'.code) actual - 32 else actual
                if (folded != expected) continue@outer
            }
            return true
        }
        return false
    }
    return contains(ascii) || contains(utf16Le) || contains(utf16Be)
}

private fun xmlBytes(document: Document): ByteArray {
    val out = ByteArrayOutputStream()
    val factory = TransformerFactory.newInstance()
    runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
    val transformer = factory.newTransformer().apply {
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        setOutputProperty(OutputKeys.INDENT, "no")
    }
    transformer.transform(DOMSource(document), StreamResult(out))
    return out.toByteArray()
}

// Word'un YAZDIGI ama icerik TASIMAYAN isaretler. Paragrafi salt-okunura
// dusurmeleri yanlis pozitifti: 05.08.2026'da kullanicinin gercek dilekcelerinde
// olculdu — 685 paragrafin 27'si salt-okunur isaretlenmisti, 22'si
// lastRenderedPageBreak, 5'i proofErr yuzunden; hicbirinde gorsel/alan/baglanti
// yoktu. Ikisi de Word tarafindan yeniden uretilir (sayfa sonu ONBELLEGI ve
// yazim denetimi araligi), rewriteParagraph'ta dusmeleri icerik kaybi degildir.
private val DOCX_IGNORED_PARAGRAPH_CHILDREN = setOf("proofErr")
private val DOCX_IGNORED_RUN_CHILDREN = setOf("lastRenderedPageBreak")

private fun isSimpleParagraph(paragraph: Element): Boolean {
    var child = paragraph.firstChild
    while (child != null) {
        if (child.nodeType == Node.ELEMENT_NODE) {
            val el = child as Element
            if (el.localName in DOCX_IGNORED_PARAGRAPH_CHILDREN) { child = child.nextSibling; continue }
            if (el.localName !in setOf("pPr", "r")) return false
            if (el.localName == "r") {
                var runChild = el.firstChild
                while (runChild != null) {
                    if (runChild.nodeType == Node.ELEMENT_NODE &&
                        (runChild as Element).localName !in setOf("rPr", "t", "tab", "br", "cr") &&
                        runChild.localName !in DOCX_IGNORED_RUN_CHILDREN
                    ) return false
                    runChild = runChild.nextSibling
                }
            }
        }
        child = child.nextSibling
    }
    return true
}

private fun parseParagraph(id: String, paragraph: Element, editable: Boolean): DocxBlock {
    val text = StringBuilder()
    val spans = mutableListOf<DocxSpan>()
    val runs = paragraph.getElementsByTagNameNS(W_NS, "r")
    for (i in 0 until runs.length) {
        val run = runs.item(i) as? Element ?: continue
        val start = text.length
        var child = run.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val el = child as Element
                when (el.localName) {
                    "t" -> text.append(el.textContent)
                    "tab" -> text.append('\t')
                    "br", "cr" -> text.append('\n')
                }
            }
            child = child.nextSibling
        }
        if (text.length > start) spans += DocxSpan(start, text.length, parseRunStyle(run))
    }
    val alignment = paragraph.firstChildW("pPr")?.firstChildW("jc")?.attrW("val")
        ?.lowercase()?.takeIf { it in setOf("left", "center", "right", "both", "justify") } ?: "left"
    return DocxBlock(
        id = id,
        kind = if (editable) "paragraph" else "complex-paragraph",
        text = text.toString(),
        spans = mergeAdjacentSpans(spans),
        alignment = alignment,
        editable = editable,
        detail = if (editable) "" else "Görsel, alan veya bağlantı içeren bu paragraf salt okunur; dosyada korunur.",
    )
}

private fun parseRunStyle(run: Element): DocxCharStyle {
    val rPr = run.firstChildW("rPr") ?: return DocxCharStyle()
    val fonts = rPr.firstChildW("rFonts")
    val size = rPr.firstChildW("sz")?.attrW("val")?.toIntOrNull() ?: 0
    return DocxCharStyle(
        bold = rPr.firstChildW("b")?.isOn() == true,
        italic = rPr.firstChildW("i")?.isOn() == true,
        underline = rPr.firstChildW("u")?.attrW("val")?.lowercase()?.let { it !in setOf("", "none", "false", "0") } == true,
        font = listOf("ascii", "hAnsi", "eastAsia", "cs").firstNotNullOfOrNull { key ->
            fonts?.attrW(key)?.takeIf { it.isNotBlank() }
        }.orEmpty(),
        sizeHalfPoints = size,
    )
}

private fun rewriteParagraph(document: Document, paragraph: Element, block: DocxBlock) {
    val pPr = paragraph.firstChildW("pPr") ?: document.createElementNS(W_NS, "w:pPr")
        .also { paragraph.insertBefore(it, paragraph.firstChild) }
    var jc = pPr.firstChildW("jc")
    if (block.alignment == "left") {
        if (jc != null) pPr.removeChild(jc)
    } else {
        val jcNode = jc ?: document.createElementNS(W_NS, "w:jc").also { pPr.appendChild(it) }
        jcNode.setAttributeNS(W_NS, "w:val", if (block.alignment == "justify") "both" else block.alignment)
    }
    val remove = mutableListOf<Node>()
    var child = paragraph.firstChild
    while (child != null) {
        if (child !== pPr) remove += child
        child = child.nextSibling
    }
    remove.forEach(paragraph::removeChild)

    val spans = normalizedSpans(block)
    if (block.text.isEmpty()) {
        paragraph.appendChild(document.createElementNS(W_NS, "w:r").also { run ->
            run.appendChild(document.createElementNS(W_NS, "w:t"))
        })
        return
    }
    for (span in spans) {
        val run = document.createElementNS(W_NS, "w:r")
        appendRunProperties(document, run, span.style)
        appendRunText(document, run, block.text.substring(span.start, span.end))
        paragraph.appendChild(run)
    }
}

private fun appendRunProperties(document: Document, run: Element, style: DocxCharStyle) {
    if (!style.bold && !style.italic && !style.underline && style.font.isBlank() && style.sizeHalfPoints <= 0) return
    val rPr = document.createElementNS(W_NS, "w:rPr")
    if (style.bold) rPr.appendChild(document.createElementNS(W_NS, "w:b"))
    if (style.italic) rPr.appendChild(document.createElementNS(W_NS, "w:i"))
    if (style.underline) rPr.appendChild(document.createElementNS(W_NS, "w:u").also { it.setAttributeNS(W_NS, "w:val", "single") })
    if (style.font.isNotBlank()) setFontsElement(document, rPr, style.font)
    if (style.sizeHalfPoints > 0) {
        rPr.appendChild(document.createElementNS(W_NS, "w:sz").also { it.setAttributeNS(W_NS, "w:val", style.sizeHalfPoints.toString()) })
        rPr.appendChild(document.createElementNS(W_NS, "w:szCs").also { it.setAttributeNS(W_NS, "w:val", style.sizeHalfPoints.toString()) })
    }
    run.appendChild(rPr)
}

private fun appendRunText(document: Document, run: Element, text: String) {
    val part = StringBuilder()
    fun flush() {
        if (part.isEmpty()) return
        val value = part.toString()
        val node = document.createElementNS(W_NS, "w:t")
        if (value.firstOrNull()?.isWhitespace() == true || value.lastOrNull()?.isWhitespace() == true) {
            node.setAttributeNS(XML_NS, "xml:space", "preserve")
        }
        node.textContent = value
        run.appendChild(node)
        part.clear()
    }
    text.forEach { ch ->
        when (ch) {
            '\t' -> { flush(); run.appendChild(document.createElementNS(W_NS, "w:tab")) }
            '\n' -> { flush(); run.appendChild(document.createElementNS(W_NS, "w:br")) }
            else -> part.append(ch)
        }
    }
    flush()
}

private fun normalizedSpans(block: DocxBlock): List<DocxSpan> {
    if (block.text.isEmpty()) return emptyList()
    val source = block.spans.filter { it.start < it.end }.sortedBy { it.start }
    val out = mutableListOf<DocxSpan>()
    var cursor = 0
    for (span in source) {
        val start = span.start.coerceIn(cursor, block.text.length)
        val end = span.end.coerceIn(start, block.text.length)
        if (start > cursor) out += DocxSpan(cursor, start, DocxCharStyle())
        if (end > start) out += DocxSpan(start, end, span.style)
        cursor = end
    }
    if (cursor < block.text.length) out += DocxSpan(cursor, block.text.length, DocxCharStyle())
    return mergeAdjacentSpans(out)
}

private fun mergeAdjacentSpans(spans: List<DocxSpan>): List<DocxSpan> {
    val out = mutableListOf<DocxSpan>()
    for (span in spans.filter { it.start < it.end }.sortedBy { it.start }) {
        val last = out.lastOrNull()
        if (last != null && last.end == span.start && last.style == span.style) {
            out[out.lastIndex] = last.copy(end = span.end)
        } else out += span
    }
    return out
}

private fun collectText(element: Element): String {
    val nodes = element.getElementsByTagNameNS(W_NS, "t")
    return buildString {
        for (i in 0 until nodes.length) {
            if (isNotEmpty()) append(' ')
            append(nodes.item(i).textContent)
        }
    }
}

private fun setRunFont(document: Document, run: Element, font: String) {
    val rPr = run.firstChildW("rPr") ?: document.createElementNS(W_NS, "w:rPr")
        .also { run.insertBefore(it, run.firstChild) }
    setFontsElement(document, rPr, font)
}

private fun setFontsElement(document: Document, rPr: Element, font: String) {
    val fonts = rPr.firstChildW("rFonts") ?: document.createElementNS(W_NS, "w:rFonts")
        .also { rPr.insertBefore(it, rPr.firstChild) }
    for (key in listOf("ascii", "hAnsi", "eastAsia", "cs")) fonts.setAttributeNS(W_NS, "w:$key", font)
    for (key in listOf("asciiTheme", "hAnsiTheme", "eastAsiaTheme", "cstheme")) fonts.removeAttributeNS(W_NS, key)
}

private fun Element.firstChildW(local: String): Element? {
    var child = firstChild
    while (child != null) {
        if (child.nodeType == Node.ELEMENT_NODE && child.namespaceURI == W_NS && child.localName == local) return child as Element
        child = child.nextSibling
    }
    return null
}

private fun Element.attrW(local: String): String =
    getAttributeNS(W_NS, local).ifBlank { getAttribute("w:$local") }

private fun Element.isOn(): Boolean = attrW("val").lowercase() !in setOf("false", "0", "off")
