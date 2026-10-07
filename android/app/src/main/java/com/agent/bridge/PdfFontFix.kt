package com.agent.bridge

import java.util.zip.Deflater

/**
 * Gömülü fontu olmayan PDF'lere font gömer.
 *
 * **Sorun.** UYAP evrakı (iText 2.1.7) fontları GÖMMEZ; yalnız `/BaseFont /ArialMT`
 * gibi bir adla anar ve Türkçe harfleri `/Differences` içinde Adobe glyph adlarıyla
 * verir (`Gbreve`, `Idotaccent`, `scedilla`…). PC'de Arial kurulu olduğu için dosya
 * düzgün görünür. Android'de o fontlar yok; PdfRenderer'ın altındaki pdfium yerine
 * bir sistem fontu koyuyor ama glyph adlarını çözemiyor. Ölçüldü (Honor Magic7 Pro,
 * Android 15): Latin-1'de karşılığı olan Ç Ö Ü ç ö ü çiziliyor, **Ğ ğ İ Ş ş ise
 * hiç çizilmiyor** — "Şehir" ekrana " ehir" olarak düşüyor.
 *
 * **Çözüm.** Adları `uniXXXX`e çevirmek işe yaramıyor (denendi; render bayt bayt
 * aynı çıktı). İşe yarayan tek şey fontu gerçekten gömmek: aynı PDF'e `/FontFile2`
 * eklendiğinde bütün harfler çıkıyor. Burada yapılan bu — dosyanın sonuna bir
 * **artımlı güncelleme** (incremental update) eklenir: gömülü fontu olmayan her
 * `/FontDescriptor` nesnesinin yeni bir sürümü yazılır, uygun yüz de gömülür.
 * Dosyanın başı hiç değişmediği için eski xref kayıtları geçerli kalır.
 *
 * **Kapsam bilinçli olarak dar; şüphede olduğunda dokunmayıp null döner.** Klasik
 * `xref` tablosu yoksa (xref akışı), dosya şifreliyse ya da font tanımı sıkıştırılmış
 * bir nesne akışının içindeyse düzeltme uygulanmaz. Bu durumda görüntü bugünküyle
 * aynı kalır — hiçbir şey bozulmaz.
 */
object PdfFontFix {

    /**
     * Bunun üstünde dosyaya bakılmaz. Gömülü fontu olmayan PDF pratikte metin
     * evrakıdır ve birkaç MB'ı geçmez; bu boyutun üstü taranmış görüntüdür, orada
     * zaten düzeltilecek font yoktur. Sınır aynı zamanda bellek tavanıdır: dosya
     * bir kez daha kopyalanıyor.
     */
    const val MAX_INPUT_BYTES: Int = 16 * 1024 * 1024

    enum class Family { SANS, SERIF, MONO }

    data class Face(val family: Family, val bold: Boolean, val italic: Boolean) {
        /** assets/pdf-fonts/ altındaki dosya adı. */
        val assetName: String
            get() {
                val f = family.name.lowercase()
                val w = when {
                    bold && italic -> "bolditalic"
                    bold -> "bold"
                    italic -> "italic"
                    else -> "regular"
                }
                return "$f-$w.ttf"
            }
    }

