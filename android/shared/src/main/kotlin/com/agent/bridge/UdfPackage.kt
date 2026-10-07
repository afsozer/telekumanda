package com.agent.bridge

import com.agent.bridge.udf.UdfBlock
import com.agent.bridge.udf.UdfCell
import com.agent.bridge.udf.UdfDocument
import com.agent.bridge.udf.UdfElement
import com.agent.bridge.udf.UdfLineRef
import com.agent.bridge.udf.UdfParagraphBlock
import com.agent.bridge.udf.UdfParser
import com.agent.bridge.udf.UdfTableBlock
import com.agent.bridge.udf.replaceLineText
import com.agent.bridge.udf.setLineAlignment
import com.agent.bridge.udf.setLineFontSize
import com.agent.bridge.udf.toggleLineStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * UDF belgesini blok editörünün modeline ([DocxBlock]) çevirir ve yazdırma
 * düzeni görünümüne belgenin kendisini verir.
 *
 * Düzenleme SATIR düzeyindedir ve yalnız yazdırma düzeninden yapılır
 * ([replaceLine]); blok listesi UDF'te salt okunur kalır — kullanıcı kararı
 * 16.08.2026: düzenleme sayfanın üstünde olacak, kart listesinde değil.
 *
 * İmzalı belge hiç düzenlenmez ([readOnly]): metni değiştirmek `sign.sgn`
 * içindeki imzayı geçersiz kılar, belge UYAP'ta doğrulanamaz hale gelir. İmzalı
 * bir belgeyi değiştirmek isteyen kullanıcı önce kopyasını almalı.
 *
 * Yazma yolu 16.08.2026'da onarıldı (bkz. [UdfParser.writeUdf] ve
 * `UdfWriterTest`): etiket kimliği (`field`/`tab`) korunuyor, gövde ham
 * dilimlerden kuruluyor, tablolar ham XML olarak yerinde kalıp yalnız ofsetleri
 * tazeleniyor. Belge değişmediyse [toBytes] açılan baytları AYNEN döndürür —
 * dokunulmamış bir belgeyi yeniden kurmanın kazancı yok, riski var.
 */
