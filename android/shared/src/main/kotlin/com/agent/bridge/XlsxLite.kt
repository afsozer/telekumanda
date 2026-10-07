package com.agent.bridge

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * SALT OKUNUR XLSX okuyucu.
 *
 * Neden DOM değil SAX: DOCX/UDF'te belgenin tamamı bellekte tutuluyor çünkü geri
 * yazılıyor. Burada yazma yok, buna karşılık 25 MB'lık bir çalışma kitabı açılınca
 * 200 MB'ı aşan XML üretebiliyor — DOM ağacı telefonda çökme demek. Bu yüzden
 * büyük parçalar (sayfalar, paylaşılan metin havuzu) akış hâlinde okunur.
 *
 * Bilinçli kapsam dışı:
 * - **Formül hesaplama.** Hücrenin dosyada saklı SON HESAPLANMIŞ değeri okunur.
 *   Excel formülü hem `<f>` hem `<v>` olarak yazdığı için görünen değer doğrudur;
 *   yalnızca dosya en son kaydedildiği andaki hâlidir.
 * - **Biçim/renk/kenarlık.** Sayı biçimi okunur (tarih ve ondalık doğru çıksın
 *   diye), görsel biçim okunmaz.
 * - **Birleşik hücreler.** Değer sol üst hücrede görünür, diğerleri boş kalır —
 *   dosyanın kendi yapısı da böyle; yalnız görsel birleştirme yapılmaz.
 * - **`.xls`.** O bambaşka bir ikili biçim (BIFF), bu okuyucu yalnız OOXML bilir.
 */
object XlsxLite {
    /**
     * Görüntüleyicinin taşıyabileceği tavanlar; aşılırsa kullanıcıya söylenir.
     *
     * Sütun tavanı önce 64'tü; kullanıcının kendi puantaj dosyası (günler sütun
     * oluyor) bu sınıra takıldı. Izgara artık yatayda da pencereleniyor, yani
     * ekranda kaç sütun olduğu değil kaçının GÖRÜNDÜĞÜ maliyeti belirliyor.
     */
    const val MAX_ROWS = 5_000
    const val MAX_COLUMNS = 256

    private const val MAX_ENTRIES = 4_096
    private const val MAX_PART_BYTES = 48 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    private const val WORKBOOK = "xl/workbook.xml"
    private const val WORKBOOK_RELS = "xl/_rels/workbook.xml.rels"
    private const val SHARED_STRINGS = "xl/sharedStrings.xml"
    private const val STYLES = "xl/styles.xml"