    /**
     * [source] içindeki gömülü olmayan fontları gömer. Düzeltilecek bir şey yoksa,
     * dosya desteklenmeyen bir biçimdeyse ya da [font] yüz veremiyorsa null döner —
     * çağıran o zaman orijinali kullanır.
     */
    fun repair(source: ByteArray, font: (Face) -> ByteArray?): ByteArray? {
        if (source.size > MAX_INPUT_BYTES || source.size < 32) return null

        // Yalnız klasik xref tablosu: xref akışına klasik tablo eklemek biçim dışı.
        val xrefOffset = resolveXrefTable(source) ?: return null
        val trailer = trailerDictAt(source, xrefOffset) ?: return null
        // Şifreli dosyada eklediğimiz nesneler şifresiz olurdu; okuyucu çözemez.
        if (trailer.contains("/Encrypt")) return null
        val size = dictInt(trailer, "/Size") ?: return null

        val brokenDescriptors = findObjects(source, "/FontDescriptor") {
            IS_DESCRIPTOR.containsMatchIn(it) && !hasEmbeddedFont(it)
        }
        val base14Fonts = findObjects(source, "/BaseFont", ::needsSyntheticDescriptor)
        if (brokenDescriptors.isEmpty() && base14Fonts.isEmpty()) return null

        // Aynı yüzü birden çok tanım paylaşabilir; akışı bir kez gömüyoruz.
        val streamObjects = LinkedHashMap<Face, Int>()
        val out = java.io.ByteArrayOutputStream(source.size + 64 * 1024)
        out.write(source, 0, pdfEnd(source, xrefOffset))
        out.write('\n'.code)
        var nextObj = size
        val added = LinkedHashMap<Int, Int>() // nesne no -> ofset

        fun streamFor(face: Face): Int {
            val cached = streamObjects[face]
            if (cached != null) return cached
            val bytes = font(face)
            val id = if (bytes == null) -1 else nextObj++
            if (bytes != null) {
                added[id] = out.size()
                writeFontStream(out, id, bytes)
            }
            streamObjects[face] = id // yüz yoksa bir daha deneme
            return id
        }

        // 1) Tanımı olan ama fontu gömülü olmayanlar: tanımı yeniden yaz.
        for (desc in brokenDescriptors) {
            val streamObj = streamFor(faceOf(desc.dict))
            if (streamObj < 0) continue
            added[desc.objNum] = out.size()
            // Sözlüğün sonundaki ">>" atılır, yerine FontFile2 bağı eklenir.
            val body = desc.dict.substring(0, desc.dict.length - 2) + "/FontFile2 $streamObj 0 R>>"
            out.write(ascii("${desc.objNum} 0 obj\n$body\nendobj\n"))
        }

        // 2) Temel-14 fontlar: tanım nesnesi hiç yok, ikisini birden üretiyoruz.
        for (fontObj in base14Fonts) {
            val face = faceOf(fontObj.dict)
            val streamObj = streamFor(face)
            if (streamObj < 0) continue
            val descriptorId = nextObj++
            added[descriptorId] = out.size()
            out.write(ascii("$descriptorId 0 obj\n${syntheticDescriptor(fontObj.dict, streamObj)}\nendobj\n"))
            added[fontObj.objNum] = out.size()
            // Type1'de /FontFile2 geçersiz; gömebilmek için font TrueType'a çevrilir.
            // Widths dizisi olduğu gibi kaldığı için satır düzeni değişmiyor.
            val body = TYPE1_SUBTYPE.replace(fontObj.dict, "/Subtype/TrueType")
                .let { it.substring(0, it.length - 2) + "/FontDescriptor $descriptorId 0 R>>" }
            out.write(ascii("${fontObj.objNum} 0 obj\n$body\nendobj\n"))
        }
        if (added.isEmpty()) return null

        val newXref = out.size()
        out.write(ascii(buildXref(added)))
        out.write(ascii(buildTrailer(trailer, nextObj, xrefOffset, newXref)))
        return out.toByteArray()
    }

    // ── PDF gezinme ────────────────────────────────────────────────────────────

    private class PdfObject(val objNum: Int, val dict: String)

    /**
     * Son klasik xref tablosunun ofseti. Önce `startxref` denenir, tutmazsa tablo
     * dosyada elle aranır — iki ayrı gerçek dosya sorunu bunu gerektirdi:
     *
     * - E-imzalı kapsayıcılarda (UYAP'ın "CMS" ihbarnameleri) `%%EOF`ten sonra
     *   kilobaytlarca imza verisi geliyor; bu yüzden tüm dosya taranıyor.
     * - Aynı dosyalarda `startxref` **yanlış** olabiliyor (ölçüldü: tablo 12600'de,
     *   işaretçi 12536 diyor). pdfium bu durumda xref'i baştan kuruyor, biz de
     *   tabloyu kendimiz buluyoruz — yoksa düzeltmeden geçerdik.
     */
    private fun resolveXrefTable(src: ByteArray): Int? {
        val announced = lastStartXref(src)
        if (announced != null && matchesAt(src, announced, "xref")) return announced
        // `startxref` içindeki "xref" elenmeli: solunda satır sonu değil 't' var.
        var at = lastIndexOf(src, ascii("xref"), 0)
        while (at != null) {
            val before = if (at > 0) src[at - 1].toInt().toChar() else '\n'
            val after = if (at + 4 < src.size) src[at + 4].toInt().toChar() else ' '
            if ((before == '\n' || before == '\r') && (after == '\n' || after == '\r' || after == ' ')) return at
            at = lastIndexOf(src, ascii("xref"), 0, at)
        }
        return null
    }

