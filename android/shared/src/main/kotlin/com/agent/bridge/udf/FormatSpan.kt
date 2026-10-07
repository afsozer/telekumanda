package com.agent.bridge.udf

/**
 * Editor'ün bellek içi karakter biçimlendirme modeli. Bir [UdfElement]'in
 * metin yükü hariç görüntü projeksiyonudur: [start, end) ofset aralığı ve
 * beş biçimlendirme özniteliği. Android/Compose bağımlılığı yoktur; saf Kotlin.
 */
data class FormatSpan(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val subscript: Boolean = false,
    val superscript: Boolean = false,
    val size: Int = 12,
    val family: String = "Times New Roman"
)

/**
 * Paragraf hizalaması için [start, end) ofset aralığı. alignment:
 * 0: Left, 1: Center, 2: Right, 3: Justified.
 */
data class AlignmentSpan(
    val start: Int,
    val end: Int,
    val alignment: Int
)

/**
 * Yüklü bir UDF belgesinin editörün iç modeline normalize edilmesi sonucu.
 */
data class RebuiltDocument(
    val text: String,
    val spans: List<FormatSpan>,
    val alignments: List<AlignmentSpan>
)

enum class TextTransformMode { UPPER, LOWER, TITLE }

/**
 * Yüklü bir UDF belgesinin görüntü metnini, her `<paragraph>` kendi '\n' ile
 * ayrılmış bir satır olacak şekilde yeniden kurar.
 *
 * UYAP ham CDATA içinde paragraf sonlarını tutarsız saklar: bazen gerçek '\n',
 * bazen sadece sonda bir `<space>` (parser bunu düşer). Bu yüzden ham metin birçok
 * mantıksal paragrafı tek görsel satırda birleştirir; bu da satır sarmayı ve
 * paragraf hizalamasını bozar. İç modelimiz — ve kaydetme yolu (`split("\n")`) —
 * katı biçimde '\n' ile sınırlandırılmıştır (bir satır == bir paragraf), bu yüzden
 * yüklemede normalize eder ve span/hizalamaları yeni ofsetlere yeniden haritalarız.
 */
fun rebuildFromParagraphs(rawText: String, paragraphs: List<UdfParagraph>): RebuiltDocument {
    val sb = StringBuilder()
    val outSpans = mutableListOf<FormatSpan>()
    val outAligns = mutableListOf<AlignmentSpan>()

    for ((pIndex, paragraph) in paragraphs.withIndex()) {
        if (pIndex > 0) sb.append('\n')
        val paraStart = sb.length

        for (el in paragraph.elements.sortedBy { it.startOffset }) {
            if (el.length <= 0) continue
            val from = el.startOffset.coerceIn(0, rawText.length)
            val to = (el.startOffset + el.length).coerceIn(from, rawText.length)
            var slice = rawText.substring(from, to)
            // Sondaki '\n' sonlandırıcısını düşür; paragraf kırılması artık
            // paragraflar arasına eklediğimiz '\n' ile yapısal olarak temsil ediliyor.
            if (slice.endsWith("\n")) slice = slice.dropLast(1)
            if (slice.isEmpty()) continue

            val elStart = sb.length
            sb.append(slice)
            outSpans.add(
                FormatSpan(
                    start = elStart,
                    end = sb.length,
                    bold = el.bold,
                    italic = el.italic,
                    underline = el.underline,
                    strikethrough = el.strikethrough,
                    subscript = el.subscript,
                    superscript = el.superscript,
                    size = el.size,
                    family = el.family
                )
            )
        }

        val paraEnd = sb.length
        if (paragraph.alignment != 0 && paraEnd > paraStart) {
            outAligns.add(AlignmentSpan(start = paraStart, end = paraEnd, alignment = paragraph.alignment))
        }
    }

    return RebuiltDocument(sb.toString(), outSpans, outAligns)
}
