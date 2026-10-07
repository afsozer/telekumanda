package com.agent.bridge.udf

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

object UdfParser {
    private const val UDF_EDITOR_VERSION = "android-compat-1"
    private const val CONTENT_ENTRY = "content.xml"
    private const val SIGNATURE_ENTRY = "sign.sgn"
    private const val DOCUMENT_PROPERTIES_ENTRY = "documentproperties.xml"
    private val GENERATED_ENTRIES = setOf(CONTENT_ENTRY, SIGNATURE_ENTRY, DOCUMENT_PROPERTIES_ENTRY)

    // Zip bombası sınırları (DocxLite ile aynı desen). UDF dışarıdan geliyor
    // (WhatsApp, e-posta, dosya yöneticisi): birkaç KB'lık bir zip açılınca
    // gigabaytlara şişip uygulamayı belleksiz bırakabilir. Sınır AÇILMIŞ bayta
    // uygulanır; zip başlığındaki boyut alanına güvenilmez (sahtelenebilir).
    private const val MAX_ENTRIES = 2_000
    private const val MAX_ENTRY_BYTES = 20 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 80 * 1024 * 1024L

    fun readUdf(file: File): UdfDocument {
        return file.inputStream().use { readUdf(it, file.absolutePath) }
    }

    fun readUdf(inputStream: InputStream, filePath: String? = null): UdfDocument {
        var textContent = ""
        val paragraphs = mutableListOf<UdfParagraph>()
        val styles = mutableListOf<UdfStyle>()
        var pageFormat = UdfPageFormat()
        var signatureBytes: ByteArray? = null
        var isSigned = false
        var contentXmlFound = false
        var parseError: Exception? = null
        val extraParts = mutableListOf<UdfPart>()
        val extraElementXml = mutableListOf<String>()
        val body = mutableListOf<UdfBodyNode>()
        val blocks = mutableListOf<UdfBlock>()

        var entryCount = 0
        var totalBytes = 0L
        fun readEntry(zip: ZipInputStream): ByteArray {
            val data = readBounded(zip, MAX_ENTRY_BYTES)
            totalBytes += data.size
            require(totalBytes <= MAX_TOTAL_BYTES) { "UDF açılmış boyutu çok büyük" }
            return data
        }

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "UDF çok fazla paket girdisi içeriyor" }
                val entryName = entry.name
                when {
                    entryName == CONTENT_ENTRY -> {
                        contentXmlFound = true
                        // Sınır aşımı içerik hatası sayılıp yutulmasın, hemen kessin.
                        val contentBytes = readEntry(zip)
                        try {
                            val xmlDoc = parseXml(contentBytes)
                            val contentNode = xmlDoc.getElementsByTagName("content").item(0)
                            if (contentNode is Element) {
                                textContent = getElementText(contentNode)
                            }

                            val pageFormatNode = xmlDoc.getElementsByTagName("pageFormat").item(0)
                            if (pageFormatNode is Element) {
                                pageFormat = UdfPageFormat(
                                    mediaSizeName = pageFormatNode.getAttribute("mediaSizeName").ifEmpty { "1" },
                                    leftMargin = pageFormatNode.getAttribute("leftMargin").toFloatOrNull() ?: UdfUnits.DEFAULT_MARGIN_PT,
                                    rightMargin = pageFormatNode.getAttribute("rightMargin").toFloatOrNull() ?: UdfUnits.DEFAULT_MARGIN_PT,
                                    topMargin = pageFormatNode.getAttribute("topMargin").toFloatOrNull() ?: UdfUnits.DEFAULT_MARGIN_PT,
                                    bottomMargin = pageFormatNode.getAttribute("bottomMargin").toFloatOrNull() ?: UdfUnits.DEFAULT_MARGIN_PT,
                                    paperOrientation = pageFormatNode.getAttribute("paperOrientation").ifEmpty { "1" },
                                    headerFOffset = pageFormatNode.getAttribute("headerFOffset").toFloatOrNull(),
                                    footerFOffset = pageFormatNode.getAttribute("footerFOffset").toFloatOrNull(),
                                    extraAttributes = pageFormatNode.extraAttributes(
                                        "mediaSizeName",
                                        "leftMargin",
                                        "rightMargin",
                                        "topMargin",
                                        "bottomMargin",
                                        "paperOrientation",
                                        "headerFOffset",
                                        "footerFOffset"
                                    )
                                )
                            }

                            val styleNodes = xmlDoc.getElementsByTagName("style")
                            for (i in 0 until styleNodes.length) {
                                val styleNode = styleNodes.item(i)
                                if (styleNode is Element) {
                                    styles.add(
                                        UdfStyle(
                                            name = styleNode.getAttribute("name").ifEmpty { "hvl-default" },
                                            description = styleNode.getAttribute("description"),
                                            size = styleNode.getAttribute("size").toIntOrNull() ?: 12,
                                            family = styleNode.getAttribute("family").ifEmpty { "Times New Roman" }
                                        )
                                    )
                                }
                            }

                            val dataNode = xmlDoc.documentElement.childElements("data").firstOrNull()
                            val fieldData = dataNode?.buildFieldDataMap() ?: emptyMap()

                            val elementsNode = xmlDoc.documentElement.childElements("elements").firstOrNull()
                            elementsNode?.readBodyElements(textContent, paragraphs, extraElementXml, blocks, body, fieldData)
                        } catch (e: Exception) {
                            parseError = e
                        }
                    }
                    entryName == SIGNATURE_ENTRY -> {
                        signatureBytes = readEntry(zip)
                        isSigned = true
                    }
                    entryName != DOCUMENT_PROPERTIES_ENTRY && !entry.isDirectory && entryName.isNotBlank() -> {
                        extraParts.add(UdfPart(entryName, readEntry(zip)))
                    }
                }
                entry = zip.nextEntry
            }
        }

        parseError?.let { throw IllegalArgumentException("Invalid UDF content.xml", it) }
        if (!contentXmlFound) {
            throw IllegalArgumentException("Invalid UDF: missing content.xml")
        }

        return UdfDocument(
            filePath = filePath,
            text = textContent,
            paragraphs = paragraphs,
            styles = styles,
            pageFormat = pageFormat,
            signatureBytes = signatureBytes,
            isSigned = isSigned,
            extraParts = extraParts,
            extraElementXml = extraElementXml,
            body = body,
            blocks = blocks
        )
    }

    fun writeUdf(outputStream: OutputStream, doc: UdfDocument) {
        ZipOutputStream(outputStream).use { zip ->
            zip.putNextEntry(ZipEntry(CONTENT_ENTRY))
            zip.write(buildContentXml(doc).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(DOCUMENT_PROPERTIES_ENTRY))
            zip.write(buildDocumentPropertiesXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            if (doc.isSigned && doc.signatureBytes != null) {
                zip.putNextEntry(ZipEntry(SIGNATURE_ENTRY))
                zip.write(doc.signatureBytes)
                zip.closeEntry()
            }

            for (part in doc.extraParts) {
                if (part.name in GENERATED_ENTRIES || part.name.isBlank()) continue
                zip.putNextEntry(ZipEntry(part.name))
                zip.write(part.bytes)
                zip.closeEntry()
            }
        }
    }

    private fun buildDocumentPropertiesXml(): String {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
            <properties>
                <entry key="author">Android UDF Editor</entry>
                <entry key="creationDate">${System.currentTimeMillis()}</entry>
                <entry key="editorVersion">$UDF_EDITOR_VERSION</entry>
            </properties>
        """.trimIndent()
    }

    private fun buildContentXml(doc: UdfDocument): String {
        val dbFactory = secureDocumentBuilderFactory()
        val dBuilder = dbFactory.newDocumentBuilder()
        val xmlDoc = dBuilder.newDocument()

        val rootElement = xmlDoc.createElement("template")
        rootElement.setAttribute("format_id", "1.8")
        xmlDoc.appendChild(rootElement)

        // Gövde metni ve ofsetler AŞAĞIDA, öğeler yazılırken kurulur; CDATA en
        // sonda doldurulur. Tek bir harf değişse bile bütün ofsetler kaydığı
        // için ikisini ayrı üretmek belgeyi sessizce bozardı.
        val contentElement = xmlDoc.createElement("content")
        rootElement.appendChild(contentElement)
        val bodyText = StringBuilder()

        val propertiesElement = xmlDoc.createElement("properties")
        val pageFormatElement = xmlDoc.createElement("pageFormat")
        pageFormatElement.setAttribute("mediaSizeName", doc.pageFormat.mediaSizeName)
        pageFormatElement.setAttribute("leftMargin", doc.pageFormat.leftMargin.toString())
        pageFormatElement.setAttribute("rightMargin", doc.pageFormat.rightMargin.toString())
        pageFormatElement.setAttribute("topMargin", doc.pageFormat.topMargin.toString())
        pageFormatElement.setAttribute("bottomMargin", doc.pageFormat.bottomMargin.toString())
        pageFormatElement.setAttribute("paperOrientation", doc.pageFormat.paperOrientation)
        doc.pageFormat.headerFOffset?.let { pageFormatElement.setAttribute("headerFOffset", it.toString()) }
        doc.pageFormat.footerFOffset?.let { pageFormatElement.setAttribute("footerFOffset", it.toString()) }
        pageFormatElement.applyExtraAttributes(doc.pageFormat.extraAttributes)
        propertiesElement.appendChild(pageFormatElement)
        rootElement.appendChild(propertiesElement)

        val stylesElement = xmlDoc.createElement("styles")
        val stylesToUse = doc.styles.ifEmpty {
            listOf(
                UdfStyle(name = "default", description = "Default"),
                UdfStyle(name = "hvl-default", description = "Gövde")
            )
        }
        for (style in stylesToUse) {
            val styleElement = xmlDoc.createElement("style")
            styleElement.setAttribute("name", style.name)
            styleElement.setAttribute("description", style.description)
            styleElement.setAttribute("size", style.size.toString())
            styleElement.setAttribute("family", style.family)
            stylesElement.appendChild(styleElement)
        }
        rootElement.appendChild(stylesElement)

        val elementsElement = xmlDoc.createElement("elements")
        elementsElement.setAttribute("resolver", "hvl-default")

        // Gövde belge SIRASINA göre yürünür. Eskiden önce bütün paragraflar,
        // sonra ham XML'ler yazılıyordu; araya giren bir tablo belgenin sonuna
        // düşüyordu.
        val nodes = doc.body.ifEmpty {
            doc.paragraphs.map { UdfParagraphNode(it) } + doc.extraElementXml.map { UdfRawNode(it) }
        }
        for (node in nodes) {
            when (node) {
                is UdfParagraphNode -> {
                    val paragraph = node.paragraph
                    val paragraphElement = xmlDoc.createElement("paragraph")
                    paragraphElement.setAttribute("Alignment", paragraph.alignment.toString())
                    paragraphElement.setAttribute("resolver", paragraph.resolver)
                    paragraph.lineSpacing?.let { paragraphElement.setAttribute("LineSpacing", it.toString()) }
                    paragraph.leftIndent?.let { paragraphElement.setAttribute("LeftIndent", it.toUdfNumber()) }
                    paragraph.rightIndent?.let { paragraphElement.setAttribute("RightIndent", it.toUdfNumber()) }
                    paragraph.firstLineIndent?.let { paragraphElement.setAttribute("FirstLineIndent", it.toUdfNumber()) }
                    paragraphElement.applyExtraAttributes(paragraph.extraAttributes)

                    for (element in paragraph.elements) {
                        // Etiket adı KORUNUR: field/tab'i content'e çevirmek şablon
                        // alan bağını ve sekmeyi öldürüyordu.
                        val runElement = xmlDoc.createElement(element.kind.tag)
                        val raw = element.rawText.ifEmpty { element.textRun }
                        runElement.setAttribute("startOffset", bodyText.length.toString())
                        runElement.setAttribute("length", raw.length.toString())
                        bodyText.append(raw)
                        if (element.bold) runElement.setAttribute("bold", "true")
                        if (element.italic) runElement.setAttribute("italic", "true")
                        if (element.underline) runElement.setAttribute("underline", "true")
                        if (element.strikethrough) runElement.setAttribute("strikethrough", "true")
                        if (element.subscript) runElement.setAttribute("subscript", "true")
                        if (element.superscript) runElement.setAttribute("superscript", "true")
                        runElement.setAttribute("size", element.size.toString())
                        runElement.setAttribute("family", element.family)
                        element.color?.let { runElement.setAttribute("color", it) }
                        runElement.applyExtraAttributes(element.extraAttributes)
                        paragraphElement.appendChild(runElement)
                    }
                    elementsElement.appendChild(paragraphElement)
                }

                is UdfRawNode -> parseXmlElement(xmlDoc, node.xml)?.let { rawElement ->
                    // Ham öğe (tablo, altbilgi…) hiç modellenmez, olduğu gibi geri
                    // yazılır — yalnız içindeki çalıştırmaların ofsetleri yeni
                    // gövdeye göre tazelenir. Dilimler OKUNAN gövdeden alınır.
                    reoffsetRawRuns(rawElement, doc.text, bodyText)
                    elementsElement.appendChild(rawElement)
                }
            }
        }
        rootElement.appendChild(elementsElement)
        contentElement.appendChild(xmlDoc.createCDATASection(bodyText.toString()))

        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        val output = ByteArrayOutputStream()
        transformer.transform(DOMSource(xmlDoc), StreamResult(output))
        return output.toString("UTF-8")
    }

    /**
     * Ham XML öğesinin içindeki çalıştırmaları yeni gövdeye taşır: her
     * çalıştırmanın metni ESKİ gövdeden eski ofsetiyle kesilir, yeni gövdeye
     * eklenir ve ofsetleri güncellenir. Öğenin kendisine (öznitelikler, yapı,
     * iç içe tablolar) hiç dokunulmaz.
     */
    private fun reoffsetRawRuns(element: Element, originalText: String, bodyText: StringBuilder) {
        if (element.nodeName in RUN_TAGS) {
            val oldStart = element.getAttribute("startOffset").toIntOrNull() ?: 0
            val oldLength = element.getAttribute("length").toIntOrNull() ?: 0
            val raw = if (oldStart >= 0 && oldStart + oldLength <= originalText.length) {
                originalText.substring(oldStart, oldStart + oldLength)
            } else {
                ""
            }
            element.setAttribute("startOffset", bodyText.length.toString())
            element.setAttribute("length", raw.length.toString())
            bodyText.append(raw)
            return
        }
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) reoffsetRawRuns(child as Element, originalText, bodyText)
        }
    }

    private val RUN_TAGS = setOf("content", "space", "field", "tab")

    private fun parseXml(bytes: ByteArray): Document {
        // Bayt düzeyinde DTD reddi (DocxLite ile aynı): parser özelliklerinin
        // bir kısmı Android'in XML sağlayıcısında desteklenmiyor ve
        // secureDocumentBuilderFactory onları sessizce atlıyor. UDF'nin
        // content.xml'i DTD kullanmaz; DOCTYPE/ENTITY görülürse belge reddedilir.
        require(!containsXmlToken(bytes, "<!DOCTYPE") && !containsXmlToken(bytes, "<!ENTITY")) {
            "UDF XML içinde DTD/entity kullanılamaz"
        }
        val dbFactory = secureDocumentBuilderFactory()
        val dBuilder = dbFactory.newDocumentBuilder()
        return dBuilder.parse(ByteArrayInputStream(bytes))
    }

    private fun readBounded(input: ZipInputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= max) { "UDF paket girdisi çok büyük" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    // ASCII ve UTF-16 (iki bayt sırası) içinde büyük/küçük harf duyarsız arama.
    private fun containsXmlToken(bytes: ByteArray, token: String): Boolean {
        val desenler = listOf(Charsets.US_ASCII, Charsets.UTF_16LE, Charsets.UTF_16BE)
            .map { token.uppercase().toByteArray(it) }
        return desenler.any { desen ->
            if (desen.isEmpty() || bytes.size < desen.size) return@any false
            (0..bytes.size - desen.size).any { i ->
                desen.indices.all { j ->
                    val gercek = bytes[i + j].toInt() and 0xff
                    val katlanmis = if (gercek in 'a'.code..'z'.code) gercek - 32 else gercek
                    katlanmis == (desen[j].toInt() and 0xff)
                }
            }
        }
    }

    /** Builds groupName/fieldName -> value (and fieldName -> value fallback) from <data>. */
    private fun Element.buildFieldDataMap(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        fun walk(element: Element, parentTag: String) {
            var hasElementChild = false
            val children = element.childNodes
            for (i in 0 until children.length) {
                val n = children.item(i)
                if (n.nodeType == Node.ELEMENT_NODE) {
                    hasElementChild = true
                    walk(n as Element, element.nodeName)
                }
            }
            if (!hasElementChild) {
                val text = element.textContent ?: ""
                map.putIfAbsent("$parentTag/${element.nodeName}", text)
                map.putIfAbsent(element.nodeName, text)
            }
        }
        walk(this, "data")
        return map
    }

    private fun Element.readBodyElements(
        textContent: String,
        paragraphs: MutableList<UdfParagraph>,
        extraElementXml: MutableList<String>,
        blocks: MutableList<UdfBlock>,
        body: MutableList<UdfBodyNode>,
        fieldData: Map<String, String>
    ) {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            when (element.nodeName) {
                "paragraph" -> {
                    val paragraph = element.toUdfParagraph(textContent, fieldData)
                    // Satır adresi gövdedeki SIRAdır: düzenleme bu adresle yazıyor.
                    val bodyIndex = body.size
                    paragraphs.add(paragraph)
                    // Yazma sırası paragrafın KENDİSİNİ tutar: aşağıdaki gömülü
                    // tablo dönüşümü yalnız gösterim içindir, dosyaya tablo
                    // olarak yazılırsa belge bozulur.
                    body.add(UdfParagraphNode(paragraph))
                    // A field value (e.g. the signature block "imza") may itself be
                    // UYAP table markup: <table><tr><td>..</td>..</tr></table>.
                    // Render it as a real table instead of literal text.
                    val joined = paragraph.elements.joinToString("") { it.textRun }
                    val embedded = parseEmbeddedTableMarkup(joined)
                    // Gösterim bloğu SATIRDIR, paragraf değil: gerekçe
                    // [splitIntoLines]'ta.
                    if (embedded != null) blocks.add(embedded)
                    else blocks.addAll(paragraph.splitIntoLineBlocks(bodyIndex))
                }
                "table" -> {
                    // Preserve raw XML for round-trip saving AND parse into a
                    // render block so tables can be displayed read-only.
                    val xml = elementToString(element)
                    extraElementXml.add(xml)
                    body.add(UdfRawNode(xml))
                    blocks.add(element.toUdfTableBlock(textContent, fieldData))
                }
                else -> {
                    val xml = elementToString(element)
                    extraElementXml.add(xml)
                    body.add(UdfRawNode(xml))
                }
            }
        }
    }

    private fun Element.toUdfTableBlock(textContent: String, fieldData: Map<String, String>): UdfTableBlock {
        val tableSpans = parseSpans(getAttribute("columnSpans"))
        val borderAttr = getAttribute("border")
        val hasBorder = borderAttr.isNotBlank() && !borderAttr.equals("borderNone", ignoreCase = true)

        val rows = childElements("row").map { rowEl ->
            val cells = rowEl.childElements("cell").map { cellEl ->
                UdfCell(cellEl.parseBlocks(textContent, fieldData))
            }
            val rowSpans = parseSpans(rowEl.getAttribute("columnSpans"))
            val weights = when {
                rowSpans.size == cells.size && rowSpans.isNotEmpty() -> rowSpans
                tableSpans.size == cells.size && tableSpans.isNotEmpty() -> tableSpans
                else -> List(cells.size.coerceAtLeast(1)) { 1f }
            }
            UdfTableRow(cells, weights)
        }

        val colWeights = tableSpans.ifEmpty {
            List((getAttribute("columnCount").toIntOrNull() ?: 1).coerceAtLeast(1)) { 1f }
        }
        return UdfTableBlock(colWeights, hasBorder, rows)
    }

    /**
     * Some field values (notably the "imza" signature block) embed a small HTML-like
     * table: <table> <tr><td align="left">..\n..</td><td ..>..</td></tr></table>.
     * Parses that markup into a borderless render table; returns null if the text is
     * not such markup.
     */
    private fun parseEmbeddedTableMarkup(text: String): UdfTableBlock? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("<table", ignoreCase = true) ||
            !trimmed.contains("</table>", ignoreCase = true)
        ) return null

        val rowRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("<td([^>]*)>(.*?)</td>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val alignRegex = Regex("align\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

        val rows = mutableListOf<UdfTableRow>()
        var maxCols = 0
        for (rowMatch in rowRegex.findAll(trimmed)) {
            val cells = mutableListOf<UdfCell>()
            for (cellMatch in cellRegex.findAll(rowMatch.groupValues[1])) {
                val align = when (alignRegex.find(cellMatch.groupValues[1])?.groupValues?.get(1)?.lowercase()) {
                    "center" -> 1
                    "right" -> 2
                    else -> 0
                }
                val cellText = cellMatch.groupValues[2].trim()
                val para = UdfParagraph(
                    alignment = align,
                    elements = if (cellText.isEmpty()) emptyList() else listOf(
                        UdfElement(textRun = cellText, length = cellText.length)
                    )
                )
                cells.add(UdfCell(listOf(UdfParagraphBlock(para))))
            }
            if (cells.isEmpty()) continue
            maxCols = maxOf(maxCols, cells.size)
            rows.add(UdfTableRow(cells, List(cells.size) { 1f }))
        }
        if (rows.isEmpty()) return null
        return UdfTableBlock(List(maxCols.coerceAtLeast(1)) { 1f }, hasBorder = false, rows = rows)
    }

    private fun parseSpans(spec: String): List<Float> =
        spec.split(",").mapNotNull { it.trim().toFloatOrNull() }.filter { it > 0f }

    /** Parses direct child paragraphs and (possibly nested) tables into render blocks. */
    private fun Element.parseBlocks(textContent: String, fieldData: Map<String, String>): List<UdfBlock> {
        val result = mutableListOf<UdfBlock>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            when (el.nodeName) {
                "paragraph" -> result.add(UdfParagraphBlock(el.toUdfParagraph(textContent, fieldData)))
                "table" -> result.add(el.toUdfTableBlock(textContent, fieldData))
            }
        }
        return result
    }

    /** Control field names that never render any visible text. */
    private val CONTROL_FIELDS = setOf("eol", "ceol", "")

    /**
     * Resolves a <field>'s display text from the <data> binding section.
     * The content offset only holds the field's own name as a placeholder,
     * so unfilled fields are blanked.
     */
    private fun resolveFieldText(
        fName: String,
        fGroup: String,
        rawRun: String,
        isLabel: Boolean,
        fieldData: Map<String, String>
    ): String {
        val t = rawRun.trim()
        // 'ceol' is an internal true/false control flag, never displayed.
        if (fName.equals("ceol", ignoreCase = true)) return ""
        // A bound value from <data> always wins (some caption-looking fields such
        // as gidecegiBirimAdi are actually fillable and carry a real value).
        val bound = fieldData["$fGroup/$fName"] ?: fieldData[fName]
        if (bound != null) return bound
        // Static label fields (fieldType="2") are fixed captions like MÜŞTEKİ,
        // MÜDAFİİ, SANIK: their own text IS the content even though it equals the
        // fieldName, so they must never be blanked. (header="true" is NOT a label
        // marker — fillable fields can carry it too.)
        if (isLabel) return rawRun
        return when {
            // Unfilled fillable field (fieldType="1"): content holds the field's
            // own name (case may differ) -> show nothing.
            fName.isNotEmpty() && (t.equals(fName, true) || t.equals(fGroup, true)) -> ""
            else -> rawRun
        }
    }

    /** Intermediate run descriptor used while resolving conditional glue text. */
    private class RawRun(
        val element: Element,
        val node: String,
        val fieldName: String,
        val fieldGroupName: String,
        val resolved: String,
        /** CDATA'daki ham dilim — kaydetme gövdeyi bundan kurar. */
        val raw: String
    )

    private fun Element.toUdfParagraph(textContent: String, fieldData: Map<String, String>): UdfParagraph {
        // Paragraph-level styling is inherited by runs that do not override it.
        // UYAP marks whole label cells bold via <paragraph bold="true">; the
        // individual content runs then omit the bold attribute.
        val paraBold = getAttribute("bold").equals("true", ignoreCase = true)
        val paraItalic = getAttribute("italic").equals("true", ignoreCase = true)
        val paraUnderline = getAttribute("underline").equals("true", ignoreCase = true)

        // Pass 1: collect runs in document order, resolving <field> values.
        val raw = mutableListOf<RawRun>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            val nn = element.nodeName
            // <tab> is a real run too: its CDATA slice is a '\t' character. It was
            // previously skipped, which dropped header indentation AND prevented the
            // tab-stop column alignment from ever triggering on label rows.
            if (nn != "content" && nn != "space" && nn != "field" && nn != "tab") continue

            val startOffset = element.getAttribute("startOffset").toIntOrNull() ?: 0
            val length = element.getAttribute("length").toIntOrNull() ?: 0
            val rawRun = if (startOffset >= 0 && startOffset + length <= textContent.length) {
                textContent.substring(startOffset, startOffset + length)
            } else ""
            val fName = element.getAttribute("fieldName").trim()
            val fGroup = element.getAttribute("fieldGroupName").trim()
            val isLabel = element.getAttribute("fieldType") == "2"
            val resolved = if (nn == "field") resolveFieldText(fName, fGroup, rawRun, isLabel, fieldData) else rawRun
            raw.add(RawRun(element, nn, fName, fGroup, resolved, rawRun))
        }

        // Pass 2: figure out which conditional regions are active.
        // A "glue" run is plain content/space that carries a fieldGroupName: it is
        // template scaffolding (e.g. "vekili ", " esas ve ") shown only when its
        // region is filled. A group is "active" if it has a field with a real value.
        val activeGroups = HashSet<String>()
        val realGroups = HashSet<String>()
        for (r in raw) {
            if (r.node == "field" && r.fieldName.lowercase() !in CONTROL_FIELDS) {
                realGroups.add(r.fieldGroupName)
                if (r.resolved.isNotBlank()) activeGroups.add(r.fieldGroupName)
            }
        }
        fun nextRealGroup(from: Int): String? {
            for (j in from + 1 until raw.size) {
                val r = raw[j]
                if (r.node == "field" && r.fieldName.lowercase() !in CONTROL_FIELDS) return r.fieldGroupName
            }
            return null
        }
        // Pseudo-label groups (only an eol marker, no data field of their own)
        // borrow activity from the following real group, e.g. "vekili"/"vekilleri"
        // before the avukat block. Only the first such label is kept (singular form).
        val emittedLabel = HashSet<String>()

        // Pass 3: build the final elements.
        val runs = mutableListOf<UdfElement>()
        for ((idx, r) in raw.withIndex()) {
            val element = r.element
            val isGlue = r.node != "field" && r.fieldGroupName.isNotEmpty()
            val finalText: String = if (isGlue) {
                when {
                    r.fieldGroupName in activeGroups -> r.resolved
                    r.fieldGroupName !in realGroups -> {
                        val nrg = nextRealGroup(idx)
                        if (nrg != null && nrg in activeGroups && nrg !in emittedLabel) {
                            emittedLabel.add(nrg)
                            r.resolved
                        } else ""
                    }
                    else -> ""
                }
            } else r.resolved

            runs.add(
                UdfElement(
                    textRun = finalText,
                    startOffset = element.getAttribute("startOffset").toIntOrNull() ?: 0,
                    length = element.getAttribute("length").toIntOrNull() ?: 0,
                    bold = if (element.hasAttribute("bold")) element.getAttribute("bold").toBoolean() else paraBold,
                    italic = if (element.hasAttribute("italic")) element.getAttribute("italic").toBoolean() else paraItalic,
                    underline = if (element.hasAttribute("underline")) element.getAttribute("underline").toBoolean() else paraUnderline,
                    strikethrough = element.getAttribute("strikethrough").toBoolean(),
                    subscript = element.getAttribute("subscript").toBoolean(),
                    superscript = element.getAttribute("superscript").toBoolean(),
                    size = element.getAttribute("size").toIntOrNull() ?: 12,
                    family = element.getAttribute("family").ifEmpty { "Times New Roman" },
                    color = element.getAttribute("color").ifEmpty { null },
                    rawText = r.raw,
                    kind = UdfElementKind.fromTag(r.node),
                    extraAttributes = element.extraAttributes(
                        "startOffset",
                        "length",
                        "bold",
                        "italic",
                        "underline",
                        "strikethrough",
                        "subscript",
                        "superscript",
                        "size",
                        "family",
                        "color"
                    )
                )
            )
        }

        return UdfParagraph(
            alignment = getAttribute("Alignment").toIntOrNull() ?: 0,
            resolver = getAttribute("resolver").ifEmpty { "hvl-default" },
            lineSpacing = getAttribute("LineSpacing").toFloatOrNull(),
            leftIndent = getAttribute("LeftIndent").toFloatOrNull(),
            rightIndent = getAttribute("RightIndent").toFloatOrNull(),
            firstLineIndent = getAttribute("FirstLineIndent").toFloatOrNull(),
            extraAttributes = extraAttributes(
                "Alignment",
                "resolver",
                "LineSpacing",
                "LeftIndent",
                "RightIndent",
                "FirstLineIndent"
            ),
            elements = runs
        )
    }

    private fun Float.toUdfNumber(): String =
        if (isFinite() && this % 1f == 0f) toInt().toString() else toString()

    private fun Element.extraAttributes(vararg knownNames: String): Map<String, String> {
        val known = knownNames.toSet()
        val result = linkedMapOf<String, String>()
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i)
            if (attr.nodeName !in known) {
                result[attr.nodeName] = attr.nodeValue
            }
        }
        return result
    }

    private fun Element.applyExtraAttributes(attributes: Map<String, String>) {
        for ((name, value) in attributes) {
            if (name.isNotBlank() && !hasAttribute(name)) {
                setAttribute(name, value)
            }
        }
    }

    private fun Element.childElements(name: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == name) {
                result.add(child as Element)
            }
        }
        return result
    }

    private fun elementToString(element: Element): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        val writer = ByteArrayOutputStream()
        transformer.transform(DOMSource(element), StreamResult(writer))
        return writer.toString("UTF-8")
    }

    private fun parseXmlElement(targetDoc: Document, xml: String): Element? {
        return try {
            val parsed = parseXml(xml.toByteArray(Charsets.UTF_8))
            targetDoc.importNode(parsed.documentElement, true) as Element
        } catch (e: Exception) {
            // Bozuk korunmuş öğe XML'i sessizce atlanır; belgenin kalanı okunabilir.
            null
        }
    }
}
