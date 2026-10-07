package com.agent.bridge.ui2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import com.agent.bridge.R
import com.agent.bridge.udf.PageSlice
import com.agent.bridge.udf.UdfBlock
import com.agent.bridge.udf.UdfDocument
import com.agent.bridge.udf.UdfLineRef
import com.agent.bridge.udf.UdfPageFormat
import com.agent.bridge.udf.UdfParagraph
import com.agent.bridge.udf.UdfParagraphBlock
import com.agent.bridge.udf.UdfTableBlock
import com.agent.bridge.udf.UdfUnits
import com.agent.bridge.udf.paginateLineUnits
import com.agent.bridge.udf.plainText
import com.agent.bridge.ui2.theme.Ui2
import androidx.compose.runtime.withFrameNanos

/*
 * UDF yazdırma düzeni görünümü — UDF Editor Pro'daki çizim hattının bu depoya
 * taşınmış hali (16.08.2026). İmzasız ve düz `content` satırları sayfanın
 * üstünde düzenlenir; şablon alanı/sekme taşıyan satırlar ile imzalı belgeler
 * belge bütünlüğü için salt okunur kalır.
 *
 * Belge gerçek A4 sayfaları olarak çizilir. Sayfa bölmesi, çizilen yerleşimin
 * KENDİSİNDEN ölçülür (onTextLayout / onSizeChanged): ayrı bir ölçüm yolu yok,
 * bu yüzden bir sayfa sonu asla satırın ortasından geçemez.
 *
 * Fontlar: Liberation ailesi (SIL OFL, repoda). Liberation Serif Times New
 * Roman ile, Sans Arial ile, Mono Courier ile metrik uyumludur — satır sonları
 * ve sayfa sayısı UYAP'ınkiyle örtüşsün diye telifli fontlar yerine bunlar
 * gömülüyor.
 */

