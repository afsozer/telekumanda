package com.agent.bridge.udf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class UdfParserTest {

    @Test
    fun testDefaultPageFormatMatchesOfficialTemplate() {
        val pageFormat = UdfPageFormat()

        assertEquals("1", pageFormat.mediaSizeName)
        assertEquals(UdfUnits.cmToPoints(2.0f), pageFormat.leftMargin)
        assertEquals(UdfUnits.cmToPoints(2.0f), pageFormat.rightMargin)
        assertEquals(UdfUnits.cmToPoints(2.0f), pageFormat.topMargin)
        assertEquals(UdfUnits.cmToPoints(2.0f), pageFormat.bottomMargin)
        assertEquals("1", pageFormat.paperOrientation)
        assertEquals(72.0f, UdfUnits.DEFAULT_TAB_INTERVAL_PT)

        val zipBytes = ByteArrayOutputStream().also {
            UdfParser.writeUdf(it, UdfDocument(pageFormat = pageFormat))
        }.toByteArray()
        var contentXml = ""
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "content.xml") contentXml = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        assertTrue(contentXml.contains("mediaSizeName=\"1\""))
        assertTrue(contentXml.contains("leftMargin=\"56.7\""))
        assertTrue(contentXml.contains("rightMargin=\"56.7\""))
        assertTrue(contentXml.contains("topMargin=\"56.7\""))
        assertTrue(contentXml.contains("bottomMargin=\"56.7\""))
        assertTrue(contentXml.contains("paperOrientation=\"1\""))
    }

    @Test
    fun testUdfCreateWriteAndRead() {
        val originalText = "T.C. ADALET BAKANLIGI\nBu bir Android UYAP Editor Pro test belgesidir."

        // Setup mock paragraphs and styled elements.
        // Paragrafı bitiren satır sonu, o paragrafın SON çalıştırmasına aittir —
        // gerçek UDF'lerde de böyledir. Yazıcı gövdeyi artık çalıştırmalardan
        // kurduğu için (16.08.2026, kayıpsız kaydetme) hiçbir çalıştırmanın
        // kapsamadığı karakter dosyaya yazılmaz.
        val element1 = UdfElement(
            textRun = "T.C. ADALET BAKANLIGI\n",
            startOffset = 0,
            length = 22,
            bold = true,
            size = 14,
            family = "Times New Roman"
        )
        val element2 = UdfElement(
            textRun = "Bu bir Android UYAP Editor Pro test belgesidir.",
            startOffset = 22,
            length = 47,
            italic = true,
            size = 12,
            family = "Arial"
        )

        val paragraphs = listOf(
            UdfParagraph(alignment = 1, resolver = "hvl-default", elements = listOf(element1)),
            UdfParagraph(alignment = 3, resolver = "hvl-default", elements = listOf(element2))
        )

        val doc = UdfDocument(
            text = originalText,
            paragraphs = paragraphs,
            styles = listOf(UdfStyle(name = "hvl-default", size = 12, family = "Times New Roman")),
            pageFormat = UdfPageFormat(leftMargin = 40.0f, rightMargin = 40.0f)
        )

        // 1. Serialize document
        val baos = ByteArrayOutputStream()
        UdfParser.writeUdf(baos, doc)
        val zipBytes = baos.toByteArray()
        
        assertTrue("Serialized UDF ZIP bytes should not be empty", zipBytes.isNotEmpty())

        // 2. Deserialize document
        val bais = ByteArrayInputStream(zipBytes)
        val parsedDoc = UdfParser.readUdf(bais)

        // 3. Asserts
        assertNotNull("Parsed document should not be null", parsedDoc)
        assertEquals("Text content must match", originalText, parsedDoc.text)
        assertEquals("Paragraph count must match", 2, parsedDoc.paragraphs.size)
        
        // Assert Element 1 (Bold)
        val parsedP1 = parsedDoc.paragraphs[0]
        assertEquals("Alignment of paragraph 1 must match", 1, parsedP1.alignment)
        assertEquals("Element count in paragraph 1 must match", 1, parsedP1.elements.size)
        val parsedE1 = parsedP1.elements[0]
        assertEquals("Start offset of element 1 must match", 0, parsedE1.startOffset)
        assertEquals("Length of element 1 must match", 22, parsedE1.length)
        assertTrue("Element 1 must be Bold", parsedE1.bold)
        assertEquals("Element 1 size must match", 14, parsedE1.size)

        // Assert Element 2 (Italic)
        val parsedP2 = parsedDoc.paragraphs[1]
        assertEquals("Alignment of paragraph 2 must match", 3, parsedP2.alignment)
        assertEquals("Element count in paragraph 2 must match", 1, parsedP2.elements.size)
        val parsedE2 = parsedP2.elements[0]
        assertEquals("Start offset of element 2 must match", 22, parsedE2.startOffset)
        assertEquals("Length of element 2 must match", 47, parsedE2.length)
        assertTrue("Element 2 must be Italic", parsedE2.italic)
        assertEquals("Element 2 size must match", 12, parsedE2.size)
        assertEquals("Element 2 font family must match", "Arial", parsedE2.family)
        
        System.out.println("UDF Document creation, serialization, and parsing test completed successfully!")
    }

    @Test(expected = org.xml.sax.SAXParseException::class)
    fun testXxeVulnerabilityPrevention() {
        val xxeXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE test [
                <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <template format_id="1.7">
                <content><![CDATA[test &xxe;]]></content>
            </template>
        """.trimIndent()
        
        val dbFactory = secureDocumentBuilderFactory()
        val dBuilder = dbFactory.newDocumentBuilder()
        dBuilder.parse(ByteArrayInputStream(xxeXml.toByteArray(Charsets.UTF_8)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testReadUdfFailsWhenContentXmlMissing() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("documentproperties.xml"))
            zip.write("<properties />".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        UdfParser.readUdf(ByteArrayInputStream(baos.toByteArray()))
    }

    @Test
    fun testUnknownOfficialPartsArePreserved() {
        val doc = UdfDocument(
            text = "Test",
            paragraphs = listOf(
                UdfParagraph(
                    elements = listOf(UdfElement(textRun = "Test", startOffset = 0, length = 4))
                )
            ),
            extraParts = listOf(UdfPart("annotation.xml", "<annotations />".toByteArray(Charsets.UTF_8)))
        )

        val firstBytes = ByteArrayOutputStream().also { UdfParser.writeUdf(it, doc) }.toByteArray()
        val parsedDoc = UdfParser.readUdf(ByteArrayInputStream(firstBytes))
        assertEquals(1, parsedDoc.extraParts.size)
        assertEquals("annotation.xml", parsedDoc.extraParts[0].name)
        assertEquals("<annotations />", parsedDoc.extraParts[0].bytes.toString(Charsets.UTF_8))

        val secondBytes = ByteArrayOutputStream().also { UdfParser.writeUdf(it, parsedDoc) }.toByteArray()
        val zipEntries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(secondBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                zipEntries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        assertEquals("<annotations />", zipEntries["annotation.xml"])
    }

    @Test
    fun testGeneratedZipEntriesAndDefaultStyles() {
        val doc = UdfDocument(
            text = "Signed",
            paragraphs = listOf(
                UdfParagraph(
                    elements = listOf(UdfElement(textRun = "Signed", startOffset = 0, length = 6))
                )
            ),
            isSigned = true,
            signatureBytes = "signature".toByteArray(Charsets.UTF_8)
        )

        val bytes = ByteArrayOutputStream().also { UdfParser.writeUdf(it, doc) }.toByteArray()
        val zipEntries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                zipEntries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }

        assertTrue(zipEntries.containsKey("content.xml"))
        assertTrue(zipEntries.containsKey("documentproperties.xml"))
        assertEquals("signature", zipEntries["sign.sgn"])
        assertTrue(zipEntries["content.xml"]?.contains("name=\"default\"") == true)
        assertTrue(zipEntries["content.xml"]?.contains("name=\"hvl-default\"") == true)
    }

    @Test
    fun testFooterElementIsPreservedOutsideBodyParagraphs() {
        val contentXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <template format_id="1.8">
              <content><![CDATA[Body
              Ftr]]></content>
              <properties><pageFormat mediaSizeName="1" leftMargin="45.72" rightMargin="45.72" topMargin="45.72" bottomMargin="45.72" paperOrientation="1" headerFOffset="20.0" footerFOffset="20.0" customPageAttr="kept" /></properties>
              <elements resolver="hvl-default">
                <paragraph Alignment="0" LineSpacing="0.14999998" LeftIndent="24" RightIndent="18" FirstLineIndent="12"><content startOffset="0" length="4" strikethrough="true" subscript="true" superscript="true" customRunAttr="kept" /><space startOffset="4" length="1" /></paragraph>
                <footer pageNumber-spec="BSP32_2120"><paragraph name="hvl-default"><content startOffset="7" length="3" /></paragraph></footer>
              </elements>
              <styles><style name="default" size="12" family="Dialog" description="Default" /><style name="hvl-default" size="12" family="Times New Roman" description="Gövde" /></styles>
            </template>
        """.trimIndent()
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("content.xml"))
            zip.write(contentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val parsedDoc = UdfParser.readUdf(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(1, parsedDoc.paragraphs.size)
        assertEquals(20.0f, parsedDoc.pageFormat.headerFOffset)
        assertEquals(20.0f, parsedDoc.pageFormat.footerFOffset)
        assertEquals("kept", parsedDoc.pageFormat.extraAttributes["customPageAttr"])
        assertEquals(0.14999998f, parsedDoc.paragraphs[0].lineSpacing)
        assertEquals(24f, parsedDoc.paragraphs[0].leftIndent)
        assertEquals(18f, parsedDoc.paragraphs[0].rightIndent)
        assertEquals(12f, parsedDoc.paragraphs[0].firstLineIndent)
        assertEquals(2, parsedDoc.paragraphs[0].elements.size)
        assertTrue(parsedDoc.paragraphs[0].elements[0].strikethrough)
        assertTrue(parsedDoc.paragraphs[0].elements[0].subscript)
        assertTrue(parsedDoc.paragraphs[0].elements[0].superscript)
        assertEquals("kept", parsedDoc.paragraphs[0].elements[0].extraAttributes["customRunAttr"])
        assertEquals(UdfElementKind.SPACE, parsedDoc.paragraphs[0].elements[1].kind)
        assertEquals(1, parsedDoc.extraElementXml.size)
        assertTrue(parsedDoc.extraElementXml[0].contains("<footer"))

        val roundTripBytes = ByteArrayOutputStream().also { UdfParser.writeUdf(it, parsedDoc) }.toByteArray()
        val zipEntries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(roundTripBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                zipEntries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        assertTrue(zipEntries["content.xml"]?.contains("<footer") == true)
        assertTrue(zipEntries["content.xml"]?.contains("<space") == true)
        assertTrue(zipEntries["content.xml"]?.contains("headerFOffset=\"20.0\"") == true)
        assertTrue(zipEntries["content.xml"]?.contains("LineSpacing=\"0.14999998\"") == true)
        assertTrue(zipEntries["content.xml"]?.contains("customPageAttr=\"kept\"") == true)
        assertTrue(zipEntries["content.xml"]?.contains("LeftIndent=\"24\"") == true)
        assertTrue(zipEntries["content.xml"]?.contains("RightIndent=\"18\"") == true)
        assertTrue(zipEntries["content.xml"]?.contains("FirstLineIndent=\"12\"") == true)
        assertTrue(zipEntries["content.xml"]?.contains("strikethrough=\"true\"") == true)
        assertTrue(zipEntries["content.xml"]?.contains("subscript=\"true\"") == true)
        assertTrue(zipEntries["content.xml"]?.contains("superscript=\"true\"") == true)
        assertTrue(zipEntries["content.xml"]?.contains("customRunAttr=\"kept\"") == true)
    }
}