    /**
     * PDF'in gerçek sonu: xref tablosunu izleyen `%%EOF`. Sonrasında ne varsa
     * (e-imzalı kapsayıcılarda 11 KB'lık CMS zarfı) **atılır**.
     *
     * Bu şart, süs değil. Ölçüldü: kuyruk yerinde kalınca pdfium eklediğimiz
     * nesneleri hiç görmüyor — dosyanın kendi xref'i bozuk olduğu için tabloyu
     * baştan kuruyor ve tarama ikili zarfta kesiliyor. Kuyruk kesilince aynı
     * düzeltme çalışıyor. Zarf yalnızca imza doğrulaması için gerekli; biz
     * görüntüleme kopyası üretiyoruz, aslına dokunmuyoruz.
     */
    private fun pdfEnd(src: ByteArray, xrefOffset: Int): Int {
        val trailerAt = indexOf(src, ascii("trailer"), xrefOffset) ?: return src.size
        val eof = indexOf(src, ascii("%%EOF"), trailerAt) ?: return src.size
        return eof + 5
    }

    /** Son `startxref` sayısı; dosya sonu değil tüm dosya taranır. */
    private fun lastStartXref(src: ByteArray): Int? {
        val at = lastIndexOf(src, ascii("startxref"), 0) ?: return null
        var i = at + 9
        while (i < src.size && src[i].toInt().toChar().isWhitespace()) i++
        val sb = StringBuilder()
        while (i < src.size && src[i].toInt().toChar().isDigit()) {
            sb.append(src[i].toInt().toChar())
            i++
        }
        val value = sb.toString().toIntOrNull() ?: return null
        return if (value in 0 until src.size) value else null
    }

    /**
     * Xref tablosunun hemen ardındaki `trailer` sözlüğü. Dosyanın sonundan aramak
     * e-imzalı kapsayıcılarda çalışmıyor; xref'ten ileri bakmak hem kesin hem hızlı.
     */
    private fun trailerDictAt(src: ByteArray, xrefOffset: Int): String? {
        val at = indexOf(src, ascii("trailer"), xrefOffset) ?: return null
        val open = indexOf(src, ascii("<<"), at + 7) ?: return null
        val end = dictEnd(src, open) ?: return null
        return latin1(src, open, end)
    }

    /**
     * İçinde [needle] geçen ve [accept] süzgecinden geçen nesneleri toplar.
     * Sıkıştırılmış nesne akışlarındaki (ObjStm) tanımlar burada görünmez;
     * o dosyalar düzeltilmeden geçer.
     */
    private fun findObjects(src: ByteArray, needleText: String, accept: (String) -> Boolean): List<PdfObject> {
        val needle = ascii(needleText)
        val found = ArrayList<PdfObject>()
        val seen = HashSet<Int>()
        var at = indexOf(src, needle, 0)
        while (at != null) {
            val header = objectHeaderBefore(src, at)
            if (header != null) {
                val open = indexOf(src, ascii("<<"), header.second)
                val end = if (open != null && open < at) dictEnd(src, open) else null
                // Anahtar sözlüğün İÇİNDE olmalı: font nesnesi de tanımı
                // `/FontDescriptor 6 0 R` diye anıyor, onu tanım sanmayalım.
                if (open != null && end != null && at < end && seen.add(header.first)) {
                    val dict = latin1(src, open, end)
                    if (accept(dict)) found.add(PdfObject(header.first, dict))
                }
            }
            at = indexOf(src, needle, at + needle.size)
        }
        return found
    }

    /**
     * [at]'ten geriye doğru en yakın `N 0 obj` başlığını arar; (nesne no, `obj`
     * sonrası indeks) döner. `endobj` elenir çünkü `obj`un solunda boşluk yok.
     * Üretim numarası 0 değilse null: artımlı güncellemede aynı nesnenin başka bir
     * kuşağını yazmak yanlış nesneyi gölgeler.
     */
    private fun objectHeaderBefore(src: ByteArray, at: Int): Pair<Int, Int>? {
        val obj = ascii("obj")
        // 8 KB pencere: nesne başlığı ile font tanımı arasında bundan fazlası olmaz
        // (aradaki en uzun şey /Widths dizisi, ~1 KB).
        val floor = maxOf(0, at - 8192)
        var i = at
        while (true) {
            val hit = lastIndexOf(src, obj, floor, i) ?: return null
            i = hit
            if (hit > 0 && src[hit - 1].toInt().toChar().isWhitespace()) {
                var k = hit - 1
                while (k >= 0 && src[k].toInt().toChar().isWhitespace()) k--
                val genEnd = k
                while (k >= 0 && src[k].toInt().toChar().isDigit()) k--
                val gen = if (k == genEnd) null else latin1(src, k + 1, genEnd + 1).toIntOrNull()
                while (k >= 0 && src[k].toInt().toChar().isWhitespace()) k--
                val numEnd = k
                while (k >= 0 && src[k].toInt().toChar().isDigit()) k--
                val num = if (k == numEnd) null else latin1(src, k + 1, numEnd + 1).toIntOrNull()
                if (num != null && gen == 0) return num to (hit + 3)
            }
            if (hit <= floor) return null
        }
    }