class UdfPackage private constructor(
    private val originalBytes: ByteArray,
    initialDocument: UdfDocument,
) : EditableDocumentPackage {

    /** Çözümlenmiş belge — yazdırma düzeni görünümü bunun üzerinden çizilir. */
    var document: UdfDocument = initialDocument
        private set

    private var edited = false

    override val signed: Boolean = document.isSigned

    override val readOnly: Boolean = document.isSigned

    private var currentBlocks: List<DocxBlock> = buildBlocks(document)

    override fun blocks(): List<DocxBlock> = currentBlocks

    /** Blok listesinden düzenleme yok; UDF'te düzenleme [replaceLine] ile yapılır. */
    override fun updateParagraph(block: DocxBlock) = Unit

    override fun applyFontToAll(font: String) = Unit

    /**
     * [line] adresindeki satırı yeniden yazar. Belge değiştiyse true döner.
     * İmzalı belgede, adres geçersizse ya da satır kilitliyse (şablon alanı,
     * sekme, koşullu bölge harcı) hiçbir şey yapmaz.
     */
    fun replaceLine(line: UdfLineRef, text: String): Boolean {
        if (readOnly) return false
        val next = document.replaceLineText(line, text)
        if (next === document) return false
        document = next
        edited = true
        currentBlocks = buildBlocks(next)
        return true
    }

    fun toggleLineStyle(line: UdfLineRef, start: Int, end: Int, style: String): Boolean =
        replaceDocument(document.toggleLineStyle(line, start, end, style))

    fun setLineFontSize(line: UdfLineRef, start: Int, end: Int, size: Int): Boolean =
        replaceDocument(document.setLineFontSize(line, start, end, size))

    fun setLineAlignment(line: UdfLineRef, alignment: Int): Boolean =
        replaceDocument(document.setLineAlignment(line, alignment))

    private fun replaceDocument(next: UdfDocument): Boolean {
        if (readOnly || next === document || next == document) return false
        document = next
        edited = true
        currentBlocks = buildBlocks(next)
        return true
    }

    override fun toBytes(): ByteArray {
        if (!edited) return originalBytes
        return ByteArrayOutputStream().also { UdfParser.writeUdf(it, document) }.toByteArray()
    }

    /**
     * Mobil imza için imzalanacak baytlar: belgenin İMZASIZ hâli, yeniden
     * serileştirilmiş.
     *
     * [toBytes] BİLEREK kullanılmıyor. O, belge düzenlenmediyse açılan baytları
     * aynen döndürüyor; imzalanan baytlarla sonradan diske yazılan baytların
     * içeriği birbirinin AYNISI olmak zorunda, yoksa imza doğrulanmaz. İkisi de
     * `writeUdf`ten geçince content.xml bit bit aynı oluyor; tek fark imzalı
     * kopyadaki fazladan `sign.sgn` girdisi.
     */
    fun unsignedBytes(): ByteArray = ByteArrayOutputStream().also {
        UdfParser.writeUdf(it, document.copy(isSigned = false, signatureBytes = null))
    }.toByteArray()

    /** İmzayı `sign.sgn` olarak gömer; [unsignedBytes] ile aynı içeriği yazar. */
    fun signedBytes(signature: ByteArray): ByteArray = ByteArrayOutputStream().also {
        UdfParser.writeUdf(it, document.copy(isSigned = true, signatureBytes = signature))
    }.toByteArray()

    companion object {
        private const val BLOCK_ID_PREFIX = "udf_"

        fun open(bytes: ByteArray): UdfPackage {
            val doc = runCatching {
                ByteArrayInputStream(bytes).use { UdfParser.readUdf(it) }
            }.getOrElse { throw IllegalArgumentException("UDF içeriği okunamadı: ${it.message}", it) }
            return UdfPackage(bytes, doc)
        }

        /**
         * Gösterim blokları zaten SATIR başınadır (bkz. `splitIntoLines`), burada
         * yalnız [DocxBlock]'a çevrilir.
         */
        private fun buildBlocks(doc: UdfDocument): List<DocxBlock> {
            val out = mutableListOf<DocxBlock>()
            var paragraphOrdinal = 0
            var tableOrdinal = 0
            for (block in doc.blocks) {
                when (block) {
                    is UdfParagraphBlock -> {
                        val text = block.paragraph.elements.joinToString("") { it.textRun }
                        out.add(
                            DocxBlock(
                                id = "$BLOCK_ID_PREFIX${paragraphOrdinal++}",
                                // Boş satır kart değil, boşluktur: eski sürüm her boş satır için
                                // "[Korunan belge öğesi]" yazan gri bir kart çiziyordu ve gerçek
                                // metin ekranın 10 kart altında kalıyordu.
                                kind = if (text.isBlank()) "blank" else "paragraph",
                                text = text,
                                spans = spansForRange(block.paragraph.elements, 0, text.length),
                                alignment = alignmentName(block.paragraph.alignment),
                                editable = false,
                            )
                        )
                    }
                    is UdfTableBlock -> {
                        out.add(
                            DocxBlock(
                                id = "${BLOCK_ID_PREFIX}tbl_${tableOrdinal++}",
                                kind = "table",
                                text = tableText(block),
                                editable = false,
                            )
                        )
                    }
                }
            }
            return out
        }

        /**
         * Paragraf metnini satırlara böler; `[başlangıç, bitiş)` çiftleri döner.
         * Sondaki satır sonu paragrafın kendi sonlandırıcısıdır, ARDINDAN boş satır
         * üretmez — eski sürümdeki bu bir fazlalık boş kart sayısını ikiye
         * katlıyordu. Ortadaki boş satırlar (`"a\n\nb"`) korunur.
         */
        internal fun splitLines(text: String): List<Pair<Int, Int>> =
            com.agent.bridge.udf.splitTextIntoLines(text)

        private fun spansForRange(elements: List<UdfElement>, start: Int, end: Int): List<DocxSpan> {
            val out = mutableListOf<DocxSpan>()
            var cursor = 0
            for (element in elements) {
                val runStart = cursor
                val runEnd = cursor + element.textRun.length
                cursor = runEnd
                if (runEnd <= start) continue
                if (runStart >= end) break
                val from = maxOf(runStart, start) - start
                val to = minOf(runEnd, end) - start
                if (to > from) out.add(DocxSpan(from, to, element.charStyle()))
            }
            return out
        }

        private fun UdfElement.charStyle() = DocxCharStyle(
            bold = bold,
            italic = italic,
            underline = underline,
            font = family,
            // UDF punto taşır, editör yarım-punto okur.
            sizeHalfPoints = size * 2,
        )

        private fun alignmentName(alignment: Int): String = when (alignment) {
            1 -> "center"
            2 -> "right"
            3 -> "justify"
            else -> "left"
        }

        /** Tablo gösterimi düz metin: satır başına `hücre | hücre`. */
        private fun tableText(table: UdfTableBlock): String =
            table.rows.joinToString("\n") { row ->
                row.cells.joinToString(" | ") { cell -> cellText(cell) }
            }

        private fun cellText(cell: UdfCell): String =
            cell.blocks.joinToString(" ") { blockText(it) }.trim()

        private fun blockText(block: UdfBlock): String = when (block) {
            is UdfParagraphBlock -> block.paragraph.elements
                .joinToString("") { it.textRun }
                .replace('\n', ' ')
                .trim()
            is UdfTableBlock -> tableText(block)
        }
    }
}
