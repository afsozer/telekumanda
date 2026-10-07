package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Testler GERÇEK bir XLSX kabı kurup okutuyor — parça parça iç fonksiyon çağırmak
 * yerine. Sebebi: bu okuyucudaki hataların çoğu tek bir fonksiyonda değil,
 * parçalar arası bağda çıkıyor (paylaşılan metin havuzuna indeks, biçim indeksine
 * indeks, ilişki kimliğinden sayfa dosyasına).
 */
class XlsxLiteTest {

    private fun xlsx(parts: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val styles = """
        <styleSheet>
          <numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00 &quot;TL&quot;"/></numFmts>
          <cellStyleXfs count="1"><xf numFmtId="0"/></cellStyleXfs>
          <cellXfs count="4">
            <xf numFmtId="0"/>
            <xf numFmtId="14"/>
            <xf numFmtId="164"/>
            <xf numFmtId="10"/>
          </cellXfs>
        </styleSheet>
    """.trimIndent()

    private val sharedStrings = """
        <sst count="2" uniqueCount="2">
          <si><t>Alacak</t></si>
          <si><r><t>Vekâlet</t></r><r><t> ücreti</t></r><rPh sb="0" eb="1"><t>ATLA</t></rPh></si>
        </sst>
    """.trimIndent()

    private fun singleSheetBook(sheet: String): ByteArray = xlsx(
        mapOf(
            "xl/workbook.xml" to
                """<workbook><sheets><sheet name="Hesap" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to
                """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to sharedStrings,
            "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet,
        )
    )

    @Test
    fun hucreTurleriDogruOkunur() {
        val book = XlsxLite.open(
            singleSheetBook(
                """
                <worksheet>
                  <cols><col min="1" max="1" width="24"/></cols>
                  <sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                    <row r="3">
                      <c r="A3" s="1"><v>44927</v></c>
                      <c r="B3" s="2"><v>1234.5</v></c>
                      <c r="C3" s="3"><v>0.075</v></c>
                    </row>
                    <row r="4">
                      <c r="A4"><f>SUM(B3:B3)</f><v>1234.5</v></c>
                      <c r="B4" t="inlineStr"><is><t>satır içi</t></is></c>
                      <c r="C4" t="b"><v>1</v></c>
                    </row>
                  </sheetData>
                </worksheet>
                """.trimIndent()
            )
        )
        val sheet = book.sheets.single()
        assertEquals("Hesap", sheet.name)
        // 2. satır dosyada hiç yazılmamış; satır numaraları kaysın diye doldurulur.
        assertEquals(4, sheet.rows.size)
        assertEquals(emptyList<SheetCell>(), sheet.rows[1])

        assertEquals("Alacak", sheet.rows[0][0].text)
        // Zengin metin parçaları birleşir, <rPh> (Japonca okunuş) DIŞARIDA kalır.
        assertEquals("Vekâlet ücreti", sheet.rows[0][1].text)

        assertEquals("01.01.2023", sheet.rows[2][0].text)
        // Özel biçimdeki para birimi metni korunur; ayıklanınca sütun anlamsızlaşıyordu.
        assertEquals("1.234,50 TL", sheet.rows[2][1].text)
        assertEquals("7,50%", sheet.rows[2][2].text)

        // Formül hücresinde <f> gövdesi değil, önbelleklenmiş <v> değeri okunur.
        assertEquals("1234,5", sheet.rows[3][0].text)
        assertEquals("satır içi", sheet.rows[3][1].text)
        assertEquals("DOĞRU", sheet.rows[3][2].text)

        assertEquals(3, sheet.columnCount)
        assertEquals(24.0, sheet.columnWidths[0], 0.001)
        assertTrue(sheet.rows[2][0].numeric)
        assertTrue(!sheet.rows[0][0].numeric)
    }

    @Test
    fun sayfaSirasiZipSirasiDegilCalismaKitabiSirasidir() {
        val book = XlsxLite.open(
            xlsx(
                mapOf(
                    "xl/workbook.xml" to """
                        <workbook><sheets>
                          <sheet name="İkinci" sheetId="2" r:id="rId2"/>
                          <sheet name="Birinci" sheetId="1" r:id="rId1"/>
                        </sheets></workbook>
                    """.trimIndent(),
                    "xl/_rels/workbook.xml.rels" to """
                        <Relationships>
                          <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
                          <Relationship Id="rId2" Target="/xl/worksheets/sheet2.xml"/>
                        </Relationships>
                    """.trimIndent(),
                    "xl/worksheets/sheet1.xml" to
                        """<worksheet><sheetData><row r="1"><c r="A1" t="str"><v>bir</v></c></row></sheetData></worksheet>""",
                    "xl/worksheets/sheet2.xml" to
                        """<worksheet><sheetData><row r="1"><c r="A1" t="str"><v>iki</v></c></row></sheetData></worksheet>""",
                )
            )
        )
        assertEquals(listOf("İkinci", "Birinci"), book.sheets.map { it.name })
        assertEquals("iki", book.sheets[0].rows[0][0].text)
        assertEquals("bir", book.sheets[1].rows[0][0].text)
    }

    @Test
    fun seyrekHucreAdresiSutunKonumunuKorur() {
        val book = XlsxLite.open(
            singleSheetBook(
                """
                <worksheet><sheetData>
                  <row r="1"><c r="A1" t="str"><v>ilk</v></c><c r="E1" t="str"><v>beşinci</v></c></row>
                </sheetData></worksheet>
                """.trimIndent()
            )
        )
        val row = book.sheets.single().rows[0]
        assertEquals("ilk", row[0].text)
        assertEquals("", row[1].text)
        assertEquals("beşinci", row[4].text)
        assertEquals(5, book.sheets.single().columnCount)
    }

    // 1900 sistemindeki sahte artık gün, bu okuyucunun en kolay sessizce yanlış
    // yapabileceği yer: bir gün kayma kimsenin gözüne çarpmaz.
    @Test
    fun tarihSerileriBilinenDegerlereCevrilir() {
        val gun = "dd.mm.yyyy"
        assertEquals("01.01.1900", XlsxLite.formatDate(1.0, gun, date1904 = false))
        assertEquals("28.02.1900", XlsxLite.formatDate(59.0, gun, date1904 = false))
        assertEquals("01.03.1900", XlsxLite.formatDate(61.0, gun, date1904 = false))
        assertEquals("01.01.1970", XlsxLite.formatDate(25569.0, gun, date1904 = false))
        assertEquals("01.01.2023", XlsxLite.formatDate(44927.0, gun, date1904 = false))
        // Mac Excel mirası 1904 sistemi: aynı seri 4 yıl 1 gün ileri.
        assertEquals("02.01.1904", XlsxLite.formatDate(1.0, gun, date1904 = true))
        // Saat kısmı yalnız biçim isterse görünür.
        assertEquals("01.01.2023 06:00", XlsxLite.formatDate(44927.25, "dd.mm.yyyy h:mm", date1904 = false))
        assertEquals("06:00:00", XlsxLite.formatDate(44927.25, "h:mm:ss", date1904 = false))
    }

    @Test
    fun tirnakIcindekiHarfTarihSanilmaz() {
        // "0" gün"" biçimindeki d harfi ayıklanmazsa hücre tarihe dönüşüyordu.
        assertTrue(!XlsxLite.isDateFormat("""0" gün""""))
        assertTrue(!XlsxLite.isDateFormat("General"))
        assertTrue(!XlsxLite.isDateFormat("#,##0.00"))
        assertTrue(!XlsxLite.isDateFormat("0.00E+00"))
        assertTrue(XlsxLite.isDateFormat("dd.mm.yyyy"))
        assertTrue(XlsxLite.isDateFormat("[\$-41F]d mmmm yyyy"))
        assertEquals("0 gün", XlsxLite.formatNumber(0.0, """0" gün""""))
    }

    @Test
    fun bicimsizSayiBinlikAyiraciKoymaz() {
        // Yıl gibi tam sayılar "2.023" diye görünmemeli.
        assertEquals("2023", XlsxLite.formatNumber(2023.0, ""))
        assertEquals("1234,5", XlsxLite.formatNumber(1234.5, "General"))
        assertEquals("-7", XlsxLite.formatNumber(-7.0, ""))
        assertEquals("0", XlsxLite.formatNumber(0.0, ""))
        // Biçim isterse ayırıcı ve ondalık gelir.
        assertEquals("1.234,50", XlsxLite.formatNumber(1234.5, "#,##0.00"))
        assertEquals("1235", XlsxLite.formatNumber(1234.5, "#"))
    }

    @Test
    fun sutunAdresleriCevrilir() {
        assertEquals(0, XlsxLite.columnIndexOf("A1"))
        assertEquals(25, XlsxLite.columnIndexOf("Z9"))
        assertEquals(26, XlsxLite.columnIndexOf("AA1"))
        assertEquals(54, XlsxLite.columnIndexOf("BC12"))
        assertEquals("A", XlsxLite.columnLabel(0))
        assertEquals("Z", XlsxLite.columnLabel(25))
        assertEquals("AA", XlsxLite.columnLabel(26))
        assertEquals("BC", XlsxLite.columnLabel(54))
    }

    @Test
    fun satirTavaniAsilincaKullaniciyaSoylenir() {
        val rows = (1..XlsxLite.MAX_ROWS + 50).joinToString("") {
            """<row r="$it"><c r="A$it" t="str"><v>x</v></c></row>"""
        }
        val sheet = XlsxLite.open(
            singleSheetBook("<worksheet><sheetData>$rows</sheetData></worksheet>")
        ).sheets.single()
        assertEquals(XlsxLite.MAX_ROWS, sheet.rows.size)
        assertTrue(sheet.notice.contains("${XlsxLite.MAX_ROWS} satır"))
    }

    @Test
    fun xlsxOlmayanDosyaAnlasilirHataVerir() {
        val hata = runCatching { XlsxLite.open(xlsx(mapOf("hello.txt" to "merhaba"))) }.exceptionOrNull()
        assertTrue(hata is IllegalArgumentException)
        assertTrue(hata!!.message!!.contains("workbook.xml"))
    }
}