    /**
     * `<<` ile başlayan sözlüğün bitiş indeksi (`>>` dahil). İç içe sözlükleri
     * sayar, dizeleri atlar — `(a>>b)` içindeki `>>` sözlüğü kapatmaz.
     */
    private fun dictEnd(src: ByteArray, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < src.size - 1) {
            val c = src[i].toInt().toChar()
            when {
                c == '(' -> {
                    i++
                    var paren = 1
                    while (i < src.size && paren > 0) {
                        when (src[i].toInt().toChar()) {
                            '\\' -> i++
                            '(' -> paren++
                            ')' -> paren--
                        }
                        i++
                    }
                    continue
                }
                c == '<' && src[i + 1].toInt().toChar() == '<' -> { depth++; i += 2; continue }
                c == '>' && src[i + 1].toInt().toChar() == '>' -> {
                    depth--
                    i += 2
                    if (depth == 0) return i
                    continue
                }
            }
            i++
        }
        return null
    }

    // ── Yüz seçimi ─────────────────────────────────────────────────────────────

    private val IS_DESCRIPTOR = Regex("/Type\\s*/FontDescriptor")
    private val IS_FONT = Regex("/Type\\s*/Font(?![A-Za-z])")
    private val TYPE1_SUBTYPE = Regex("/Subtype\\s*/Type1(?![A-Za-z0-9])")

    private fun hasEmbeddedFont(dict: String): Boolean =
        dict.contains("/FontFile")

    /**
     * Temel-14 fontlar (`/Type1 /Helvetica` gibi) tanım nesnesi taşımak zorunda
     * değil — biçim, okuyucunun fontu kendi sağlamasını bekliyor. Android'de o
     * font yok; ikame edilen fonta da glyph adları çözülemediği için Türkçe harfler
     * düşüyor. Böyle fontlara tanım + gömülü yüz üretiyoruz.
     *
     * İki koşul birden aranıyor:
     * - `/Differences` OLMALI. Düz `WinAnsiEncoding` zaten Türkçe harf taşıyamaz;
     *   ona dokunmak kazanç değil, yalnız risk olur.
     * - `/Widths` OLMALI. Temel-14 fontun genişlikleri okuyucunun içindedir;
     *   dizisi olmayan bir fontu gömülü fonta çevirirsek satır düzeni kayar.
     */
    private fun needsSyntheticDescriptor(dict: String): Boolean =
        IS_FONT.containsMatchIn(dict) &&
            !dict.contains("/FontDescriptor") &&
            TYPE1_SUBTYPE.containsMatchIn(dict) &&
            dict.contains("/Differences") &&
            dict.contains("/Widths")

    /** Üretilen tanım; değerler gömülü font varken görüntüyü etkilemiyor. */
    private fun syntheticDescriptor(fontDict: String, streamObj: Int): String {
        val base = dictName(fontDict, "/BaseFont").ifEmpty { "Helvetica" }
        val face = faceOf(fontDict)
        val flags = (if (face.family == Family.MONO) 1 else 0) or
            (if (face.family == Family.SERIF) 2 else 0) or 32 or
            (if (face.italic) 1 shl 6 else 0) or (if (face.bold) 1 shl 18 else 0)
        return "<</Type/FontDescriptor/FontName/$base/Flags $flags" +
            "/FontBBox[-665 -325 2000 1040]/ItalicAngle ${if (face.italic) -12 else 0}" +
            "/Ascent 728/Descent -210/CapHeight 716/StemV ${if (face.bold) 140 else 80}" +
            "/FontFile2 $streamObj 0 R>>"
    }

    /**
     * Tanımdan (ya da temel-14'te font nesnesinden) aile/kalınlık/eğiklik çıkarır.
     * Ad ile bayrakları birlikte okur: iText adı doğru yazar ama bayrakları eksik
     * bırakabiliyor, tersi de olur.
     */
    internal fun faceOf(dict: String): Face {
        // Alt küme öneki (ABCDEF+ArialMT) atılır. lowercase() kök yerelde çalışır,
        // tr-TR'nin I/ı eşlemesine takılmaz.
        val name = dictName(dict, "/FontName").ifEmpty { dictName(dict, "/BaseFont") }
            .substringAfter('+').lowercase()
        val flags = dictInt(dict, "/Flags") ?: 0
        val italicAngle = dictInt(dict, "/ItalicAngle") ?: 0
        val stemV = dictInt(dict, "/StemV") ?: 0

        val bold = name.contains("bold") || name.contains("black") || name.contains("heavy") ||
            (flags and (1 shl 18)) != 0 || stemV >= 120
        val italic = name.contains("italic") || name.contains("oblique") ||
            (flags and (1 shl 6)) != 0 || italicAngle != 0
        val family = when {
            (flags and 1) != 0 || name.contains("mono") || name.contains("courier") ||
                name.contains("consol") -> Family.MONO
            name.contains("arial") || name.contains("helvetica") || name.contains("verdana") ||
                name.contains("tahoma") || name.contains("calibri") || name.contains("sans") ->
                Family.SANS
            (flags and 2) != 0 || name.contains("times") || name.contains("serif") ||
                name.contains("georgia") || name.contains("garamond") || name.contains("roman") ->
                Family.SERIF
            else -> Family.SANS
        }
        return Face(family, bold, italic)
    }

    // ── Yazma ──────────────────────────────────────────────────────────────────

    private fun writeFontStream(out: java.io.ByteArrayOutputStream, id: Int, ttf: ByteArray) {
        val packed = deflate(ttf)
        out.write(ascii("$id 0 obj\n<</Length ${packed.size}/Length1 ${ttf.size}/Filter/FlateDecode>>stream\n"))
        out.write(packed)
        out.write(ascii("\nendstream\nendobj\n"))
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream(data.size / 2)
        val buf = ByteArray(16 * 1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    /** Artımlı xref: yalnız değişen/eklenen nesneler, ardışık gruplar halinde. */
    private fun buildXref(entries: Map<Int, Int>): String {
        val sb = StringBuilder("xref\n")
        val nums = entries.keys.sorted()
        var i = 0
        while (i < nums.size) {
            var j = i
            while (j + 1 < nums.size && nums[j + 1] == nums[j] + 1) j++
            sb.append(nums[i]).append(' ').append(j - i + 1).append('\n')
            for (k in i..j) sb.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", entries[nums[k]]))
            i = j + 1
        }
        return sb.toString()
    }

    /**
     * Yeni trailer eskisinin kopyası; `/Size` büyütülür ve `/Prev` bir önceki xref'e
     * bağlanır. `/XRefStm` atılır — o, /Prev ile gelen eski bölüme aitti; burada
     * bırakılırsa okuyucu bizim bölümümüzü melez sanar.
     */
    private fun buildTrailer(old: String, size: Int, prevXref: Int, newXref: Int): String {
        var dict = old
        dict = dict.replace(Regex("/Prev\\s+\\d+"), "")
        dict = dict.replace(Regex("/XRefStm\\s+\\d+"), "")
        dict = dict.replace(Regex("/Size\\s+\\d+"), "/Size $size")
        dict = dict.substring(0, dict.length - 2).trimEnd() + "/Prev $prevXref>>"
        return "trailer\n$dict\nstartxref\n$newXref\n%%EOF\n"
    }

    // ── Bayt yardımcıları ──────────────────────────────────────────────────────

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    private fun latin1(src: ByteArray, from: Int, to: Int): String =
        String(src, from, to - from, Charsets.ISO_8859_1)

    private fun matchesAt(src: ByteArray, at: Int, text: String): Boolean {
        val needle = ascii(text)
        if (at < 0 || at + needle.size > src.size) return false
        for (i in needle.indices) if (src[at + i] != needle[i]) return false
        return true
    }

    private fun indexOf(src: ByteArray, needle: ByteArray, from: Int): Int? {
        if (needle.isEmpty()) return null
        outer@ for (i in maxOf(0, from)..src.size - needle.size) {
            for (j in needle.indices) if (src[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }

    private fun lastIndexOf(src: ByteArray, needle: ByteArray, from: Int, until: Int = src.size): Int? {
        outer@ for (i in minOf(until, src.size) - needle.size downTo maxOf(0, from)) {
            for (j in needle.indices) if (src[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }

    private fun dictInt(dict: String, key: String): Int? =
        Regex("${Regex.escape(key)}\\s+(-?\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull()

    private fun dictName(dict: String, key: String): String =
        Regex("${Regex.escape(key)}\\s*/([^\\s/>\\[\\]()]+)").find(dict)?.groupValues?.get(1).orEmpty()
}
