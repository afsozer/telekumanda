package com.agent.bridge.ui2.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.DocxBlock
import com.agent.bridge.DocxEditorState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.udf.UdfUnits
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens
import kotlin.math.roundToInt

private val DocxFonts = listOf("Times New Roman", "Arial", "Calibri", "Aptos")

// Önizleme ölçeği. DOCX'ler genelde punto taşımaz (docDefaults'ta kalır) ve
// hepsi 14sp çizilirdi; 366dp genişliğinde bir telefonda bu, satır başına ~30
// karakter demek — belge okunmuyordu. Ölçek metnin YALNIZ ekranda çizimini
// küçültür, dosyaya yazılan puntoya dokunmaz.
private const val DOCX_BASE_SP = 14f
private const val DOCX_LINE_SP = 20f
internal const val DOCX_SCALE_DEFAULT = 0.8f
private const val DOCX_SCALE_MIN = 0.6f
private const val DOCX_SCALE_MAX = 1.4f
// Sayfa görünümü tam sayfadan okunabilir metne kadar açılabilmeli; blok
// editörünün dar yazı-boyu aralığı burada yetmiyor.
private const val PAGE_ZOOM_MIN = 0.5f
private const val PAGE_ZOOM_MAX = 4f
private const val DOCX_VIEW_PREFS = "docx_viewer"
private const val DOCX_SCALE_KEY = "text_scale_v1"

// 0.05'lik ızgaraya yuvarlar: float toplamı 0.7999999 gibi değerler üretip
// yüzde etiketini "%79" yapıyordu.
internal fun adjustDocxScale(current: Float, step: Float): Float =
    (((current + step) * 20f).roundToInt() / 20f).coerceIn(DOCX_SCALE_MIN, DOCX_SCALE_MAX)

