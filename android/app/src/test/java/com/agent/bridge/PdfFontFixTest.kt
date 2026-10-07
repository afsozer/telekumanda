package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF cerrahisinin testleri. Gerçek font gerekmiyor: gömülen bayt dizisinin
 * dosyada doğru yere, doğru uzunlukla yazıldığını ve xref'in tuttuğunu ölçüyoruz.
 * Türkçe harflerin gerçekten çizildiği ayrı ölçüldü (cihazda, PdfGlyphProbe).
 */
class PdfFontFixTest {

    private val fakeFont = ByteArray(600) { (it % 251).toByte() }

    /** Gömülü fontu olmayan, iText'in ürettiğine benzeyen küçük bir PDF. */
    private fun samplePdf(
        descriptorExtra: String = "",
        fontName: String = "ArialMT",
        flags: Int = 32,
        twinDescriptor: Boolean = false,
    ): ByteArray {
        val content = "BT /F1 12 Tf 60 700 Td (Sehir) Tj ET\n"
        val objs = linkedMapOf(
            1 to "<</Type/Catalog/Pages 2 0 R>>",
            2 to "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            3 to "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]" +
                "/Resources<</Font<</F1 5 0 R>>>>/Contents 4 0 R>>",
            4 to "<</Length ${content.length}>>stream\n$content\nendstream",
            5 to "<</Type/Font/Subtype/TrueType/BaseFont/$fontName/FontDescriptor 6 0 R" +
                "/FirstChar 32/LastChar 254/Widths[" + List(223) { "556" }.joinToString(" ") + "]>>",
            6 to "<</Type/FontDescriptor/FontName/$fontName/Flags $flags" +
                "/FontBBox[-664 -324 2000 1039]/ItalicAngle 0/Ascent 728/Descent -210" +
                "/CapHeight 716/StemV 80$descriptorExtra>>",
        )
        if (twinDescriptor) {
            // Aynı yüze düşen ikinci bir tanım (gerçek evrakta her boy için ayrı olur).
            objs[7] = "<</Type/FontDescriptor/FontName/$fontName/Flags $flags" +
                "/FontBBox[-664 -324 2000 1039]/ItalicAngle 0/Ascent 728/Descent -210" +
                "/CapHeight 716/StemV 80>>"
        }
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = LinkedHashMap<Int, Int>()
        for ((n, body) in objs) {
            offsets[n] = sb.length
            sb.append("$n 0 obj\n$body\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (n in objs.keys) sb.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offsets[n]))
        sb.append("trailer\n<</Size ${objs.size + 1}/Root 1 0 R>>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun `gomulusuz fontu gomer ve orijinali onune dokunmaz`() {
        val src = samplePdf()
        val out = PdfFontFix.repair(src) { fakeFont }
        assertNotNull(out)
        out!!
        // Artımlı güncelleme: dosyanın başı bit bit aynı kalmalı, yoksa eski xref
        // ofsetleri kayar ve dosya okunamaz hale gelir.
        assertTrue(out.size > src.size)
        assertTrue(src.contentEquals(out.copyOfRange(0, src.size)))
        val tail = text(out).substring(src.size)
        assertTrue("yeni tanım yazılmalı", tail.contains("6 0 obj"))
        assertTrue("bağ kurulmalı", tail.contains("/FontFile2 7 0 R"))
        assertTrue("akış Length1 taşımalı", tail.contains("/Length1 ${fakeFont.size}"))
        assertTrue("önceki bölüme bağlanmalı", tail.contains("/Prev "))
        assertTrue("Size büyümeli", tail.contains("/Size 8"))
    }

    @Test
    fun `yeni xref girdileri gercek nesne ofsetlerini gosterir`() {
        val src = samplePdf()
        val out = PdfFontFix.repair(src) { fakeFont }!!
        val body = text(out)
        // "startxref" de "xref" içeriyor; tablonun kendisini satır başından arıyoruz.
        val xrefAt = body.lastIndexOf("\nxref\n")
        val entries = Regex("(\\d{10}) 00000 n").findAll(body.substring(xrefAt))
            .map { it.groupValues[1].toInt() }.toList()
        assertEquals(2, entries.size)
        // Her girdi gerçekten bir "N 0 obj" başlangıcına düşmeli.
        for (offset in entries) {
            assertTrue("ofset $offset nesne başlangıcı değil", Regex("^\\d+ 0 obj").containsMatchIn(body.substring(offset)))
        }
    }

    @Test
    fun `zaten gomulu fontta dokunmaz`() {
        val src = samplePdf(descriptorExtra = "/FontFile2 9 0 R")
        assertNull(PdfFontFix.repair(src) { fakeFont })
    }

    @Test
    fun `font yuzu saglanamazsa dokunmaz`() {
        assertNull(PdfFontFix.repair(samplePdf()) { null })
    }

    @Test
    fun `sifreli dosyaya dokunmaz`() {
        val src = text(samplePdf()).replace("<</Size 7/Root 1 0 R>>", "<</Size 7/Root 1 0 R/Encrypt 8 0 R>>")
        assertNull(PdfFontFix.repair(src.toByteArray(Charsets.ISO_8859_1)) { fakeFont })
    }

    @Test
    fun `klasik xref tablosu yoksa dokunmaz`() {
        // Xref akışlı dosyalarda tablo anahtar kelimesi hiç geçmez.
        val src = text(samplePdf()).replace("\nxref\n", "\nXREF\n")
        assertNull(PdfFontFix.repair(src.toByteArray(Charsets.ISO_8859_1)) { fakeFont })
    }

    @Test
    fun `startxref yanlis yeri gosterse de tablo bulunur`() {
        // Ölçülen gerçek dosya: imza sarmalayıcısı işaretçiyi 64 bayt kaydırmış.
        val src = text(samplePdf()).replace(Regex("startxref\\n\\d+"), "startxref\n9")
        assertNotNull(PdfFontFix.repair(src.toByteArray(Charsets.ISO_8859_1)) { fakeFont })
    }

    @Test
    fun `bozuk ve bos girdide cokmez`() {
        assertNull(PdfFontFix.repair(ByteArray(0)) { fakeFont })
        assertNull(PdfFontFix.repair("%PDF-1.4\nnope".toByteArray()) { fakeFont })
        assertNull(PdfFontFix.repair(ByteArray(4096) { 0x25 }) { fakeFont })
    }

    @Test
    fun `ayni yuzu paylasan tanimlar tek akis gomer`() {
        val src = samplePdf(twinDescriptor = true)
        val out = PdfFontFix.repair(src) { fakeFont }!!
        val tail = text(out).substring(src.size)
        assertEquals("font akışı bir kez gömülmeli", 1, Regex("/Length1 ").findAll(tail).count())
        assertTrue(tail.contains("6 0 obj"))
        assertTrue(tail.contains("7 0 obj"))
    }

    /**
     * Temel-14 fontlu belge: font nesnesi var, tanım nesnesi YOK. UYAP'ın
     * JasperReports'la ürettiği ihbarnameler böyle.
     */
    private fun base14Pdf(
        subtype: String = "Type1",
        extras: String = "/Encoding<</Type/Encoding/Differences[32/space 208/Gbreve 222/Scedilla]>>" +
            "/FirstChar 32/LastChar 254/Widths[" + List(223) { "556" }.joinToString(" ") + "]",
        trailingGarbage: ByteArray = ByteArray(0),
    ): ByteArray {
        val content = "BT /F1 12 Tf 60 700 Td (Sehir) Tj ET\n"
        val objs = linkedMapOf(
            1 to "<</Type/Catalog/Pages 2 0 R>>",
            2 to "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            3 to "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]" +
                "/Resources<</Font<</F1 5 0 R>>>>/Contents 4 0 R>>",
            4 to "<</Length ${content.length}>>stream\n$content\nendstream",
            5 to "<</Type/Font/Subtype/$subtype/BaseFont/Helvetica-Bold$extras>>",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = LinkedHashMap<Int, Int>()
        for ((n, body) in objs) {
            offsets[n] = sb.length
            sb.append("$n 0 obj\n$body\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (n in objs.keys) sb.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offsets[n]))
        sb.append("trailer\n<</Size ${objs.size + 1}/Root 1 0 R>>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1) + trailingGarbage
    }

    @Test
    fun `temel-14 fonta tanim ve gomulu yuz uretir`() {
        val src = base14Pdf()
        val out = PdfFontFix.repair(src) { fakeFont }
        assertNotNull(out)
        val tail = text(out!!).substring(src.size)
        // /FontFile2 yalnız TrueType'ta geçerli; font nesnesi de dönüştürülmeli.
        assertTrue("subtype TrueType olmalı", tail.contains("/Subtype/TrueType"))
        assertTrue("Type1 kalmamalı", !tail.contains("/Subtype/Type1"))
        assertTrue("font tanıma bağlanmalı", Regex("/FontDescriptor \\d+ 0 R").containsMatchIn(tail))
        assertTrue("tanım üretilmeli", tail.contains("/Type/FontDescriptor"))
        assertTrue("yüz gömülmeli", tail.contains("/FontFile2"))
        // Widths dizisi olduğu gibi kalmalı, yoksa satır düzeni kayar.
        assertTrue("genişlikler korunmalı", tail.contains("/Widths[556"))
        assertTrue("Helvetica-Bold kalın sans yüzüne düşmeli", tail.contains("/StemV 140"))
    }

    @Test
    fun `duz WinAnsi temel-14 fonta dokunmaz`() {
        // Differences yok: WinAnsi zaten Türkçe harf taşımıyor, dönüştürmek kazanç değil.
        assertNull(PdfFontFix.repair(base14Pdf(extras = "/Encoding/WinAnsiEncoding")) { fakeFont })
    }

    @Test
    fun `genislik dizisi olmayan temel-14 fonta dokunmaz`() {
        val noWidths = "/Encoding<</Type/Encoding/Differences[32/space 208/Gbreve]>>"
        assertNull(PdfFontFix.repair(base14Pdf(extras = noWidths)) { fakeFont })
    }

    @Test
    fun `imza kuyrugu kesilir ve dosya duzeltilir`() {
        // E-imzalı kapsayıcı: %%EOF'ten sonra kilobaytlarca CMS verisi geliyor.
        // Kuyruk kalırsa pdfium eklediğimiz nesneleri görmüyor (cihazda ölçüldü).
        val garbage = ByteArray(11_000) { ((it * 37) % 251).toByte() }
        val clean = base14Pdf()
        val src = base14Pdf(trailingGarbage = garbage)
        val out = PdfFontFix.repair(src) { fakeFont }
        assertNotNull("kuyruk düzeltmeyi engellememeli", out)
        out!!
        // PDF'in kendisi bit bit korunur, kuyruk atılır.
        assertTrue(clean.contentEquals(out.copyOfRange(0, clean.size)))
        assertTrue("kuyruk kalmamalı", out.size < src.size + 40_000)
        assertTrue(text(out).endsWith("%%EOF\n"))
        assertTrue(text(out).contains("/FontFile2"))
    }

    @Test
    fun `yuz secimi ada ve bayraklara bakar`() {
        fun face(dict: String) = PdfFontFix.faceOf(dict)
        assertEquals(
            PdfFontFix.Face(PdfFontFix.Family.SANS, bold = false, italic = false),
            face("<</FontName/ArialMT/Flags 32/ItalicAngle 0/StemV 80>>"),
        )
        assertEquals(
            PdfFontFix.Face(PdfFontFix.Family.SANS, bold = true, italic = false),
            face("<</FontName/Arial-BoldMT/Flags 262176/ItalicAngle 0/StemV 80>>"),
        )
        assertEquals(
            PdfFontFix.Face(PdfFontFix.Family.SANS, bold = false, italic = true),
            face("<</FontName/Arial-ItalicMT/Flags 96/ItalicAngle -12/StemV 80>>"),
        )
        assertEquals(
            PdfFontFix.Face(PdfFontFix.Family.SERIF, bold = false, italic = false),
            face("<</FontName/TimesNewRomanPSMT/Flags 32/ItalicAngle 0/StemV 80>>"),
        )
        assertEquals(
            PdfFontFix.Face(PdfFontFix.Family.MONO, bold = false, italic = false),
            face("<</FontName/Consolas/Flags 33/ItalicAngle 0/StemV 80>>"),
        )
        // Alt küme öneki atılmalı, yoksa "BCDEEE+Arial-BoldMT" düz sans sanılır.
        assertEquals(
            PdfFontFix.Face(PdfFontFix.Family.SERIF, bold = true, italic = true),
            face("<</FontName/BCDEEE+TimesNewRomanPS-BoldItalicMT/Flags 32/ItalicAngle -15/StemV 80>>"),
        )
    }

    @Test
    fun `dosya adlari assets ile ayni`() {
        assertEquals("sans-regular.ttf", PdfFontFix.Face(PdfFontFix.Family.SANS, false, false).assetName)
        assertEquals("serif-bolditalic.ttf", PdfFontFix.Face(PdfFontFix.Family.SERIF, true, true).assetName)
        assertEquals("mono-bold.ttf", PdfFontFix.Face(PdfFontFix.Family.MONO, true, false).assetName)
    }
}