private val TimesCompatibleSerif = FontFamily(
    Font(R.font.liberation_serif_regular, FontWeight.Normal),
    Font(R.font.liberation_serif_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.liberation_serif_bold, FontWeight.Bold),
    Font(R.font.liberation_serif_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

private val ArialCompatibleSans = FontFamily(
    Font(R.font.liberation_sans_regular, FontWeight.Normal),
    Font(R.font.liberation_sans_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.liberation_sans_bold, FontWeight.Bold),
    Font(R.font.liberation_sans_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

private val CourierCompatibleMono = FontFamily(
    Font(R.font.liberation_mono_regular, FontWeight.Normal),
    Font(R.font.liberation_mono_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.liberation_mono_bold, FontWeight.Bold),
    Font(R.font.liberation_mono_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

/**
 * UDF'te yazan font adını çözer. Bilinmeyen aile sistem yedeğine bırakılmaz,
 * bilerek Times'a düşürülür: belge her cihazda aynı kırılsın.
 */
internal fun udfFontFamily(name: String): FontFamily = when (name.trim().lowercase()) {
    "times", "times new roman" -> TimesCompatibleSerif
    "arial", "helvetica" -> ArialCompatibleSans
    "courier", "courier new" -> CourierCompatibleMono
    else -> TimesCompatibleSerif
}

/** A4 dikey, punto cinsinden (1pt = 1/72 inç; A4 = 210×297 mm). */
private const val A4_WIDTH_PT = 595.275f
private const val A4_HEIGHT_PT = 841.89f

/**
 * Zoom öncesi odak altındaki içerik koordinatını zoom sonrasında aynı ekran
 * noktasında tutacak kaydırma hedefi. Sayfanın sütun içindeki başlangıcı da
 * zoom ile değiştiğinden [oldOrigin]/[newOrigin] hesaba katılır.
 */
internal fun focalScrollTarget(
    scroll: Float,
    focal: Float,
    oldOrigin: Float,
    newOrigin: Float,
    zoomRatio: Float,
): Float = newOrigin + (scroll + focal - oldOrigin) * zoomRatio - focal

/** Üst biçim çubuğuna taşınan etkin satır/karakter biçimi. */
data class UdfEditContext(
    val line: UdfLineRef,
    val selection: TextRange,
    val size: Int,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val alignment: Int,
    val lineSpacing: Float?,
)

/**
 * Belgeyi sayfalanmış A4 yaprakları olarak çizer.
 *
 * Yerleşim CİHAZDAN BAĞIMSIZDIR: sayfa gerçek A4 punto boyutunda kurulur
 * (1 pt = 1 dp), böylece küçük piksel boyutlarındaki yuvarlama satır sonlarını
 * ekran genişliğine göre oynatamaz. Ekrana sığdırma yalnız GÖRSEL büyütmeyle
 * olur.
 *
 * @param zoom kullanıcının büyütmesi; 1f "ekrana sığdır" demektir. Gerçek
 *   büyütme `sığdırma oranı × zoom`'dur — yani satır sonları ve sayfa sayısı
 *   bundan etkilenmez.
 * @param editable satırlara dokunup yeniden yazılabilir mi (imzasız UDF).
 * @param onEditLine satır kapanırken çağrılır; yalnız metin gerçekten
 *   değiştiyse.
 */
@Composable
fun UdfDocumentView(
    document: UdfDocument,
    modifier: Modifier = Modifier,
    zoom: Float = 1f,
    onZoomChange: (Float) -> Unit = {},
    showPageNumbers: Boolean = true,
    editable: Boolean = false,
    onEditLine: (UdfLineRef, String) -> Unit = { _, _ -> },
    onEditContext: (UdfEditContext) -> Unit = {},
) {
    val baseStyle = remember {
        TextStyle(
            fontFamily = TimesCompatibleSerif,
            fontSize = 12f.sp,
            color = Color(0xFF111111),
        )
    }

    // Düzenleme oturumu: hangi satır açık, taslak metin ne. Taslak BURADA durur,
    // belgede değil — her tuşa basışta belgeyi yeniden kurmak bütün sayfalamayı
    // tetiklerdi. Belgeye yalnız satır kapanırken yazılır.
    var editing by remember { mutableStateOf<UdfLineRef?>(null) }
    var draft by remember { mutableStateOf(TextFieldValue()) }
    var original by remember { mutableStateOf("") }
    var editingParagraph by remember { mutableStateOf<UdfParagraph?>(null) }
    val currentOnEditContext by rememberUpdatedState(onEditContext)

    fun publishContext(line: UdfLineRef, paragraph: UdfParagraph, value: TextFieldValue) {
        val element = paragraph.elements.firstOrNull { it.textRun.isNotEmpty() }
            ?: paragraph.elements.firstOrNull()
        currentOnEditContext(
            UdfEditContext(
                line = line,
                selection = value.selection,
                size = element?.size ?: 12,
                bold = element?.bold == true,
                italic = element?.italic == true,
                underline = element?.underline == true,
                alignment = paragraph.alignment,
                lineSpacing = paragraph.lineSpacing,
            )
        )
    }

    /**
     * @param only verilirse yalnız O satır açıksa kapatır. Kapanan alanın odak
     *   kaybı bildirimi bir sonraki satır açıldıktan SONRA geliyor; süzmezsek o
     *   gecikmiş bildirim yeni açılan satırı hemen kapatıyor.
     */
    fun close(only: UdfLineRef? = null) {
        val open = editing ?: return
        if (only != null && only != open) return
        editing = null
        if (draft.text != original) onEditLine(open, draft.text)
    }

    fun open(line: UdfLineRef, paragraph: UdfParagraph) {
        if (editing == line) return
        close()
        val text = paragraph.plainText()
        editing = line
        editingParagraph = paragraph
        original = text
        draft = TextFieldValue(text, TextRange(text.length))
        publishContext(line, paragraph, draft)
    }

    // Belge değişince (kaydedilen satır) oturum kapanır: satır adresleri yeniden
    // üretildi, açık kalan taslak yanlış satıra yazabilirdi.
    LaunchedEffect(document) { editing = null }

    val controller = remember(editable, editing, draft, editingParagraph) {
        LineEditController(
            enabled = editable,
            editing = editing,
            draft = draft,
            onDraft = { value ->
                draft = value
                val line = editing
                val paragraph = editingParagraph
                if (line != null && paragraph != null) publishContext(line, paragraph, value)
            },
            onOpen = ::open,
            onClose = ::close,
        )
    }

    // Viewport ölçüsü kaydırma katmanının DIŞINDA alınmalı: kaydırılabilir bir
    // düğümün içinde maxWidth sonsuz gelir ve içerik genişliği anlamını yitirir
    // (bu hata canlıda sayfayı kilitledi, 16.08.2026).
    BoxWithConstraints(modifier) {
        val viewportWidth = maxWidth
        val fitZoom = ((viewportWidth.value - 16f) / A4_WIDTH_PT).coerceIn(0.2f, 1f)
        PaginatedDocumentPreview(
            blocks = document.blocks,
            pageFormat = document.pageFormat,
            scale = 1f,
            zoom = fitZoom * zoom,
            sheetWidth = A4_WIDTH_PT.dp,
            viewportWidth = viewportWidth,
            viewportHeight = maxHeight,
            baseStyle = baseStyle,
            showPageNumbers = showPageNumbers,
            onZoomChange = onZoomChange,
            editor = controller,
        )

        // Bitir düğmesi kaydırma katmanının dışında, ekrana sabit: satır
        // düzenlenirken klavye açık olur ve kullanıcının sayfayı kaydırmadan
        // kapatabilmesi gerekir.
        if (editing != null) {
            Surface(
                color = Ui2.colors.accent,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            ) {
                Text(
                    text = "Bitir",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .pointerInput(Unit) { detectTapGestures { close(null) } }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Satır düzenleme durumunun çizim hattına taşınan hali. Ölçüm geçişine `null`
 * verilir: orada düzenleme alanı çizilmemeli, yoksa aynı alan iki kez var olur
 * ve odak bir ileri bir geri zıplar.
 */
private class LineEditController(
    val enabled: Boolean,
    val editing: UdfLineRef?,
    val draft: TextFieldValue,
    val onDraft: (TextFieldValue) -> Unit,
    val onOpen: (UdfLineRef, UdfParagraph) -> Unit,
    /** null: açık olan satırı kapat. Dolu: yalnız o satır hâlâ açıksa kapat. */
    val onClose: (UdfLineRef?) -> Unit,
)

/**
 * Bir gövde bloğunu çizer: paragraf metin olarak, tablo gerçek ızgara olarak
 * (hücreler kendi içinde paragraf ve iç içe tablo taşıyabilir).
 */
@Composable
private fun RenderDocBlock(
    block: UdfBlock,
    scale: Float,
    baseStyle: TextStyle,
    cellPad: Dp,
    borderColor: Color,
    recorder: PrintGeometryRecorder? = null,
    blockIndex: Int = -1,
    editor: LineEditController? = null,
) {
    when (block) {
        is UdfParagraphBlock -> {
            val paragraph = block.paragraph
            val line = block.line
            if (editor != null && line != null && editor.editing == line) {
                LineEditField(line, paragraph, baseStyle, scale, editor)
                return
            }
            val annotated = blockAnnotatedString(listOf(paragraph), scale)
            // Boş satırın yüksekliği: metin yokken paragraf biçimi
            // AnnotatedString'e iliştirilemez (boş aralığa stil uygulanmaz), bu
            // yüzden satır yüksekliği doğrudan taban biçime katılır. Katılmazsa
            // boş satırlar font varsayılanı kadar yer kaplar ve belge yanlış
            // yerden sayfalanır — bloklar satıra bölündüğünden beri gerçek
            // dilekçelerde bu boş satırlar ayrı birim.
            val style = if (annotated.text.isEmpty()) baseStyle.merge(paragraphStyle(paragraph, scale)) else baseStyle
            val tabStops = parseTabStops(paragraph.extraAttributes["TabSet"], scale)
            // Kilitli satırlar (şablon alanı, sekme, koşullu bölge harcı) dokunmayı
            // yutmaz: gerekçesi UdfParagraphBlock.editable'da.
            val tapModifier = if (editor != null && editor.enabled && block.editable && line != null) {
                // clickable, detectTapGestures'a TERCİH EDİLİR: sayfa yığını
                // pinch + iki eksenli kaydırma katmanlarının içinde duruyor ve ham
                // jest yakalayıcıya dokunma hiç ulaşmıyordu (tablette ölçüldü).
                Modifier.clickable(
                    interactionSource = remember(line) { MutableInteractionSource() },
                    indication = null,
                ) {
                    editor.onOpen(line, paragraph)
                }
            } else {
                Modifier
            }
            val paragraphModifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ((paragraph.leftIndent ?: 0f) * scale).dp,
                    end = ((paragraph.rightIndent ?: 0f) * scale).dp,
                )
                .then(tapModifier)
            if (annotated.text.contains('\t')) {
                // TabbedText tek parça görsel birimdir; çok satırlı gerçek
                // yüksekliği TEK sayfalama birimi olarak kaydedilir.
                if (recorder != null) {
                    Box(Modifier.onSizeChanged { recorder.recordSingleUnit(blockIndex, it.height) }) {
                        TabbedText(annotated, style, tabStops, UdfUnits.DEFAULT_TAB_INTERVAL_PT * scale, paragraphModifier)
                    }
                } else {
                    TabbedText(annotated, style, tabStops, UdfUnits.DEFAULT_TAB_INTERVAL_PT * scale, paragraphModifier)
                }
            } else {
                Text(
                    text = annotated,
                    style = style,
                    modifier = paragraphModifier,
                    onTextLayout = { recorder?.recordParagraph(blockIndex, it) },
                )
            }
        }

        is UdfTableBlock -> Column(modifier = Modifier.fillMaxWidth()) {
            for ((rowIndex, row) in block.rows.withIndex()) {
                val total = row.columnWeights.sum().takeIf { it > 0f }
                    ?: row.cells.size.toFloat().coerceAtLeast(1f)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .then(
                            if (recorder != null) {
                                Modifier.onSizeChanged { recorder.recordTableRow(blockIndex, rowIndex, it.height) }
                            } else {
                                Modifier
                            }
                        ),
                ) {
                    row.cells.fastForEachIndexed { index, cell ->
                        val weight = (row.columnWeights.getOrElse(index) { 1f }) / total
                        Box(
                            modifier = Modifier
                                .weight(weight)
                                .fillMaxHeight()
                                .then(if (block.hasBorder) Modifier.border(0.8.dp, borderColor) else Modifier)
                                .padding(cellPad),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                for (cellBlock in cell.blocks) {
                                    RenderDocBlock(cellBlock, scale, baseStyle, cellPad, borderColor)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Satırın TAM yerinde açılan düzenleme alanı: aynı font, aynı punto, aynı
 * hizalama, aynı girinti. Kullanıcı sayfadan kopmuyor — düzenleme yazdırma
 * düzeninin üstünde oluyor (kullanıcı kararı 16.08.2026).
 *
 * Satır içi karışık biçim burada TEK biçime düşer (satırın ilk çalıştırması):
 * yazma tarafı da öyle davranıyor, ikisi tutarlı. Bunun korunması gereken
 * satırlar zaten kilitli.
 */
@Composable
private fun LineEditField(
    line: UdfLineRef,
    paragraph: UdfParagraph,
    baseStyle: TextStyle,
    scale: Float,
    editor: LineEditController,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // onFocusChanged, odak İSTENMEDEN önce bir kez "odaksız" diye tetikleniyor;
    // süzmezsek satır doğduğu karede kapanıyor (tablette ölçüldü: dokunma
    // geliyordu, alan bir kare bile yaşamıyordu).
    var hadFocus by remember { mutableStateOf(false) }

    BasicTextField(
        value = editor.draft,
        onValueChange = editor.onDraft,
        textStyle = lineTextStyle(paragraph, baseStyle, scale),
        cursorBrush = SolidColor(Ui2.colors.accent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ((paragraph.leftIndent ?: 0f) * scale).dp,
                end = ((paragraph.rightIndent ?: 0f) * scale).dp,
            )
            // İnce vurgu: hangi satırın açık olduğu, sayfanın düzenini bozmadan
            // görünsün.
            .background(Ui2.colors.accent.copy(alpha = 0.10f))
            .focusRequester(focus)
            .onFocusChanged { state ->
                if (state.isFocused) hadFocus = true else if (hadFocus) editor.onClose(line)
            },
    )
}

/** Düzenleme alanının biçimi: paragrafın hizalaması + satırın ilk çalıştırması. */
private fun lineTextStyle(paragraph: UdfParagraph, baseStyle: TextStyle, scale: Float): TextStyle {
    val element = paragraph.elements.firstOrNull { it.textRun.isNotEmpty() } ?: paragraph.elements.firstOrNull()
    val merged = baseStyle.merge(paragraphStyle(paragraph, scale))
    if (element == null) return merged
    return merged.merge(
        SpanStyle(
            fontSize = (element.size.toFloat() * scale).sp,
            fontFamily = udfFontFamily(element.family),
            fontWeight = if (element.bold) FontWeight.Bold else null,
            fontStyle = if (element.italic) FontStyle.Italic else null,
            textDecoration = when {
                element.underline && element.strikethrough ->
                    TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                element.underline -> TextDecoration.Underline
                element.strikethrough -> TextDecoration.LineThrough
                else -> null
            },
        )
    )
}

/**
 * Paragraf listesini (gövde paragrafı ya da tablo hücresi) biçimli metne çevirir.
 * Her çalıştırmanın çözülmüş [com.agent.bridge.udf.UdfElement.textRun] değerini
 * kullanır, yani genel CDATA ofsetlerine bağlı değildir.
 */
private fun blockAnnotatedString(paragraphs: List<UdfParagraph>, scale: Float): AnnotatedString =
    buildAnnotatedString {
        for ((paragraphIndex, paragraph) in paragraphs.withIndex()) {
            if (paragraphIndex > 0) append('\n')
            val paragraphStart = length
            for (element in paragraph.elements.sortedBy { it.startOffset }) {
                if (element.length <= 0) continue
                val slice = element.textRun.removeSuffix("\n")
                if (slice.isEmpty()) continue
                val start = length
                append(slice)
                if (element.bold) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                if (element.italic) addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                val decoration = when {
                    element.underline && element.strikethrough ->
                        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    element.underline -> TextDecoration.Underline
                    element.strikethrough -> TextDecoration.LineThrough
                    else -> null
                }
                if (decoration != null) addStyle(SpanStyle(textDecoration = decoration), start, length)
                if (element.subscript) addStyle(SpanStyle(baselineShift = BaselineShift.Subscript), start, length)
                if (element.superscript) addStyle(SpanStyle(baselineShift = BaselineShift.Superscript), start, length)
                addStyle(SpanStyle(fontSize = (element.size.toFloat() * scale).sp), start, length)
                addStyle(SpanStyle(fontFamily = udfFontFamily(element.family)), start, length)
            }
            val paragraphEnd = length
            if (paragraphEnd > paragraphStart) {
                // Sol girinti burada DEĞİL, blok modifier'ında uygulanır; iki
                // kez uygulanırsa paragraf iki kat içeri kayar.
                addStyle(paragraphStyle(paragraph, scale), paragraphStart, paragraphEnd)
            }
        }
    }

private fun paragraphStyle(paragraph: UdfParagraph, scale: Float): ParagraphStyle {
    val textAlign = when (paragraph.alignment) {
        1 -> TextAlign.Center
        2 -> TextAlign.Right
        3 -> TextAlign.Justify
        else -> TextAlign.Left
    }
    val firstLine = paragraph.firstLineIndent ?: 0f
    val textIndent = if (firstLine != 0f) TextIndent(firstLine = (firstLine * scale).sp) else null
    // Resmî dönüştürücü LineSpacing yokken/sıfırken de tam olarak bu yüksekliği
    // yazıyor: (LineSpacing + 1) × 0.2 inç.
    return ParagraphStyle(
        textAlign = textAlign,
        textIndent = textIndent,
        lineHeight = (UdfUnits.lineHeightPoints(paragraph.lineSpacing) * scale).sp,
    )
}

/**
 * Paragrafın `TabSet` özniteliğini ("177.0:0:0 354.0:0:0" ya da
 * "151.0:0:0,300.0:0:0") sekme durağı x konumlarına çevirir. Her belirteç
 * `konum:hizalama:kılavuz`; yalnız konum kullanılır.
 *
 * Ayraç hem boşluk hem VİRGÜL: UYAP virgüllü liste de yazıyor (ölçüldü, ceza
 * mahkemesi ek kararı 20.08.2026) ve yalnız boşlukla bölünce liste tek belirteç
 * sayılıp ilk duraktan sonrası sessizce düşüyordu.
 *
 * Konumu 0 olan duraklar ELENİR — kullanılabilir durak değiller: sekme yalnız
 * imlecin SAĞINDAKİ durağa ilerleyebilir, imleç ise zaten 0'ın sağında. Aynı
 * belgede UYAP bunlardan 511 tane yazmıştı, hepsi 0.0.
 */
private fun parseTabStops(tabSet: String?, scale: Float): List<Float> {
    if (tabSet.isNullOrBlank()) return emptyList()
    return tabSet.trim().split(Regex("[\\s,]+"))
        .mapNotNull { it.substringBefore(':').toFloatOrNull() }
        .filter { it > 0f }
        .map { it * scale }
        .sorted()
}

/** Sekmeli bir satırdaki tek parçanın yeri: x ve kaçıncı görsel satır. */
internal data class TabbedSpot(val x: Int, val row: Int)

/**
 * Sekmeli bir satırın parça yerlerini hesaplar. Saf işlev, ölçüm yapmaz —
 * testi [com.agent.bridge.ui2.components.UdfTabLayoutTest].
 *
 * KURAL: SEKME SATIR KIRMAZ. Sağ kenarı geçen sekmenin ilerlemesi kenara
 * sabitlenir (sonrakiler hiç yer kaplamaz) ve satırın son parçası sığmıyorsa
 * alt satıra atılmaz, sağ kenara YASLANIR.
 *
 * Gerekçe: UYAP kâtipleri bir başlığı sağa itmek için Tab'a onlarca kez basıyor
 * ve o paragrafın durak listesi kullanılamaz oluyor (bkz. [parseTabStops]). Her
 * sekmeyi varsayılan aralık kadar ilerletip sığmayınca alt satıra geçen eski
 * kural, 53 sekmeyi yarım sayfa boşluğa çeviriyordu; masaüstü UYAP Editörü ve
 * resmî Android görüntüleyici aynı belgeyi tek satır çiziyor (kullanıcı
 * bildirdi 20.08.2026).
 *
 * Yaslama YALNIZ satıra sığan son parçaya uygulanır. Sığmayana uygulanırsa
 * ("KATILAN	: ONURCAN SAĞBAN, …" gibi uzun bir alan) parça sola, etiketin
 * ÜSTÜNE biniyor; canlıda öyle oldu (20.08.2026). Sığmayan parça sekmenin
 * bıraktığı yerde kalır ve kendi sütununda sarar — sarabilmesi için
 * [minLastSegmentPx] kadar yer garanti edilir, yoksa yazı karakter karakter
 * alt alta dizilirdi (eski canlı hata: yetki belgesindeki TC numarası).
 *
 * Satır YALNIZ gerçek metin sayfa dışında kalacaksa kırılır: kenara dayanmış
 * bir sekmeden sonra hâlâ boş olmayan bir ARA parça varsa.
 */
internal fun tabbedLineOffsets(
    segmentWidths: List<Int>,
    tabStopsPx: List<Float>,
    defaultTabPx: Float,
    maxWidth: Int?,
    minLastSegmentPx: Int = 0,
): List<TabbedSpot> {
    val spots = ArrayList<TabbedSpot>(segmentWidths.size)
    var x = 0
    var row = 0
    for (index in segmentWidths.indices) {
        val isLast = index == segmentWidths.lastIndex
        val width = segmentWidths[index]
        if (maxWidth != null) {
            if (isLast) {
                if (width <= maxWidth) {
                    // Sığıyor: sekme kenarı geçirdiyse sağ kenara yasla.
                    if (x + width > maxWidth) x = maxWidth - width
                } else {
                    // Sığmıyor: yerinde sarsın, ama saracak yer bırakılsın.
                    x = x.coerceAtMost((maxWidth - minLastSegmentPx).coerceAtLeast(0))
                }
            } else if (x >= maxWidth && width > 0) {
                row++
                x = 0
            }
        }
        spots.add(TabbedSpot(x, row))
        x += width
        if (!isLast) {
            val next = tabStopsPx.firstOrNull { it > x + 0.5f }
                ?: if (defaultTabPx > 0f) ((kotlin.math.floor(x / defaultTabPx) + 1) * defaultTabPx) else x.toFloat()
            // Kenarı geçen sekme kenara sabitlenir; geriye ASLA çekilmez
            // (çekilse üst üste binen metin çıkardı).
            x = next.toInt().let { if (maxWidth != null) it.coerceAtMost(maxWidth) else it }.coerceAtLeast(x)
        }
    }
    return spots
}

/**
 * Sekme içeren paragrafı çizer: her parça bir sonraki sekme durağına ilerler,
 * böylece "DAVACI\t: A.Y." satırında iki nokta masaüstü UYAP Editörü'ndeki
 * sütuna oturur, metnin hemen ardına yapışmaz.
 */
@Composable
private fun TabbedText(
    annotated: AnnotatedString,
    style: TextStyle,
    tabStopsPt: List<Float>,
    defaultTabIntervalPt: Float,
    modifier: Modifier = Modifier,
) {
    val text = annotated.text
    val lines = remember(annotated) {
        val result = mutableListOf<List<AnnotatedString>>()
        var lineStart = 0
        while (true) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val parts = mutableListOf<AnnotatedString>()
            var partStart = lineStart
            while (true) {
                val tab = text.indexOf('\t', partStart)
                if (tab < 0 || tab > lineEnd) {
                    parts.add(annotated.subSequence(partStart, lineEnd))
                    break
                }
                parts.add(annotated.subSequence(partStart, tab))
                partStart = tab + 1
            }
            result.add(parts)
            if (lineEnd == text.length) break
            lineStart = lineEnd + 1
        }
        result
    }

    Layout(
        modifier = modifier,
        content = {
            lines.forEach { line ->
                line.forEachIndexed { index, segment ->
                    val canWrap = index == line.lastIndex
                    Text(
                        text = segment,
                        style = style,
                        softWrap = canWrap,
                        maxLines = if (canWrap) Int.MAX_VALUE else 1,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val stopsPx = tabStopsPt.map { it.dp.toPx() }
        val defaultTabPx = defaultTabIntervalPt.dp.toPx()
        val maxWidth = constraints.maxWidth.takeIf { it != Constraints.Infinity }
        val placements = mutableListOf<Triple<Placeable, Int, Int>>()
        var measurableIndex = 0
        var y = 0
        var widest = 0
        for (line in lines) {
            val first = measurableIndex
            // Genişlikler ÖLÇÜLMEDEN, doğal genişlikten alınır: son parçanın
            // x'ini bulmak için genişliğini önceden bilmek gerekiyor, oysa bir
            // Measurable yalnız BİR kez ölçülebilir.
            val widths = line.indices.map { measurables[first + it].maxIntrinsicWidth(Constraints.Infinity) }
            // 96.dp: sığmayan son parçaya bırakılan en az genişlik. Sıfıra yakın
            // genişlikte sarmak metni karakter karakter alt alta diziyor.
            val spots = tabbedLineOffsets(widths, stopsPx, defaultTabPx, maxWidth, 96.dp.roundToPx())
            var rowTop = y
            var row = 0
            var rowHeight = 0
            for (index in line.indices) {
                val spot = spots[index]
                if (spot.row != row) {
                    rowTop += rowHeight
                    rowHeight = 0
                    row = spot.row
                }
                val isLast = index == line.lastIndex
                val childConstraints = if (isLast && maxWidth != null) {
                    Constraints(maxWidth = (maxWidth - spot.x).coerceAtLeast(0))
                } else {
                    Constraints()
                }
                val placeable = measurables[measurableIndex++].measure(childConstraints)
                placements.add(Triple(placeable, spot.x, rowTop))
                rowHeight = maxOf(rowHeight, placeable.height)
                widest = maxOf(widest, spot.x + placeable.width)
            }
            y = rowTop + rowHeight
        }
        val width = if (constraints.maxWidth != Constraints.Infinity) constraints.maxWidth else widest
        layout(width, y) {
            placements.forEach { (placeable, x, top) -> placeable.place(x, top) }
        }
    }
}

/**
 * Gerçekten çizilen içerik sütunundan toplanan geometri. Her üst düzey blok
 * kendi yüksekliğini ve bölünemez birim sınırlarını (metin satırları
 * [TextLayoutResult]'tan, tablo satırları yerleşim boyutundan) bildirir. Ayrı
 * bir ölçüm yolu YOKTUR: çizilen yerleşim ölçümün kendisidir.
 */
private class PrintGeometryRecorder {
    /** blockIndex -> (blok yüksekliği px, satır sınırları (üst, alt) px). */
    val paragraphGeometry = mutableStateMapOf<Int, Pair<Float, List<Pair<Float, Float>>>>()

    /** (blockIndex, rowIndex) -> yerleşmiş tablo satırı yüksekliği px. */
    val rowHeights = mutableStateMapOf<Pair<Int, Int>, Float>()

    fun recordParagraph(blockIndex: Int, layout: TextLayoutResult) {
        val lines = (0 until layout.lineCount).map { layout.getLineTop(it) to layout.getLineBottom(it) }
        val geometry = layout.size.height.toFloat() to lines
        if (paragraphGeometry[blockIndex] != geometry) paragraphGeometry[blockIndex] = geometry
    }

    /** Bölünemez tek görsel birim (ör. sekmeli satır). */
    fun recordSingleUnit(blockIndex: Int, heightPx: Int) {
        val height = heightPx.toFloat()
        val geometry = height to listOf(0f to height)
        if (paragraphGeometry[blockIndex] != geometry) paragraphGeometry[blockIndex] = geometry
    }

    fun recordTableRow(blockIndex: Int, rowIndex: Int, heightPx: Int) {
        val key = blockIndex to rowIndex
        val height = heightPx.toFloat()
        if (rowHeights[key] != height) rowHeights[key] = height
    }
}

/**
 * Belge geometrisi: sayfalama için bölünemez satır/satır-birimleri ve her üst
 * düzey bloğun mutlak dikey sınırları (belge uzayı, px).
 */
private class DocumentGeometry(
    val lineUnits: List<Pair<Float, Float>>,
    /** Blok başına (üst, alt) — bir sayfada hangi blokların çizileceğini seçer. */
    val blockBounds: List<Pair<Float, Float>>,
)

/**
 * Kaydedilen geometriyi düzleştirir. Her blok bildirene kadar null döner —
 * çağıran o ilk kare için tek sayfalık tahmine düşer.
 */
private fun collectGeometry(
    blocks: List<UdfBlock>,
    recorder: PrintGeometryRecorder,
): DocumentGeometry? {
    val units = ArrayList<Pair<Float, Float>>()
    val bounds = ArrayList<Pair<Float, Float>>(blocks.size)
    var y = 0f
    for ((index, block) in blocks.withIndex()) {
        val top = y
        when (block) {
            is UdfParagraphBlock -> {
                val (height, lines) = recorder.paragraphGeometry[index] ?: return null
                for ((lineTop, lineBottom) in lines) units.add(y + lineTop to y + lineBottom)
                y += height
            }
            is UdfTableBlock -> {
                for (row in block.rows.indices) {
                    val height = recorder.rowHeights[index to row] ?: return null
                    units.add(y to y + height)
                    y += height
                }
            }
        }
        bounds.add(top to y)
    }
    return DocumentGeometry(units, bounds)
}

@Composable
private fun PaginatedDocumentPreview(
    blocks: List<UdfBlock>,
    pageFormat: UdfPageFormat,
    scale: Float,
    zoom: Float,
    sheetWidth: Dp,
    viewportWidth: Dp,
    viewportHeight: Dp,
    baseStyle: TextStyle,
    showPageNumbers: Boolean,
    onZoomChange: (Float) -> Unit,
    editor: LineEditController? = null,
) {
    val leftPad = (pageFormat.leftMargin * scale).dp
    val rightPad = (pageFormat.rightMargin * scale).dp
    val topPad = (pageFormat.topMargin * scale).dp
    val bottomPad = (pageFormat.bottomMargin * scale).dp
    val cellPad = (2f * scale).dp

    val originalDensity = LocalDensity.current
    // Yazı tipi ölçeği SABİTLENİR: sistem yazı boyutu ayarı belgeyi yeniden
    // kırmasın, sayfa sayısı kullanıcının erişilebilirlik ayarına göre
    // değişmesin.
    val fixedDensity = remember(originalDensity.density) { Density(originalDensity.density, 1f) }

    // Kaydedici belge DEĞİŞSE de korunur: bir satır düzenlenince yeni bir
    // kaydedici kurmak geometriyi bir kare boyunca boşaltıyor, sayfa sayısı da o
    // karede 1'e düşüp geri çıkıyordu — her düzenlemeden sonra göz kırpma.
    // Bayat girdiler zararsız: her blok ilk yerleşimde kendi ölçüsünü yeniden
    // bildiriyor, blok sayısı azalırsa fazlalıklar hiç okunmuyor.
    val recorder = remember(pageFormat, baseStyle, scale, fixedDensity) { PrintGeometryRecorder() }
    val pageContentHeightPx = with(fixedDensity) {
        ((A4_HEIGHT_PT - pageFormat.topMargin - pageFormat.bottomMargin) * scale).dp.toPx()
    }
    val geometry = collectGeometry(blocks, recorder)
    val pageSlices = remember(geometry, pageContentHeightPx) {
        if (geometry == null) listOf(PageSlice(0f, pageContentHeightPx))
        else paginateLineUnits(geometry.lineUnits, pageContentHeightPx)
    }
    val pageCount = pageSlices.size

    val borderColor = Color(0xFF555555)
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    var sheetOrigin by remember { mutableStateOf(Offset.Zero) }

    // Belge ilk açıldığında yatay kaydırma 0'da kalıp sayfa sola yaslı
    // görünüyordu. Yerleşim ölçülüp aralık belli olur olmaz bir kez ortalanır.
    var didCenterHorizontally by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { horizontalScrollState.maxValue }.collect { maxValue ->
            if (!didCenterHorizontally && maxValue > 0 && maxValue != Int.MAX_VALUE) {
                horizontalScrollState.scrollTo(maxValue / 2)
                didCenterHorizontally = true
            }
        }
    }

    // Büyütme odak telafisi: yalnız kaydırmayı oranlamak yeterli değil. Sayfa
    // küçükken sütunun ortasındadır; büyürken bu başlangıç noktası da değişir.
    // Eski ve yeni gerçek sayfa başlangıcını formüle katınca parmak altındaki
    // belge noktası zoom boyunca aynı ekran koordinatında kalır.
    //
    // Odak pinch'te iki parmağın ortasıdır. A+/A− ile büyütmede parmak yoktur;
    // o durumda EKRANIN ORTASI alınır. Eskiden burada (0,0) kalıyordu ve
    // düğmeyle büyütmek belgeyi sol üst köşeye kilitliyordu (canlı hata).
    val viewportCenter = with(LocalDensity.current) {
        Offset(viewportWidth.toPx() / 2f, viewportHeight.toPx() / 2f)
    }
    // Yalnız sıradaki pinch zoom olayı için saklanır. Saf iki-parmak sürükleme
    // bu değeri kirletmez; gesture bittikten sonraki A+/A− tekrar ekran
    // merkezini kullanır.
    var pendingZoomFocal by remember { mutableStateOf(Offset.Unspecified) }
    val currentZoom = rememberUpdatedState(zoom)
    val currentOnZoomChange = rememberUpdatedState(onZoomChange)
    LaunchedEffect(Unit) {
        var previous = currentZoom.value
        var previousOrigin = sheetOrigin
        snapshotFlow { currentZoom.value to sheetOrigin }.collect { (next, observedOrigin) ->
            if (previous > 0f && next != previous) {
                didCenterHorizontally = true
                withFrameNanos { } // yeni kaydırma aralığı ölçülene kadar bekle
                val ratio = next / previous
                val focal = if (pendingZoomFocal.isSpecified) pendingZoomFocal else viewportCenter
                val nextOrigin = sheetOrigin
                horizontalScrollState.scrollTo(
                    kotlin.math.round(
                        focalScrollTarget(
                            scroll = horizontalScrollState.value.toFloat(),
                            focal = focal.x,
                            oldOrigin = previousOrigin.x,
                            newOrigin = nextOrigin.x,
                            zoomRatio = ratio,
                        )
                    ).toInt().coerceIn(0, horizontalScrollState.maxValue)
                )
                verticalScrollState.scrollTo(
                    kotlin.math.round(
                        focalScrollTarget(
                            scroll = verticalScrollState.value.toFloat(),
                            focal = focal.y,
                            oldOrigin = previousOrigin.y,
                            newOrigin = nextOrigin.y,
                            zoomRatio = ratio,
                        )
                    ).toInt().coerceIn(0, verticalScrollState.maxValue)
                )
                previousOrigin = nextOrigin
                pendingZoomFocal = Offset.Unspecified
            } else {
                // İlk yerleşim zoom'dan önce tamamlanırsa gerçek sayfa
                // başlangıcını kaydet; aksi hâlde ilk pinch Offset.Zero'dan
                // hesaplanıp sıçrayabilirdi.
                previousOrigin = observedOrigin
            }
            previous = next
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui2.colors.surface2)
            .pointerInput(Unit) {
                awaitEachGesture {
                    var previousDistance = 0f
                    var previousCentroid = Offset.Unspecified
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val distance = (pressed[0].position - pressed[1].position).getDistance()
                            val centroid = (pressed[0].position + pressed[1].position) / 2f
                            if (previousCentroid.isSpecified) {
                                val pan = centroid - previousCentroid
                                horizontalScrollState.dispatchRawDelta(-pan.x)
                                verticalScrollState.dispatchRawDelta(-pan.y)
                            }
                            if (previousDistance > 0f && distance > 0f) {
                                val change = distance / previousDistance
                                if (change.isFinite() && change != 1f) {
                                    pendingZoomFocal = centroid
                                    currentOnZoomChange.value(change)
                                }
                            }
                            previousDistance = distance
                            previousCentroid = centroid
                            pressed.forEach { it.consume() }
                        } else {
                            previousDistance = 0f
                            previousCentroid = Offset.Unspecified
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .horizontalScroll(horizontalScrollState)
            .verticalScroll(verticalScrollState),
    ) {
        val sheetHeight = sheetWidth * (A4_HEIGHT_PT / A4_WIDTH_PT) // tam A4 oranı (~1.4142)
        val contentWidth = maxOf(viewportWidth, sheetWidth * zoom + 32.dp)

        Column(
            modifier = Modifier.width(contentWidth).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = Ui2.colors.surface, shape = RoundedCornerShape(50)) {
                Text(
                    text = "Toplam $pageCount sayfa",
                    color = Ui2.colors.ink2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }

            // Sayfa yığını tam A4 boyutunda yerleşir ve yalnız GÖRSEL olarak
            // ölçeklenir: satır sonları ve sayfalama zoom'a bağlı değildir.
            Box(
                modifier = Modifier
                    .onGloballyPositioned { sheetOrigin = it.positionInParent() }
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints())
                        layout((placeable.width * zoom).toInt(), (placeable.height * zoom).toInt()) {
                            placeable.place(0, 0)
                        }
                    }
                    .graphicsLayer(scaleX = zoom, scaleY = zoom, transformOrigin = TransformOrigin(0f, 0f)),
            ) {
              CompositionLocalProvider(LocalDensity provides fixedDensity) {
                // ÖLÇÜM GEÇİŞİ — çizilmez, yalnız yerleşir. Belgenin tamamı sayfa
                // içerik genişliğinde BİR KEZ dizilir ve geometrisini bildirir;
                // sayfalar bu ölçüme bakarak yalnız kendi bloklarını çizer. Ölçüm
                // ile çizim aynı yerleşim kurallarıyla yapıldığı için sayfa sonu
                // hâlâ satırın ortasından geçemez.
                //
                // Sayfa yığınının ÜSTÜNE bindirilir (Box), yani yerleşimde yer
                // kaplamaz; Column'un içine konsaydı sıfır yüksekliğine rağmen
                // aradaki 24dp boşluğu tetiklerdi.
                Box(
                    modifier = Modifier
                        .width(sheetWidth)
                        .height(0.dp)
                        .clipToBounds(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = leftPad, end = rightPad)
                            .wrapContentHeight(align = Alignment.Top, unbounded = true),
                    ) {
                        for ((blockIndex, block) in blocks.withIndex()) {
                            RenderDocBlock(
                                block = block,
                                scale = scale,
                                baseStyle = baseStyle,
                                cellPad = cellPad,
                                borderColor = borderColor,
                                recorder = recorder,
                                blockIndex = blockIndex,
                            )
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    for ((pageIndex, slice) in pageSlices.withIndex()) {
                        val sliceHeightDp = with(fixedDensity) { (slice.endY - slice.startY).toDp() }
                        val sliceOffsetDp = with(fixedDensity) { slice.startY.toDp() }
                        Box(
                            modifier = Modifier
                                .width(sheetWidth)
                                .height(sheetHeight)
                                .shadow(8.dp, RoundedCornerShape(2.dp))
                                .border(1.dp, Color(0x22000000), RoundedCornerShape(2.dp))
                                .background(Color.White),
                        ) {
                            // Sayfa kenar boşluklarının içindeki kırpılmış pencere.
                            // İçerik sütunu sayfanın başlangıcı kadar yukarı
                            // kaydırılır; kesim her zaman satır sınırındadır.
                            //
                            // Bu sayfaya YALNIZ kendi blokları çizilir. Eskiden her
                            // yaprak belgenin tamamını çizip kırpıyordu: 20 sayfalık
                            // bir dilekçe 20 kez diziliyordu ve düzenleme sırasında
                            // her tuşa basış bunu tekrarlayacaktı. Çizilmeyen önceki
                            // blokların yüksekliği tek bir boşlukla temsil edilir, o
                            // yüzden konumlar birebir aynı kalır.
                            val visibleRange = geometry?.let { g ->
                                val first = blocks.indices.firstOrNull { g.blockBounds[it].second > slice.startY }
                                val last = blocks.indices.lastOrNull { g.blockBounds[it].first < slice.endY }
                                if (first == null || last == null || first > last) IntRange.EMPTY else first..last
                            }
                            val leadingGapDp = with(fixedDensity) {
                                (visibleRange?.firstOrNull()?.let { geometry?.blockBounds?.get(it)?.first } ?: 0f).toDp()
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = leftPad, end = rightPad, top = topPad)
                                    .height(sliceHeightDp)
                                    .clipToBounds(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                        .offset(y = leadingGapDp - sliceOffsetDp),
                                ) {
                                    val indices = visibleRange ?: blocks.indices
                                    for (blockIndex in indices) {
                                        // Sarmalı uzun bir satır iki sayfanın
                                        // aralığına birden girebilir; düzenleme
                                        // alanı yalnız BAŞLADIĞI sayfada açılır,
                                        // yoksa aynı satır için iki alan doğar ve
                                        // odak ikisi arasında zıplar.
                                        val startsHere = geometry?.blockBounds
                                            ?.getOrNull(blockIndex)?.first
                                            ?.let { it >= slice.startY - 0.5f } ?: true
                                        RenderDocBlock(
                                            block = blocks[blockIndex],
                                            scale = scale,
                                            baseStyle = baseStyle,
                                            cellPad = cellPad,
                                            borderColor = borderColor,
                                            blockIndex = blockIndex,
                                            editor = editor.takeIf { startsHere },
                                        )
                                    }
                                }
                            }

                            if (showPageNumbers) {
                                Text(
                                    text = "${pageIndex + 1}/$pageCount",
                                    style = baseStyle,
                                    color = Color(0xFF333333),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = bottomPad / 2),
                                )
                            }
                        }
                    }
                }
              }
            }
        }
    }
}