@Composable
fun DocxLiteEditor(
    state: DocxEditorState,
    actions: RemoteViewModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    // Kaydet tuşu çağıran tarafta (başlık taşma menüsünde) olabilir.
    showSaveButton: Boolean = true,
) {
    var activeId by rememberSaveable(state.path) { mutableStateOf("") }
    var selection by remember(state.path) { mutableStateOf(TextRange.Zero) }
    var fontMenu by remember { mutableStateOf(false) }
    var confirmTimesAll by remember { mutableStateOf(false) }
    var udfEditContext by remember(state.path) { mutableStateOf<UdfEditContext?>(null) }
    var udfSizeMenu by remember { mutableStateOf(false) }
    // Ölçek belge başına değil KULLANICI başına: bir kez ayarla, her belgede
    // öyle açılsın.
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(DOCX_VIEW_PREFS, Context.MODE_PRIVATE) }
    var scale by remember { mutableStateOf(prefs.getFloat(DOCX_SCALE_KEY, DOCX_SCALE_DEFAULT)) }
    // UDF sayfa görünümünün büyütmesi AYRI: blok editörünün "yazı boyu" ölçeği
    // ile aynı şey değil (orada metin büyür, burada sayfa yakınlaşır) ve daha
    // geniş bir aralık ister. Belge değişince sıfırlanır.
    var pageZoom by remember(state.path) { mutableStateOf(1f) }
    val setScale = { next: Float ->
        scale = next
        prefs.edit().putFloat(DOCX_SCALE_KEY, next).apply()
    }

    val udfDocument = state.udfDocument

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
        when {
            state.loading -> LoadingSkeleton(rows = 6)
            state.error.isNotBlank() && state.blocks.isEmpty() -> EmptyState(
                title = "DOCX açılamadı",
                description = state.error,
            )
            // UDF: gerçek yazdırma düzeni (A4 sayfaları, kenar boşlukları, sekme
            // durakları, tablo ızgarası) — ve düzenleme de BURADA. Kullanıcı
            // satıra dokunup yerinde yazar (karar 16.08.2026); blok listesine
            // düşülmez, seçim biçimi yukarıdaki hafif araç çubuğundan uygulanır.
            udfDocument != null -> {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val edit = udfEditContext
                    // Durum, satır aralığının hemen solunda: iki ayrı başlık
                    // satırı belge yüzeyinden gereksiz yere yükseklik çalıyordu.
                    when {
                        state.signed -> StatusBadge("Elektronik imzalı belge", StatusKind.Done)
                        state.dirty -> StatusBadge("Kaydedilmemiş değişiklik", StatusKind.Attention)
                        state.saved -> StatusBadge("PC'ye kaydedildi", StatusKind.Done)
                        else -> Text(
                            "Satıra dokunup yazabilirsin",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ui2.colors.ink3,
                        )
                    }
                    Text(
                        "Satır aralığı: " + udfLineSpacingLabel(
                            edit?.let { it.lineSpacing ?: 0f } ?: uniformLineSpacing(udfDocument)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ui2.colors.ink3,
                    )
                    if (!state.readOnly) {
                        Column {
                            OutlinedButton(
                                onClick = { udfSizeMenu = true },
                                enabled = edit != null,
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) {
                                Text("${edit?.size ?: 12} pt")
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = udfSizeMenu, onDismissRequest = { udfSizeMenu = false }) {
                                listOf(8, 9, 10, 11, 12, 13, 14, 16, 18, 20).forEach { size ->
                                    DropdownMenuItem(
                                        text = { Text("$size pt") },
                                        onClick = {
                                            edit?.let {
                                                actions.setUdfSelectionFontSize(
                                                    it.line, it.selection.min, it.selection.max, size
                                                )
                                            }
                                            udfSizeMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        DocxFormatTextButton("B", FontWeight.Bold, enabled = edit != null) {
                            edit?.let { actions.formatUdfSelection(it.line, it.selection.min, it.selection.max, "bold") }
                        }
                        DocxFormatTextButton("I", fontStyle = FontStyle.Italic, enabled = edit != null) {
                            edit?.let { actions.formatUdfSelection(it.line, it.selection.min, it.selection.max, "italic") }
                        }
                        DocxFormatTextButton("U", decoration = TextDecoration.Underline, enabled = edit != null) {
                            edit?.let { actions.formatUdfSelection(it.line, it.selection.min, it.selection.max, "underline") }
                        }
                        IconButton(
                            onClick = { edit?.let { actions.setUdfParagraphAlignment(it.line, 0) } },
                            enabled = edit != null,
                            modifier = Modifier.size(36.dp),
                        ) { Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, "Sola hizala", tint = Ui2.colors.ink2) }
                        IconButton(
                            onClick = { edit?.let { actions.setUdfParagraphAlignment(it.line, 1) } },
                            enabled = edit != null,
                            modifier = Modifier.size(36.dp),
                        ) { Icon(Icons.Default.FormatAlignCenter, "Ortala", tint = Ui2.colors.ink2) }
                        IconButton(
                            onClick = { edit?.let { actions.setUdfParagraphAlignment(it.line, 2) } },
                            enabled = edit != null,
                            modifier = Modifier.size(36.dp),
                        ) { Icon(Icons.AutoMirrored.Filled.FormatAlignRight, "Sağa hizala", tint = Ui2.colors.ink2) }
                        IconButton(
                            onClick = { edit?.let { actions.setUdfParagraphAlignment(it.line, 3) } },
                            enabled = edit != null,
                            modifier = Modifier.size(36.dp),
                        ) { Icon(Icons.Default.FormatAlignJustify, "İki yana yasla", tint = Ui2.colors.ink2) }
                    }
                    DocxCompactAction("Paylaş", actions::shareOpenedDocx)
                    DocxFormatTextButton("A−") {
                        pageZoom = (pageZoom / 1.25f).coerceIn(PAGE_ZOOM_MIN, PAGE_ZOOM_MAX)
                    }
                    Text(
                        "%" + (pageZoom * 100).roundToInt(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ui2.colors.ink3,
                    )
                    DocxFormatTextButton("A+") {
                        pageZoom = (pageZoom * 1.25f).coerceIn(PAGE_ZOOM_MIN, PAGE_ZOOM_MAX)
                    }
                    if (!state.readOnly) {
                        Button(
                            onClick = { actions.saveOpenedDocx() },
                            enabled = state.dirty && !state.saving,
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) { Text(if (state.saving) "Kaydediliyor…" else "Kaydet") }
                        DocxCompactAction("Mobil imza", actions::mobilImzaAc)
                    }
                }
                if (state.signers.isNotEmpty()) {
                    Text(
                        "İmzalayan: " + state.signers.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ui2.colors.ink3,
                    )
                }
                if (state.error.isNotBlank()) {
                    Text(state.error, color = Ui2.colors.danger, style = MaterialTheme.typography.bodySmall)
                }
                UdfDocumentView(
                    document = udfDocument,
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (compact) it.heightIn(min = 260.dp, max = 520.dp) else it.weight(1f) }
                        .background(Ui2.colors.surface2, RoundedCornerShape(Ui2Tokens.cornerCard))
                        .clip(RoundedCornerShape(Ui2Tokens.cornerCard)),
                    zoom = pageZoom,
                    onZoomChange = { change ->
                        pageZoom = (pageZoom * change).coerceIn(PAGE_ZOOM_MIN, PAGE_ZOOM_MAX)
                    },
                    // İmzalı belge düzenlenmez: metni değiştirmek imzayı geçersiz
                    // kılar (bkz. UdfPackage).
                    editable = !state.readOnly,
                    onEditLine = actions::updateUdfLine,
                    onEditContext = { udfEditContext = it },
                )
                if (showSaveButton && !state.readOnly) {
                    Button(
                        onClick = { actions.saveOpenedDocx() },
                        enabled = state.dirty && !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(kaydetEtiketi(state))
                    }
                }
            }
            // Salt okunur DOCX: biçim çubuğu YOK. Hiçbir şeyi değiştirmeyen
            // B/I/U/Font tuşlarını göstermek, her paragrafın altına "düzenleme
            // kapalı" yazmaktan daha kötüydü — ekranı da yiyordu.
            state.readOnly -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Salt okunur",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ui2.colors.ink3,
                    )
                    Spacer(Modifier.weight(1f))
                    DocxCompactAction("Paylaş", actions::shareOpenedDocx)
                    DocxFormatTextButton("A−") { setScale(adjustDocxScale(scale, -0.1f)) }
                    Text(
                        "%" + (scale * 100).roundToInt(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ui2.colors.ink3,
                    )
                    DocxFormatTextButton("A+") { setScale(adjustDocxScale(scale, 0.1f)) }
                }
                DocxBlockList(state, actions, scale, compact, activeId, { activeId = it }, { selection = it })
            }
            else -> {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DocxFormatTextButton("B", FontWeight.Bold) {
                        actions.formatDocxSelection(activeId, selection.min, selection.max, "bold")
                    }
                    DocxFormatTextButton("I", fontStyle = FontStyle.Italic) {
                        actions.formatDocxSelection(activeId, selection.min, selection.max, "italic")
                    }
                    DocxFormatTextButton("U", decoration = TextDecoration.Underline) {
                        actions.formatDocxSelection(activeId, selection.min, selection.max, "underline")
                    }
                    IconButton(onClick = { actions.setDocxParagraphAlignment(activeId, "left") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, "Sola hizala", tint = Ui2.colors.ink2)
                    }
                    IconButton(onClick = { actions.setDocxParagraphAlignment(activeId, "center") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.FormatAlignCenter, "Ortala", tint = Ui2.colors.ink2)
                    }
                    IconButton(onClick = { actions.setDocxParagraphAlignment(activeId, "right") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.FormatAlignRight, "Sağa hizala", tint = Ui2.colors.ink2)
                    }
                    Column {
                        OutlinedButton(
                            onClick = { fontMenu = true },
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Text("Font")
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                            DocxFonts.forEach { font ->
                                DropdownMenuItem(
                                    text = { Text(font) },
                                    onClick = {
                                        actions.setDocxSelectionFont(activeId, selection.min, selection.max, font)
                                        fontMenu = false
                                    },
                                )
                            }
                        }
                    }
                    DocxCompactAction("Tümünü TNR") { confirmTimesAll = true }
                    DocxCompactAction("Paylaş", actions::shareOpenedDocx)
                    DocxFormatTextButton("A−") { setScale(adjustDocxScale(scale, -0.1f)) }
                    Text(
                        "%" + (scale * 100).roundToInt(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ui2.colors.ink3,
                    )
                    DocxFormatTextButton("A+") { setScale(adjustDocxScale(scale, 0.1f)) }
                }

                when {
                    state.dirty -> StatusBadge("Kaydedilmemiş değişiklik", StatusKind.Attention)
                    state.saved -> StatusBadge("PC'ye kaydedildi", StatusKind.Done)
                }
                if (state.error.isNotBlank()) {
                    Text(state.error, color = Ui2.colors.danger, style = MaterialTheme.typography.bodySmall)
                }

                // Aktif font göstergesi: imlecin/seçimin bulunduğu run'ın fontu.
                // Metin Android varsayılan fontuyla çizilir (gerçek DOCX fontu
                // gömülmez); font yalnız BİLGİ olarak burada yazar ve "Tümünü TNR"
                // ile topluca değiştirilir. Hiçbir paragraf seçili değilken
                // gizlenir: okuma modunda "belge varsayılanı" yazan bir satır
                // dar ekranda yer harcamaktan başka iş görmüyordu.
                if (activeId.isNotBlank()) {
                    Text(
                        "Font: " + activeFontLabel(state.blocks, activeId, selection),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ui2.colors.ink3,
                    )
                }

                DocxBlockList(state, actions, scale, compact, activeId, { activeId = it }, { selection = it })

                if (showSaveButton && !state.readOnly) {
                    Button(
                        onClick = { actions.saveOpenedDocx() },
                        enabled = state.dirty && !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(kaydetEtiketi(state))
                    }
                }
            }
        }
    }

    if (confirmTimesAll && !state.readOnly) {
        AlertDialog(
            onDismissRequest = { confirmTimesAll = false },
            title = { Text("Tüm metin Times New Roman yapılsın mı?") },
            text = { Text("Punto, kalın, italik ve altı çizili özellikleri korunur; DOCX içindeki metin run'larının font ailesi değiştirilir.") },
            confirmButton = {
                TextButton(onClick = { actions.setAllDocxFont("Times New Roman"); confirmTimesAll = false }) {
                    Text("Dönüştür")
                }
            },
            dismissButton = { TextButton(onClick = { confirmTimesAll = false }) { Text("Vazgeç") } },
        )
    }

    if (state.conflict) {
        AlertDialog(
            onDismissRequest = actions::dismissDocxStatus,
            title = { Text("DOCX bilgisayarda değişmiş") },
            text = { Text("Telefon düzenlemesi açıldıktan sonra PC kopyası değişti. Hangi sürüm korunsun?") },
            confirmButton = {
                TextButton(onClick = { actions.saveOpenedDocx(overwriteConflict = true) }) {
                    Text("Telefon sürümüyle değiştir")
                }
            },
            dismissButton = {
                TextButton(onClick = actions::reloadOpenedDocx) { Text("PC sürümünü yükle") }
            },
        )
    }
}