    fun open(bytes: ByteArray): XlsxWorkbook {
        require(bytes.isNotEmpty()) { "Tablo dosyası boş" }
        // Birinci geçiş yalnız KÜÇÜK parçaları toplar. Sayfalar bilerek dışarıda:
        // onlar ikinci geçişte akışla okunur, belleğe alınmaz.
        val small = readParts(bytes) { name ->
            name.equals(WORKBOOK, true) || name.equals(WORKBOOK_RELS, true) ||
                name.equals(SHARED_STRINGS, true) || name.equals(STYLES, true)
        }
        val workbookXml = small.entries.firstOrNull { it.key.equals(WORKBOOK, true) }?.value
            ?: throw IllegalArgumentException("Geçerli bir XLSX değil: xl/workbook.xml yok")

        val workbook = WorkbookHandler().also { parse(workbookXml, it) }
        val rels = small.entries.firstOrNull { it.key.equals(WORKBOOK_RELS, true) }
            ?.let { RelsHandler().also { h -> parse(it.value, h) }.targets } ?: emptyMap()
        val strings = small.entries.firstOrNull { it.key.equals(SHARED_STRINGS, true) }
            ?.let { SharedStringsHandler().also { h -> parse(it.value, h) }.strings } ?: emptyList()
        val formats = small.entries.firstOrNull { it.key.equals(STYLES, true) }
            ?.let { StylesHandler().also { h -> parse(it.value, h) }.cellFormats } ?: emptyList()

        // Sayfa sırası workbook.xml'deki sıradır, ZIP içindeki sıra DEĞİL — dosya
        // adı sheet1.xml olan parça ilk sekme olmak zorunda değil.
        val wanted = LinkedHashMap<String, String>() // parça yolu -> sayfa adı
        workbook.sheets.forEachIndexed { index, sheet ->
            val target = rels[sheet.relationId]?.let(::resolveTarget)
                ?: "xl/worksheets/sheet${index + 1}.xml"
            wanted[target.lowercase(Locale.ROOT)] = sheet.name
        }
        if (wanted.isEmpty()) throw IllegalArgumentException("Çalışma kitabında sayfa yok")

        val grids = LinkedHashMap<String, SheetGrid>()
        streamParts(bytes, { it.lowercase(Locale.ROOT) in wanted }) { name, stream ->
            val key = name.lowercase(Locale.ROOT)
            val handler = SheetHandler(strings, formats, workbook.date1904)
            try {
                newParser().parse(stream, handler)
            } catch (_: StopParsing) {
                // Tavana ulaşıldı; okunan kadarı gösterilir.
            }
            grids[key] = handler.toGrid(wanted.getValue(key))
        }

        val sheets = wanted.entries.mapNotNull { (key, sheetName) ->
            grids[key] ?: SheetGrid(name = sheetName)
        }
        return XlsxWorkbook(sheets)
    }

    // ── ZIP okuma ───────────────────────────────────────────────────────────

    private fun readParts(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        streamParts(bytes, wanted) { name, stream ->
            out[name] = readBounded(stream)
        }
        return out
    }

