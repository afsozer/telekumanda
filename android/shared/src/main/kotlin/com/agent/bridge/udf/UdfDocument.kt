package com.agent.bridge.udf

data class UdfDocument(
    val filePath: String? = null,
    val text: String = "",
    val paragraphs: List<UdfParagraph> = emptyList(),
    val styles: List<UdfStyle> = emptyList(),
    val pageFormat: UdfPageFormat = UdfPageFormat(),
    val signatureBytes: ByteArray? = null,
    val isSigned: Boolean = false,
    val extraParts: List<UdfPart> = emptyList(),
    val extraElementXml: List<String> = emptyList(),
    /** Ordered body blocks (paragraphs + tables) used for read-only rendering. */
    val blocks: List<UdfBlock> = emptyList(),
    /**
     * Gövdenin YAZMA için sırası. [blocks] gösterim içindir ve kayıplıdır:
     * gömülü `<table>` işaretlemesi taşıyan bir paragraf orada tabloya
     * dönüşür, tablo öznitelikleri de çizim için sadeleştirilir. Yazarken
     * belgeyi bozmamak için kaynak burasıdır — paragraflar model, tablolar ve
     * tanınmayan öğeler ham XML olarak durur.
     *
     * Boşsa (elle kurulmuş belge) yazıcı [paragraphs] + [extraElementXml]
     * ikilisine düşer.
     */
    val body: List<UdfBodyNode> = emptyList()
)

/** [UdfDocument.body] öğesi: ya modellenmiş paragraf ya da dokunulmamış XML. */
sealed class UdfBodyNode
data class UdfParagraphNode(val paragraph: UdfParagraph) : UdfBodyNode()
data class UdfRawNode(val xml: String) : UdfBodyNode()

/** A top-level body element in document order: either a paragraph or a table. */
sealed class UdfBlock

/**
 * Bir gösterim satırı. Blok = SATIR'dır (bkz. [splitIntoLines]).
 *
 * @param line satırın gövdedeki adresi; düzenleme bu adresle yazar. Tablo
 *   hücrelerindeki paragraflarda null'dır — oralar henüz düzenlenmiyor.
 * @param editable satır serbestçe yeniden yazılabilir mi. Şablon alanı
 *   (`field`), sekme (`tab`) ya da koşullu bölge harcı taşıyan satırlar
 *   kilitlidir: metni değiştirmek alan bağını sessizce koparırdı.
 */
data class UdfParagraphBlock(
    val paragraph: UdfParagraph,
    val line: UdfLineRef? = null,
    val editable: Boolean = false,
) : UdfBlock()

/** Satırın gövdedeki adresi: [UdfDocument.body] içindeki düğüm ve kaçıncı satır. */
data class UdfLineRef(val bodyIndex: Int, val lineIndex: Int)
data class UdfTableBlock(
    /** Default relative column widths from the table's columnSpans. */
    val columnWeights: List<Float>,
    /** false for border="borderNone" (layout-only table). */
    val hasBorder: Boolean,
    val rows: List<UdfTableRow>
) : UdfBlock()

data class UdfTableRow(
    val cells: List<UdfCell>,
    /** Relative column widths for this row (size matches cells). */
    val columnWeights: List<Float>
)

/** A table cell's content can itself contain paragraphs AND nested tables. */
data class UdfCell(val blocks: List<UdfBlock>)

data class UdfPart(
    val name: String,
    val bytes: ByteArray
)

data class UdfParagraph(
    val alignment: Int = 0, // 0: Left, 1: Center, 2: Right, 3: Justified
    val resolver: String = "hvl-default",
    val lineSpacing: Float? = null,
    /** UDF values are points; FirstLineIndent is relative to the left indent. */
    val leftIndent: Float? = null,
    val rightIndent: Float? = null,
    val firstLineIndent: Float? = null,
    val extraAttributes: Map<String, String> = emptyMap(),
    val elements: List<UdfElement> = emptyList()
)

data class UdfElement(
    /** Ekranda görünen metin: `<field>` çalıştırmalarında ÇÖZÜLMÜŞ değerdir. */
    val textRun: String = "",
    /**
     * CDATA gövdesindeki HAM dilim. Şablon alanlarında [textRun]'dan ayrışır
     * (orada yer tutucu ad durur) ve koşullu bölge harcı boşaltıldığında da
     * ayrışır. Kaydetme gövdeyi bundan kurar; [textRun]'dan kurmak alan
     * bağlarını ve koşullu bölgeleri yok ederdi.
     */
    val rawText: String = "",
    val startOffset: Int = 0,
    val length: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val subscript: Boolean = false,
    val superscript: Boolean = false,
    val size: Int = 12,
    val family: String = "Times New Roman",
    val color: String? = null,
    val kind: UdfElementKind = UdfElementKind.CONTENT,
    val extraAttributes: Map<String, String> = emptyMap()
)

/**
 * Çalıştırmanın XML etiketi. `<field>` ve `<tab>` de taşınır: yazarken hepsini
 * `content`e çevirmek şablon alan bağını ve sekme karakterini sessizce
 * öldürüyordu (16.08.2026'da ölçüldü).
 */
enum class UdfElementKind(val tag: String) {
    CONTENT("content"),
    SPACE("space"),
    FIELD("field"),
    TAB("tab");

    companion object {
        fun fromTag(tag: String): UdfElementKind =
            entries.firstOrNull { it.tag == tag } ?: CONTENT
    }
}

data class UdfStyle(
    val name: String = "hvl-default",
    val description: String = "Gövde",
    val size: Int = 12,
    val family: String = "Times New Roman"
)

data class UdfPageFormat(
    val mediaSizeName: String = "1",
    val leftMargin: Float = UdfUnits.DEFAULT_MARGIN_PT,
    val rightMargin: Float = UdfUnits.DEFAULT_MARGIN_PT,
    val topMargin: Float = UdfUnits.DEFAULT_MARGIN_PT,
    val bottomMargin: Float = UdfUnits.DEFAULT_MARGIN_PT,
    val paperOrientation: String = "1",
    val headerFOffset: Float? = null,
    val footerFOffset: Float? = null,
    val extraAttributes: Map<String, String> = emptyMap()
)