/**
 * Belge görünümü: tek sürekli sayfa. Paragraflar kutulu form alanı değil, akan
 * metin gibi çizilir (kenarlık/arka plan yok, sayfa dolgusu var). Blok ayrımı
 * KORUNUR çünkü her düzenlenebilir paragraf kendi `<w:p>`'sine geri yazılır —
 * tablo/görsel gibi desteklenmeyen öğeler aradaki yerlerinde dokunulmadan durur.
 *
 * Tam ekranda `weight(1f)`: belge içten kayar, alttaki "PC'ye kaydet" HEP görünür
 * kalır. Eski sabit 900dp tavan kısa ekranlarda tuşu ekran dışına itiyordu ve dış
 * kaydırma olmadığından erişilemiyordu.
 */
@Composable
private fun ColumnScope.DocxBlockList(
    state: DocxEditorState,
    actions: RemoteViewModel,
    scale: Float,
    compact: Boolean,
    activeId: String,
    onActiveIdChange: (String) -> Unit,
    onSelectionChange: (TextRange) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (compact) it.heightIn(min = 260.dp, max = 520.dp) else it.weight(1f) }
            .background(Ui2.colors.surface, RoundedCornerShape(Ui2Tokens.cornerCard))
            .padding(horizontal = Ui2Tokens.s16, vertical = Ui2Tokens.s12),
        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
    ) {
        items(state.blocks, key = { it.id }) { block ->
            when {
                // Boş satır belgenin bir parçasıdır ama İÇERİK değildir: kart
                // çizilirse (eskiden "[Korunan belge öğesi]") boşluklar ekranı
                // yer ve gerçek metin sayfalarca aşağı iner. Yalnız yer tutar.
                block.kind == "blank" -> Spacer(Modifier.height((DOCX_LINE_SP * scale).dp))

                block.editable -> DocxParagraphEditor(
                    block = block,
                    scale = scale,
                    active = activeId == block.id,
                    onActivate = { range -> onActiveIdChange(block.id); onSelectionChange(range) },
                    onSelection = { range -> if (activeId == block.id) onSelectionChange(range) },
                    onTextChange = { actions.updateDocxParagraphText(block.id, it) },
                )

                // Salt okunur paragraf: belge akışında akan metin gibi durur.
                // Tablo/görsel gibi yapısal öğeler ise ayrı bir yüzeyle ayrılır.
                block.kind == "paragraph" -> Text(
                    block.text,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (DOCX_BASE_SP * scale).sp,
                        lineHeight = (DOCX_LINE_SP * scale).sp,
                    ),
                    textAlign = when (block.alignment) {
                        "center" -> TextAlign.Center
                        "right" -> TextAlign.End
                        "justify" -> TextAlign.Justify
                        else -> TextAlign.Start
                    },
                    color = Ui2.colors.ink,
                )

                else -> Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Ui2Tokens.s4)
                        .background(Ui2.colors.surface2, RoundedCornerShape(Ui2Tokens.cornerInline))
                        .padding(Ui2Tokens.s8),
                ) {
                    Text(
                        block.text.ifBlank { "[Korunan belge öğesi]" },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = (DOCX_BASE_SP * scale).sp,
                            lineHeight = (DOCX_LINE_SP * scale).sp,
                        ),
                        color = Ui2.colors.ink2,
                        // maxLines YOK: salt okunur paragraf da belgenin
                        // parçası, 5 satırda kesilince cümle ortasında
                        // biten bozuk bir blok gibi görünüyordu.
                    )
                    if (block.detail.isNotBlank()) {
                        Text(block.detail, style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                    }
                }
            }
        }
    }
}