    /**
     * ZIP'i baştan tarayıp istenen parçaları AKIŞ olarak verir. Aynı bayt dizisi
     * üzerinde iki kez dolaşmak ucuz; alternatif (her şeyi belleğe alıp sonra
     * ayıklamak) 25 MB'lık bir dosyada yüzlerce MB tutuyordu.
     */
    private fun streamParts(
        bytes: ByteArray,
        wanted: (String) -> Boolean,
        consume: (String, ByteArrayInputStream) -> Unit,
    ) {
        var count = 0
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_ENTRIES) { "XLSX çok fazla paket girdisi içeriyor" }
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory && wanted(name)) {
                    val data = readBounded(zip)
                    total += data.size
                    require(total <= MAX_TOTAL_BYTES) { "XLSX açılmış boyutu çok büyük" }
                    consume(name, ByteArrayInputStream(data))
                }
                zip.closeEntry()
            }
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= MAX_PART_BYTES) { "XLSX paket girdisi çok büyük" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** İlişki hedefini paket kökündeki yola çevirir ("../media/x" gibi yollar dâhil). */
    internal fun resolveTarget(target: String): String {
        val clean = target.replace('\\', '/')
        if (clean.startsWith("/")) return clean.trimStart('/')
        val parts = ArrayDeque<String>()
        parts.addLast("xl")
        for (segment in clean.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    // ── XML ─────────────────────────────────────────────────────────────────

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        newParser().parse(ByteArrayInputStream(bytes), handler)
    }

    /**
     * XXE savunması DocxLite/UdfLite ile aynı politika: her özellik her platformda
     * yok, tek tek denenir, desteklenmeyen sessizce geçilir.
     *
     * `isNamespaceAware` bilerek KAPALI: OOXML'de asıl ad alanı öneksiz geliyor,
     * yalnız `r:id` gibi öznitelikler önekli. Kapalı modda qName ham hâliyle
     * geldiği için ikisine de tek yoldan erişiliyor.
     */
    private fun newParser(): javax.xml.parsers.SAXParser {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        ).forEach { (feature, value) -> runCatching { factory.setFeature(feature, value) } }
        runCatching { factory.isXIncludeAware = false }
        val parser = factory.newSAXParser()
        runCatching { parser.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        return parser
    }

    private fun localName(qName: String): String = qName.substringAfterLast(':')

    /** Tavana ulaşınca ayrıştırmayı erken kesmek için; hata değil, kontrol akışı. */
    private class StopParsing : SAXException()

    private class WorkbookSheet(val name: String, val relationId: String)

    private class WorkbookHandler : DefaultHandler() {
        val sheets = mutableListOf<WorkbookSheet>()
        var date1904 = false

        override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
            when (localName(qName)) {
                "workbookPr" -> {
                    // Mac Excel mirası: 1904 sisteminde tarih başlangıcı farklı.
                    val value = attrs.getValue("date1904") ?: attrs.getValue("dateCompatibility")
                    date1904 = value == "1" || value.equals("true", true)
                }
                "sheet" -> sheets += WorkbookSheet(
                    name = attrs.getValue("name").orEmpty().ifBlank { "Sayfa${sheets.size + 1}" },
                    // Gizli sayfalar da listeye girer: görüntüleyicinin işi dosyayı
                    // olduğu gibi göstermek, Excel'in gizleme kararını sürdürmek değil.
                    relationId = attrs.getValue("r:id") ?: attrs.getValue("id").orEmpty(),
                )
            }
        }
    }

    private class RelsHandler : DefaultHandler() {
        val targets = LinkedHashMap<String, String>()

        override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
            if (localName(qName) != "Relationship") return
            val id = attrs.getValue("Id") ?: return
            val target = attrs.getValue("Target") ?: return
            targets[id] = target
        }
    }

    private class SharedStringsHandler : DefaultHandler() {
        val strings = mutableListOf<String>()
        private val text = StringBuilder()
        private var inItem = false
        private var inText = false
        // <rPh> Japonca okunuş açıklaması; metnin içine karışmamalı.
        private var phonetic = 0

        override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
            when (localName(qName)) {
                "si" -> { inItem = true; text.setLength(0) }
                "rPh" -> phonetic++
                "t" -> if (inItem && phonetic == 0) inText = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, local: String?, qName: String) {
            when (localName(qName)) {
                "t" -> inText = false
                "rPh" -> phonetic--
                "si" -> { strings += text.toString(); inItem = false }
            }
        }
    }

    private class StylesHandler : DefaultHandler() {
        /** cellXfs sırası = hücrelerdeki `s` indeksinin sırası. */
        val cellFormats = mutableListOf<String>()
        private val customFormats = HashMap<Int, String>()
        private val formatIds = mutableListOf<Int>()
        private var inCellXfs = false

        override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
            when (localName(qName)) {
                "numFmt" -> {
                    val id = attrs.getValue("numFmtId")?.toIntOrNull() ?: return
                    customFormats[id] = attrs.getValue("formatCode").orEmpty()
                }
                // <cellStyleXfs> de <xf> içerir ama hücreler ona indekslenmez.
                "cellXfs" -> inCellXfs = true
                "xf" -> if (inCellXfs) formatIds += attrs.getValue("numFmtId")?.toIntOrNull() ?: 0
            }
        }

        override fun endElement(uri: String?, local: String?, qName: String) {
            if (localName(qName) == "cellXfs") inCellXfs = false
        }

        /**
         * Çözümleme BELGE BİTİMİNDE yapılır: `<numFmts>` bloğu `<cellXfs>`ten sonra
         * da gelebiliyor ve erken çözersek özel biçimler (para birimi, tarih)
         * sessizce yerleşik karşılığına düşüyordu.
         */
        override fun endDocument() {
            cellFormats.clear()
            formatIds.mapTo(cellFormats) { id -> customFormats[id] ?: BUILTIN_FORMATS[id] ?: "" }
        }
    }

    private class SheetHandler(
        private val strings: List<String>,
        private val formats: List<String>,
        private val date1904: Boolean,
    ) : DefaultHandler() {
        private val rows = mutableListOf<MutableList<SheetCell>>()
        private val widths = mutableListOf<Double>()
        private var rowTruncated = false
        private var columnTruncated = false

        private var current: MutableList<SheetCell>? = null
        private var column = -1
        private var cellType = ""
        private var styleIndex = -1
        private val value = StringBuilder()
        private var inValue = false
        private var inInline = false
        private var inInlineText = false
        private var inFormula = false

        override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
            when (localName(qName)) {
                "col" -> readColumnWidth(attrs)
                "row" -> startRow(attrs)
                "c" -> startCell(attrs)
                "v" -> if (!inFormula) { inValue = true; value.setLength(0) }
                "is" -> { inInline = true; value.setLength(0) }
                "t" -> if (inInline) inInlineText = true
                "f" -> inFormula = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inValue || inInlineText) value.append(ch, start, length)
        }

        override fun endElement(uri: String?, local: String?, qName: String) {
            when (localName(qName)) {
                "v" -> inValue = false
                "t" -> inInlineText = false
                "is" -> inInline = false
                "f" -> inFormula = false
                "c" -> endCell()
                "row" -> { current?.let(rows::add); current = null }
            }
        }

        private fun readColumnWidth(attrs: Attributes) {
            val width = attrs.getValue("width")?.toDoubleOrNull() ?: return
            val min = attrs.getValue("min")?.toIntOrNull() ?: return
            val max = attrs.getValue("max")?.toIntOrNull() ?: min
            for (index in (min - 1)..(max - 1).coerceAtMost(MAX_COLUMNS - 1)) {
                if (index < 0) continue
                while (widths.size <= index) widths += 0.0
                widths[index] = width
            }
        }

        private fun startRow(attrs: Attributes) {
            // Boş satırlar dosyada hiç yazılmayabilir; satır numaraları Excel'dekiyle
            // aynı kalsın diye aradaki boşluk doldurulur.
            val declared = attrs.getValue("r")?.toIntOrNull()
            val target = (declared?.minus(1) ?: rows.size).coerceAtLeast(rows.size)
            while (rows.size < target) {
                if (rows.size >= MAX_ROWS) { rowTruncated = true; throw StopParsing() }
                rows.add(mutableListOf())
            }
            if (rows.size >= MAX_ROWS) { rowTruncated = true; throw StopParsing() }
            current = mutableListOf()
            column = -1
        }

        private fun startCell(attrs: Attributes) {
            column = attrs.getValue("r")?.let(::columnIndexOf) ?: (column + 1)
            cellType = attrs.getValue("t").orEmpty()
            styleIndex = attrs.getValue("s")?.toIntOrNull() ?: -1
            value.setLength(0)
        }

        private fun endCell() {
            val row = current ?: return
            if (column < 0) return
            if (column >= MAX_COLUMNS) { columnTruncated = true; return }
            val cell = buildCell()
            if (cell != null) {
                while (row.size < column) row += SheetCell()
                if (row.size == column) row += cell else row[column] = cell
            }
            cellType = ""
            styleIndex = -1
        }

        private fun buildCell(): SheetCell? {
            val raw = value.toString()
            if (raw.isEmpty() && cellType != "s") return null
            return when (cellType) {
                "s" -> strings.getOrNull(raw.trim().toIntOrNull() ?: -1)
                    ?.takeIf { it.isNotEmpty() }?.let { SheetCell(it) }
                "inlineStr", "str" -> raw.takeIf { it.isNotEmpty() }?.let { SheetCell(it) }
                "b" -> SheetCell(if (raw.trim() == "1") "DOĞRU" else "YANLIŞ")
                // Hata değerleri (#YOK, #DEĞER!) dosyada zaten metin olarak durur.
                "e" -> SheetCell(raw)
                else -> {
                    val number = raw.trim().toDoubleOrNull()
                        ?: return raw.takeIf { it.isNotEmpty() }?.let { SheetCell(it) }
                    SheetCell(formatValue(number, formats.getOrNull(styleIndex).orEmpty(), date1904), numeric = true)
                }
            }
        }

        fun toGrid(name: String): SheetGrid {
            while (rows.isNotEmpty() && rows.last().all { it.text.isEmpty() }) rows.removeAt(rows.lastIndex)
            val columnCount = rows.maxOfOrNull { row ->
                row.indexOfLast { it.text.isNotEmpty() } + 1
            } ?: 0
            val notice = buildList {
                if (rowTruncated) add("yalnız ilk $MAX_ROWS satır")
                if (columnTruncated) add("yalnız ilk $MAX_COLUMNS sütun")
            }.joinToString(" ve ").let { if (it.isBlank()) "" else "Bu sayfanın $it gösteriliyor." }
            return SheetGrid(
                name = name,
                rows = rows.map { it.toList() },
                columnCount = columnCount.coerceAtMost(MAX_COLUMNS),
                columnWidths = widths.toList(),
                notice = notice,
            )
        }
    }

    // ── Sayı ve tarih biçimleme ─────────────────────────────────────────────

    /**
     * Excel'in yerleşik biçim numaraları. 14–22 tarih/saat; 14'ün gerçek karşılığı
     * yerele göre değişiyor (ABD'de m/d/yyyy) — burada Türkçe okunuşu üretiliyor,
     * çünkü gösterilecek kişi Türkiye'de.
     */
    private val BUILTIN_FORMATS: Map<Int, String> = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
        14 to "dd.mm.yyyy", 15 to "d.mmm.yy", 16 to "d.mmm", 17 to "mmm.yy",
        18 to "h:mm", 19 to "h:mm:ss", 20 to "h:mm", 21 to "h:mm:ss",
        22 to "dd.mm.yyyy h:mm",
        37 to "#,##0", 38 to "#,##0", 39 to "#,##0.00", 40 to "#,##0.00",
        44 to "#,##0.00", 45 to "mm:ss", 46 to "h:mm:ss", 47 to "mm:ss.0",
        48 to "##0.0E+0", 49 to "@",
    )

    /**
     * Biçim kodundaki METİN parçalarını atar; geriye yalnız biçim karakterleri kalır.
     * Bu şart: `0" gün"` kodundaki tırnak içi "gün" ayıklanmazsa `d` harfi yüzünden
     * hücre tarih sanılır.
     */
    internal fun stripLiterals(code: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < code.length) {
            when (code[i]) {
                '"' -> { i++; while (i < code.length && code[i] != '"') i++ }
                // [Red], [$-41F], [h] gibi bloklar
                '[' -> while (i < code.length && code[i] != ']') i++
                // \x kaçırma, _x hizalama boşluğu, *x doldurma: hepsi sonraki karakteri yutar
                '\\', '_', '*' -> i++
                else -> out.append(code[i])
            }
            i++
        }
        return out.toString()
    }

    /** Biçim kodunun ilk bölümü pozitif sayılar içindir; gerisi negatif/sıfır/metin. */
    private fun firstSection(code: String): String = code.substringBefore(';')

    internal fun isDateFormat(code: String): Boolean {
        val clean = stripLiterals(firstSection(code)).trim()
        if (clean.isEmpty() || clean.equals("General", true) || clean == "@") return false
        return clean.any { it.lowercaseChar() in "ydhs" }
    }

    internal fun formatValue(number: Double, code: String, date1904: Boolean): String =
        if (isDateFormat(code)) formatDate(number, code, date1904) else formatNumber(number, code)

    internal fun formatDate(serial: Double, code: String, date1904: Boolean): String {
        val moment = serialToDateTime(serial, date1904) ?: return formatNumber(serial, "")
        val clean = stripLiterals(firstSection(code)).lowercase(Locale.ROOT)
        val hasDay = clean.contains('d')
        val hasYear = clean.contains('y')
        val hasTime = clean.contains('h') || clean.contains('s')
        val date = when {
            hasDay && hasYear -> "%02d.%02d.%04d".format(moment.dayOfMonth, moment.monthValue, moment.year)
            hasYear -> "%02d.%04d".format(moment.monthValue, moment.year)
            hasDay -> "%02d.%02d".format(moment.dayOfMonth, moment.monthValue)
            else -> ""
        }
        val time = when {
            !hasTime -> ""
            clean.contains('s') -> "%02d:%02d:%02d".format(moment.hour, moment.minute, moment.second)
            else -> "%02d:%02d".format(moment.hour, moment.minute)
        }
        return listOf(date, time).filter { it.isNotEmpty() }.joinToString(" ")
            .ifEmpty { formatNumber(serial, "") }
    }

    /**
     * Excel seri numarasını tarihe çevirir.
     *
     * 1900 sisteminde 1900 yılı yanlışlıkla artık yıl sayılır (Lotus 1-2-3 mirası),
     * bu yüzden 60'tan küçük seriler bir gün kaymış durumda. İki farklı başlangıç
     * kullanmak bu çentiği düzeltmenin standart yolu.
     */
    private fun serialToDateTime(serial: Double, date1904: Boolean): LocalDateTime? {
        if (serial.isNaN() || serial.isInfinite() || serial < 0 || serial > 2_958_465) return null
        val days = floor(serial).toLong()
        val base = when {
            date1904 -> LocalDate.of(1904, 1, 1)
            days < 60 -> LocalDate.of(1899, 12, 31)
            else -> LocalDate.of(1899, 12, 30)
        }
        val seconds = ((serial - days) * 86_400.0).roundToLong().coerceIn(0, 86_399)
        return base.plusDays(days).atStartOfDay().plusSeconds(seconds)
    }

    /**
     * Ondalık ayırıcı virgül, binlik ayırıcı nokta: dosyanın değil KULLANICININ
     * yereli belirler. Sabit seçilmesinin sebebi testlerin çalıştığı JVM'in
     * yereline bağımlı olmaması.
     */
    private val TURKISH_SYMBOLS = DecimalFormatSymbols(Locale.ROOT).apply {
        decimalSeparator = ','
        groupingSeparator = '.'
    }

    internal fun formatNumber(number: Double, code: String): String {
        if (number.isNaN() || number.isInfinite()) return number.toString()
        val section = firstSection(code)
        val clean = stripLiterals(section).trim()
        if (clean.isEmpty() || clean.equals("General", true) || clean == "@") return generalNumber(number)
        // Bilimsel gösterim (0.00E+00): DecimalFormat "E+" sözdizimini kabul
        // etmiyor ve bu biçim hukuk/muhasebe tablolarında pratikte hiç geçmiyor.
        if (clean.any { it == 'E' || it == 'e' }) return generalNumber(number)
        val pattern = toDecimalPattern(section)
        if (pattern.none { it == '#' || it == '0' }) return generalNumber(number)
        return runCatching {
            DecimalFormat(pattern, TURKISH_SYMBOLS).apply {
                // Excel sıfırdan UZAĞA yuvarlar; DecimalFormat'ın varsayılanı
                // HALF_EVEN, yani 1234,5 -> 1234 ("çift olana" yuvarlama). Para
                // sütununda kimsenin fark etmeyeceği bir kuruş hatası demekti.
                roundingMode = java.math.RoundingMode.HALF_UP
            }.format(number)
        }.getOrElse { generalNumber(number) }
    }

    /**
     * Excel biçim kodunu DecimalFormat desenine çevirir.
     *
     * stripLiterals'ın tersine METNİ ATMAZ, tırnağa alır: `#,##0.00 "TL"` kodundaki
     * "TL" ayıklanınca bilirkişi hesap tablosunda para birimi kayboluyordu. Bitişik
     * düz karakterler TEK tırnak bloğunda toplanır — `' ''TL'` gibi ardışık bloklar
     * DecimalFormat'ta kesme işareti kaçırma sayılıp çıktıyı bozuyor.
     */
    internal fun toDecimalPattern(section: String): String {
        val out = StringBuilder()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isEmpty()) return
            out.append('\'').append(literal.toString().replace("'", "''")).append('\'')
            literal.setLength(0)
        }
        var i = 0
        while (i < section.length) {
            when (val c = section[i]) {
                '"' -> {
                    val end = section.indexOf('"', i + 1)
                    literal.append(if (end < 0) section.substring(i + 1) else section.substring(i + 1, end))
                    i = if (end < 0) section.length else end
                }
                // [Red], [$-41F], [h] — biçim değil yönerge; gösterime girmez.
                '[' -> i = section.indexOf(']', i).let { if (it < 0) section.length else it }
                '\\' -> if (i + 1 < section.length) literal.append(section[++i])
                // _x hizalama boşluğu, *x doldurma: sonraki karakteri yutar.
                '_' -> { i++; literal.append(' ') }
                '*' -> i++
                // ? kesir basamağı: sayı kalıbı olarak karşılığı yok, boşluk kalır.
                '?' -> literal.append(' ')
                '#', '0', '.', ',', '%', '-' -> { flush(); out.append(c) }
                else -> literal.append(c)
            }
            i++
        }
        flush()
        return out.toString()
    }

    /**
     * Biçimsiz ("General") sayı: Excel gibi 11 anlamlı basamağa yuvarlar ve
     * binlik ayırıcı KOYMAZ. Tam sayılar ondalıksız görünür — 2023 yılını
     * "2.023,00" diye göstermek okumayı bozuyordu.
     */
    private fun generalNumber(number: Double): String {
        if (number == floor(number) && abs(number) < 1e15) return number.toLong().toString()
        return BigDecimal(number).round(MathContext(11)).stripTrailingZeros()
            .toPlainString().replace('.', ',')
    }

    // ── Hücre adresi ────────────────────────────────────────────────────────

    /** "BC12" -> 54 (0 tabanlı sütun). Geçersizse -1. */
    internal fun columnIndexOf(reference: String): Int {
        var index = 0
        var seen = false
        for (ch in reference) {
            val upper = ch.uppercaseChar()
            if (upper !in 'A'..'Z') break
            index = index * 26 + (upper - 'A' + 1)
            seen = true
        }
        return if (seen) index - 1 else -1
    }

    /** 0 -> "A", 26 -> "AA". Sütun başlıklarında kullanılır. */
    fun columnLabel(index: Int): String {
        if (index < 0) return ""
        var value = index
        val out = StringBuilder()
        while (true) {
            out.append('A' + value % 26)
            value = value / 26 - 1
            if (value < 0) break
        }
        return out.reverse().toString()
    }
}

/** Tek hücre. [numeric] yalnız hizalama içindir: sayılar sağa yaslanır. */
data class SheetCell(val text: String = "", val numeric: Boolean = false)

/**
 * Tek sayfa. [rows] SEYREKTİR: her satır kendi son dolu hücresinde biter, bu
 * yüzden okuyan taraf `getOrNull` kullanmalı. Dikdörtgene tamamlamak boş bir
 * çalışma sayfasında bile yüz binlerce nesne demekti.
 */
data class SheetGrid(
    val name: String,
    val rows: List<List<SheetCell>> = emptyList(),
    val columnCount: Int = 0,
    /** Karakter cinsinden sütun genişlikleri (dosyadaki değer); eksik olabilir. */
    val columnWidths: List<Double> = emptyList(),
    /** Boş değilse kullanıcıya gösterilecek kısıt açıklaması. */
    val notice: String = "",
)

data class XlsxWorkbook(val sheets: List<SheetGrid> = emptyList())