@Composable
private fun DocxFormatTextButton(
    label: String,
    weight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    decoration: TextDecoration? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Text(
            label,
            fontWeight = weight,
            fontStyle = fontStyle,
            textDecoration = decoration,
            color = if (enabled) Ui2.colors.ink else Ui2.colors.ink3,
        )
    }
}

private fun uniformLineSpacing(document: com.agent.bridge.udf.UdfDocument): Float? {
    val values = document.paragraphs.map { it.lineSpacing ?: 0f }.distinct()
    return values.singleOrNull()
}

internal fun udfLineSpacingLabel(lineSpacing: Float?): String {
    if (lineSpacing == null) return "karma · satıra dokun"
    val multiplier = lineSpacing + 1f
    val points = UdfUnits.lineHeightPoints(lineSpacing)
    fun decimal(value: Float): String = String.format(java.util.Locale.forLanguageTag("tr-TR"), "%.1f", value)
    return "${decimal(multiplier)} · ${decimal(points)} pt"
}

@Composable
private fun DocxCompactAction(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DocxParagraphEditor(
    block: DocxBlock,
    scale: Float,
    active: Boolean,
    onActivate: (TextRange) -> Unit,
    onSelection: (TextRange) -> Unit,
    onTextChange: (String) -> Unit,
) {
    var value by remember(block.id) { mutableStateOf(styledValue(block, TextRange(block.text.length), scale)) }
    // scale de anahtar: punto span'ların İÇİNE gömülü olduğu için ölçek
    // değişince AnnotatedString yeniden kurulmazsa metin eski boyutta kalır.
    LaunchedEffect(block.text, block.spans, block.alignment, scale) {
        val range = value.selection.let {
            TextRange(it.start.coerceIn(0, block.text.length), it.end.coerceIn(0, block.text.length))
        }
        value = styledValue(block, range, scale)
    }
    // BasicTextField: OutlinedTextField'ın kenarlık/label/dolgu çerçevesi yok —
    // paragraf belge metni gibi akar. Aktif paragraf yalnızca hafif bir zeminle
    // belli olur (odak göstergesi), kutu çizilmez.
    BasicTextField(
        value = value,
        onValueChange = { next ->
            value = next
            onSelection(next.selection)
            if (next.text != block.text) onTextChange(next.text)
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onActivate(value.selection) }
            .background(
                if (active) Ui2.colors.surface2 else Color.Transparent,
                RoundedCornerShape(Ui2Tokens.cornerInline),
            )
            .padding(horizontal = Ui2Tokens.s4, vertical = 2.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = Ui2.colors.ink,
            // Span'ı olmayan (biçimsiz) metin bu stille çizilir; ölçek yalnız
            // span'lara uygulanırsa aynı paragrafta iki farklı boy oluşuyor.
            fontSize = (DOCX_BASE_SP * scale).sp,
            lineHeight = (DOCX_LINE_SP * scale).sp,
            textAlign = when (block.alignment) {
                "center" -> TextAlign.Center
                "right" -> TextAlign.Right
                "both", "justify" -> TextAlign.Justify
                else -> TextAlign.Left
            },
        ),
        cursorBrush = SolidColor(Ui2.colors.accent),
    )
}

// İmlecin (veya seçimin başının) bulunduğu run'ın font adı. Karışık seçimde
// "karışık" yazar; hiç font bilgisi yoksa belgenin varsayılanı devrededir.
internal fun activeFontLabel(
    blocks: List<DocxBlock>,
    activeId: String,
    selection: TextRange,
): String {
    val block = blocks.firstOrNull { it.id == activeId && it.editable }
        ?: return "belge varsayılanı"
    val from = selection.min.coerceIn(0, block.text.length)
    val to = selection.max.coerceIn(from, block.text.length)
    // Boş seçimde (imleç) imlecin sağındaki karakteri kapsayan run'a bak.
    val upper = if (to > from) to else from + 1
    val touched = block.spans.filter { it.end > from && it.start < upper }
    val names = touched.map { it.style.font.trim() }.filter { it.isNotBlank() }.distinct()
    return when {
        names.isEmpty() -> "belge varsayılanı"
        names.size == 1 -> names.first()
        else -> "karışık (" + names.joinToString(", ") + ")"
    }
}

private fun styledValue(block: DocxBlock, selection: TextRange, scale: Float): TextFieldValue {
    val annotated = buildAnnotatedString {
        append(block.text)
        block.spans.forEach { span ->
            if (span.start >= span.end || span.start < 0 || span.end > block.text.length) return@forEach
            // fontFamily BİLİNÇLİ olarak set edilmez: metin Android varsayılan
            // fontuyla çizilir. Gerçek DOCX fontu (TNR/Calibri/Aptos) telefonda
            // gömülü olmadığı için taklit etmek yanıltıcı olurdu; aktif font
            // yukarıdaki göstergede yazılı ve dosyaya doğru şekilde yazılıyor.
            addStyle(
                SpanStyle(
                    fontWeight = if (span.style.bold) FontWeight.Bold else null,
                    fontStyle = if (span.style.italic) FontStyle.Italic else null,
                    textDecoration = if (span.style.underline) TextDecoration.Underline else null,
                    fontSize = (
                        if (span.style.sizeHalfPoints > 0) span.style.sizeHalfPoints / 2f else DOCX_BASE_SP
                    ).times(scale).sp,
                ),
                span.start,
                span.end,
            )
        }
    }
    return TextFieldValue(annotatedString = annotated, selection = selection)
}

/**
 * Kaydet tuşunun etiketi. HEDEFİ söyler: telefondan açılan belge telefona geri
 * yazılıyor, "PC'ye kaydet" orada düpedüz yanlış bilgi olurdu.
 */
private fun kaydetEtiketi(state: DocxEditorState): String = when {
    state.saving -> "Kaydediliyor…"
    state.phoneLocal -> "Telefona kaydet"
    else -> "PC'ye kaydet"
}
