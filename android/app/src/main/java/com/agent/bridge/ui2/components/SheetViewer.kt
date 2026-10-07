package com.agent.bridge.ui2.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.SheetCell
import com.agent.bridge.SheetGrid
import com.agent.bridge.SheetViewerState
import com.agent.bridge.XlsxLite
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens
import kotlin.math.roundToInt

/**
 * Uygulama içi XLSX okuyucu (SALT OKUNUR).
 *
 * Izgara kararları:
 * - Dikey yön LazyColumn'a bırakılır (bir sayfa binlerce satır olabilir), yatay
 *   yön **tembel değildir**: her satır aynı `ScrollState`i paylaşan düz bir Row.
 *   Satırların hizalı kalmasının tek güvenilir yolu bu — birden çok LazyRow'a
 *   tek durum paylaştırmak Compose'da desteklenmiyor. Bunun yerine görünen
 *   sütun penceresi elle hesaplanır (bkz. [WindowedRow]).
 * - **Zoom, düzeni ölçekler; görüntüyü değil.** graphicsLayer ile büyütmek metni
 *   bulanıklaştırırdı; burada sütun genişliği, satır yüksekliği ve yazı boyutu
 *   birlikte çarpılır, metin her kademede yeniden dizilir ve nettir.
 * - **Metin sarma satır yüksekliğini değiştirir, ama satırın TÜM hücrelerine
 *   bakarak.** Yalnız görünen hücrelere bakılsaydı yatay kaydırırken satırlar
 *   zıplardı. Tahmin karakter sayısından yapılır ve zoom'dan bağımsızdır:
 *   genişlik de yazı da aynı katsayıyla büyüdüğü için satır sayısı sabit kalır.
 */
@Composable
fun SheetViewer(
    state: SheetViewerState,
    actions: RemoteViewModel,
    modifier: Modifier = Modifier,
) {
    // Görünüm ayarları sayfa sekmeleri arasında KORUNUR: her sekmede zoom'u
    // yeniden ayarlamak zorunda kalmak sinir bozucuydu.
    var zoom by remember { mutableFloatStateOf(1f) }
    var wrap by remember { mutableStateOf(true) }
    var viewportPx by remember { mutableIntStateOf(0) }

    when {
        state.error.isNotBlank() -> EmptyState(
            title = "Tablo açılamadı",
            description = state.error,
            icon = Icons.Outlined.GridOn,
            modifier = modifier,
        )
        state.loading -> SheetLoading(state, modifier)
        state.sheets.isEmpty() -> EmptyState(
            title = "Boş çalışma kitabı",
            description = "Bu dosyada gösterilecek sayfa yok.",
            icon = Icons.Outlined.GridOn,
            modifier = modifier,
        )
        else -> Column(modifier.fillMaxSize()) {
            val index = state.selected.coerceIn(0, state.sheets.lastIndex)
            val sheet = state.sheets[index]
            val density = LocalDensity.current
            val avgChar = rememberAverageCharWidth()
            // Ölçeksiz genişlikler: hem zoom hesabının hem sarma tahmininin dayanağı.
            val baseWidths = remember(sheet, avgChar) {
                List(sheet.columnCount) { columnWidth(sheet, it, avgChar) + GRID_LINE }
            }
            val totalBasePx = remember(baseWidths, density) {
                with(density) { baseWidths.sumOf { it.roundToPx() } }
            }

            SheetActions(
                state = state,
                actions = actions,
                zoom = zoom,
                wrap = wrap,
                onZoom = { zoom = it.coerceIn(MIN_ZOOM, MAX_ZOOM) },
                onWrap = { wrap = it },
                fitZoom = {
                    if (viewportPx <= 0 || totalBasePx <= 0) 1f
                    else (viewportPx.toFloat() / totalBasePx).coerceIn(MIN_ZOOM, MAX_ZOOM)
                },
            )
            if (state.sheets.size > 1) SheetTabs(state.sheets, index) { actions.selectSheet(it) }
            if (sheet.notice.isNotBlank()) {
                Text(
                    sheet.notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = Ui2.colors.ink3,
                    modifier = Modifier.padding(bottom = Ui2Tokens.s4),
                )
            }
            if (sheet.columnCount == 0) {
                EmptyState(
                    title = "Bu sayfa boş",
                    description = "\"${sheet.name}\" sayfasında veri yok.",
                    icon = Icons.Outlined.GridOn,
                )
            } else {
                // Sayfa değişince yatay konum başa dönsün: eski sayfanın 40.
                // sütunundaki kaydırma yeni sayfada boşluğa bakmak demekti.
                key(index) {
                    SheetTable(
                        sheet = sheet,
                        baseWidths = baseWidths,
                        avgChar = avgChar,
                        zoom = zoom,
                        wrap = wrap,
                        onZoom = { factor -> zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM) },
                        onViewport = { viewportPx = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Görünüm denetimleri DOSYA işlemleriyle aynı şeritte: ayrı bir satır ~38dp
 * dikey yer yiyordu ve 366dp'lik telefonda ızgaraya kalan yer zaten azdı.
 * Solda görünüm (zoom, sığdır, sarma), sağda dosya işlemleri.
 */
@Composable
private fun SheetActions(
    state: SheetViewerState,
    actions: RemoteViewModel,
    zoom: Float,
    wrap: Boolean,
    onZoom: (Float) -> Unit,
    onWrap: (Boolean) -> Unit,
    fitZoom: () -> Float,
) {
    ViewerActionStrip(
        modifier = Modifier.padding(bottom = Ui2Tokens.s8),
        left = {
            // Yüzde hem gösterge hem düğme: dokununca %100'e döner. Parmakla
            // ayarlanan zoom'dan "başa dön"menin tek yolu olmasın diye. Sayı
            // olduğu için METİN kalıyor — simgeleştirilecek bir şey yok.
            ImageCompactAction("%${(zoom * 100).roundToInt()}") { onZoom(1f) }
            ImageCompactIconAction(Icons.Outlined.FitScreen, "Ekrana sığdır") { onZoom(fitZoom()) }
            TogglePillIcon(Icons.AutoMirrored.Filled.WrapText, "Metni sar", active = wrap) { onWrap(!wrap) }
        },
        right = {
            if (!state.local) {
                ImageCompactIconAction(Icons.Outlined.Download, "Telefona indir") {
                    actions.downloadOpenedSheet()
                }
            }
            // Düzenleme bu sürümde yok; tabloyu değiştirmek isteyen kendi
            // uygulamasına devreder.
            ImageCompactIconAction(Icons.AutoMirrored.Filled.OpenInNew, "Birlikte aç") {
                actions.openOpenedSheetExternally()
            }
            ImageCompactIconAction(Icons.Default.Share, "Paylaş") { actions.shareOpenedSheet() }
            ImageCompactIconAction(Icons.Outlined.AddComment, "Sohbete ekle") {
                actions.attachOpenedSheetToChat()
            }
        },
    )
}

// Açık/kapalı tuş. Etiket gidince "açık mı" bilgisini taşıyan tek şey dolgu ve
// kenarlık kalıyor; ikisi de korundu.
@Composable
private fun TogglePillIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(34.dp)
            .background(if (active) Ui2.colors.pillFill else Ui2.colors.surface, Ui2Tokens.pill)
            .border(1.dp, if (active) Ui2.colors.lineStrong else Ui2.colors.line, Ui2Tokens.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Ui2.colors.pillOn else Ui2.colors.ink,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SheetTabs(sheets: List<SheetGrid>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = Ui2Tokens.s8),
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sheets.forEachIndexed { index, sheet ->
            val active = index == selected
            Box(
                Modifier
                    .height(30.dp)
                    .background(if (active) Ui2.colors.pillFill else Ui2.colors.surface, Ui2Tokens.pill)
                    .border(1.dp, if (active) Ui2.colors.lineStrong else Ui2.colors.line, Ui2Tokens.pill)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    sheet.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) Ui2.colors.pillOn else Ui2.colors.ink2,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SheetLoading(state: SheetViewerState, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(Ui2Tokens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.progress >= 0f) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "%${(state.progress * 100).toInt()} indirildi",
                style = MaterialTheme.typography.bodySmall,
                color = Ui2.colors.ink2,
            )
        } else {
            CircularProgressIndicator()
            Text("Tablo okunuyor…", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
        }
    }
}

private val ROW_HEADER_WIDTH = 46.dp

/** Hücreler arası ızgara çizgisi; zemin rengi bu boşluktan sızarak çizilir. */
private val GRID_LINE = 1.dp

/**
 * Hücre metriklerinin tamamı **dp** cinsinden; yazı boyutu da dp'den türetilir.
 * Bilinçli: sistem yazı ölçeği hücre yüksekliğini değiştirseydi ızgara hizası
 * bozulurdu. Büyütme ihtiyacını sistem ölçeği yerine zoom karşılıyor.
 */
private val CELL_FONT = 11.dp
private val CELL_LINE = 15.dp
private val CELL_VPAD = 8.dp
private val CELL_HPAD = 6.dp

/**
 * Sarma açıkken bir satırın büyüyebileceği azami metin satırı.
 *
 * Sınırsız değil çünkü tek bir 400 karakterlik hücre satırı ekranın tamamına
 * yayardı ve tablo listeye dönerdi. Aşan içerik hücreye dokununca açılır.
 */
private const val MAX_WRAP_LINES = 6

private const val MIN_ZOOM = 0.35f
private const val MAX_ZOOM = 2.5f

/** Görünen pencerenin iki yanına eklenen yedek sütun (hızlı kaydırmada boşluk çıkmasın). */
private const val WINDOW_MARGIN = 2

/**
 * Hücre yazıtipinin ortalama karakter genişliğini ÖLÇER (tahmin etmez).
 *
 * Katsayı elle seçildiğinde ("yazı boyutunun 0.55'i") gerçek genişlik tutmuyordu:
 * labelSmall'un harf aralığı ve orta kalınlığı hesabı bozuyor, sonuçta satır
 * sayısı eksik çıkıp metin erken kesiliyordu. Örnek dizi Türkçe metnin harf
 * dağılımına yakın seçildi; ölçüm zoom'suz temel biçemle yapılır, bu yüzden
 * sonuç zoom'dan bağımsızdır ve bir kez hesaplanır.
 */
@Composable
private fun rememberAverageCharWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = cellTextStyle(zoom = 1f)
    return remember(measurer, style, density) {
        val sample = "aeiounrlstkmdybgvçşğüöıAEIOUNRLSTKMDYBGV 0123456789.,-/()"
        val width = measurer.measure(AnnotatedString(sample), style).size.width
        with(density) { (width.toFloat() / sample.length).toDp() }
    }
}

/** İçeriğe göre genişleyen sütunun sınırları. */
private val AUTO_FIT_MIN_WIDTH = 56.dp
private val AUTO_FIT_MAX_WIDTH = 200.dp

/**
 * Sütun genişliği.
 *
 * Dosya kendi genişliğini bildiriyorsa (karakter cinsinden, "0" rakamının kaç
 * tanesi sığar) ona uyulur — kullanıcı Excel'de nasıl ayarladıysa öyle görsün.
 * Bildirmiyorsa sabit bir varsayılan yerine **içeriğe göre** genişletilir:
 * sabit 96dp, kısa etiketleri gereksiz yere sarıyordu.
 *
 * Üst sınır 220dp: 260dp'de tek sütun 366dp'lik telefonun tamamını yiyordu.
 */
private fun columnWidth(sheet: SheetGrid, index: Int, avgChar: Dp): Dp {
    val chars = sheet.columnWidths.getOrNull(index) ?: 0.0
    if (chars > 0.0) return (chars * 7).dp.coerceIn(44.dp, 220.dp)
    var longest = 0
    val ceiling = ((AUTO_FIT_MAX_WIDTH - CELL_HPAD * 2) / avgChar).toInt()
    for (row in sheet.rows) {
        val length = row.getOrNull(index)?.text?.length ?: 0
        if (length > longest) longest = length
        if (longest >= ceiling) break // tavana ulaşıldı, kalan satırlar sonucu değiştiremez
    }
    return (avgChar * longest + CELL_HPAD * 2 + GRID_LINE)
        .coerceIn(AUTO_FIT_MIN_WIDTH, AUTO_FIT_MAX_WIDTH)
}

/**
 * Satırın kaç metin satırı kaplayacağı.
 *
 * Neden gerçek metin ölçümü değil: satır yükseklikleri çizimden ÖNCE ve
 * görünmeyen sütunlar için de bilinmek zorunda (yoksa yatay kaydırırken satırlar
 * zıplar). Compose'un ölçümü ancak çizilen hücre için mümkün.
 *
 * Bunun yerine karakter genişliği bir kez GERÇEKTEN ölçülür ([avgChar]) ve
 * kırılma açgözlü kelime sarmasıyla benzetilir. Önceki sürüm `uzunluk / sığan`
 * bölmesi yapıyordu; kelime sonlarındaki boşluğu saymadığı için gereken satırı
 * eksik hesaplıyor ve metin, sınırın ALTINDAYKEN kesiliyordu (canlı görüldü:
 * 4 satır sınırına rağmen 3 satırda "…").
 *
 * Ölçeksiz genişlikle çalışır: zoom hem genişliği hem yazıyı aynı katsayıyla
 * büyüttüğü için sonuç zoom'dan bağımsızdır, yakınlaştırırken yeniden hesaplanmaz.
 */
private fun rowLineCount(row: List<SheetCell>, baseWidths: List<Dp>, avgChar: Dp): Int {
    var lines = 1
    for ((index, cell) in row.withIndex()) {
        if (cell.text.length <= 1) continue
        val width = baseWidths.getOrNull(index) ?: continue
        val perLine = ((width - CELL_HPAD * 2 - GRID_LINE) / avgChar).toInt().coerceAtLeast(1)
        val needed = wrappedLineCount(cell.text, perLine)
        if (needed > lines) lines = needed
        if (lines >= MAX_WRAP_LINES) return MAX_WRAP_LINES
    }
    return lines
}

/**
 * Açgözlü kelime sarması; satır içi `\n` sert kırılmadır.
 *
 * internal: birim testi bu hesabı doğrudan ölçüyor (bkz. SheetWrapTest).
 */
internal fun wrappedLineCount(text: String, perLine: Int): Int {
    if (perLine <= 0) return MAX_WRAP_LINES
    var lines = 0
    for (paragraph in text.split('\n')) {
        lines++
        var used = 0
        for (word in paragraph.split(' ')) {
            val length = word.length
            when {
                // Satır boşken kelime doğrudan yerleşir; sığmayan kelime kendi
                // içinde kırılır (uzun URL, boşluksuz kod).
                used == 0 -> used = place(length, perLine) { lines += it }
                used + 1 + length <= perLine -> used += 1 + length
                else -> {
                    lines++
                    used = place(length, perLine) { lines += it }
                }
            }
            if (lines >= MAX_WRAP_LINES) return MAX_WRAP_LINES
        }
    }
    return lines.coerceIn(1, MAX_WRAP_LINES)
}

/** Kelimeyi boş satıra yerleştirir; taşan kısmın ek satır sayısını bildirir. */
private inline fun place(length: Int, perLine: Int, overflow: (Int) -> Unit): Int {
    if (length <= perLine) return length
    overflow((length - 1) / perLine)
    return length % perLine
}

@Composable
private fun SheetTable(
    sheet: SheetGrid,
    baseWidths: List<Dp>,
    avgChar: Dp,
    zoom: Float,
    wrap: Boolean,
    onZoom: (Float) -> Unit,
    onViewport: (Int) -> Unit,
    modifier: Modifier,
) {
    // TEK ScrollState, tüm satırlar: başlık ve gövde birlikte kayar. Her satıra
    // ayrı LazyRow verip durumu paylaştırmak Compose'da desteklenmiyor.
    val horizontal = rememberScrollState()
    val density = LocalDensity.current
    val widths = remember(baseWidths, zoom) { baseWidths.map { it * zoom } }
    // Sütun başlangıçları: dp yerleşim için, px pencere hesabı için.
    val starts = remember(widths) { widths.runningFold(0.dp) { acc, w -> acc + w } }
    val startsPx = remember(starts, density) { with(density) { starts.map { it.roundToPx() } } }
    // Satır sayıları zoom'dan bağımsız; yalnız sayfa ya da sarma değişince hesaplanır.
    val lineCounts = remember(sheet, wrap, baseWidths, avgChar) {
        if (!wrap) null else sheet.rows.map { rowLineCount(it, baseWidths, avgChar) }
    }
    var viewportPx by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<CellDetail?>(null) }

    // Yalnız GÖRÜNEN sütunlar çizilir. derivedStateOf şart: doğrudan
    // horizontal.value okunsaydı her kaydırma pikselinde tüm satırlar yeniden
    // bestelenirdi; böylece yalnız sütun sınırı geçilince yenilenir.
    val window by remember(startsPx) {
        derivedStateOf {
            if (widths.isEmpty() || viewportPx <= 0) 0 until widths.size.coerceAtMost(1)
            else {
                val scroll = horizontal.value
                val first = (startsPx.indexOfLast { it <= scroll }.coerceAtLeast(0) - WINDOW_MARGIN)
                    .coerceIn(0, widths.lastIndex)
                val afterLast = startsPx.indexOfFirst { it > scroll + viewportPx }
                val last = ((if (afterLast < 0) widths.size else afterLast) + WINDOW_MARGIN)
                    .coerceIn(first + 1, widths.size)
                first until last
            }
        }
    }

    val headerHeight = (CELL_LINE + CELL_VPAD * 2) * zoom
    Column(
        modifier
            .background(Ui2.colors.line)
            // Kıstırma jesti Initial geçişte yakalanır: Main'de kaydırma olayı
            // çoktan tüketmiş oluyordu. Tek parmak DOKUNULMAZ — yalnız iki
            // parmak varken tüketilir, böylece kaydırma bozulmaz.
            .pointerInput(Unit) { sheetPinch(onZoom) },
    ) {
        Row {
            GridCell("", ROW_HEADER_WIDTH * zoom, headerHeight, zoom, header = true)
            WindowedRow(
                horizontal = horizontal,
                window = window,
                starts = starts,
                height = headerHeight,
                modifier = Modifier.onSizeChanged {
                    viewportPx = it.width
                    onViewport(it.width)
                },
            ) { column ->
                GridCell(
                    XlsxLite.columnLabel(column), widths[column], headerHeight, zoom,
                    header = true, center = true,
                )
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(top = GRID_LINE),
            verticalArrangement = Arrangement.spacedBy(GRID_LINE),
        ) {
            itemsIndexed(sheet.rows) { rowIndex, row ->
                val lines = lineCounts?.getOrNull(rowIndex) ?: 1
                val height = (CELL_LINE * lines + CELL_VPAD * 2) * zoom
                Row {
                    GridCell(
                        "${rowIndex + 1}", ROW_HEADER_WIDTH * zoom, height, zoom,
                        header = true, center = true,
                    )
                    WindowedRow(
                        horizontal = horizontal,
                        window = window,
                        starts = starts,
                        height = height,
                    ) { column ->
                        val cell = row.getOrNull(column) ?: SheetCell()
                        GridCell(
                            text = cell.text,
                            width = widths[column],
                            height = height,
                            zoom = zoom,
                            numeric = cell.numeric,
                            maxLines = lines,
                            onClick = if (cell.text.isBlank()) null else {
                                {
                                    detail = CellDetail(
                                        "${XlsxLite.columnLabel(column)}${rowIndex + 1}",
                                        cell.text,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // Sarma açıkken bile 4 satırı aşan içerik kırpılır; tam metin burada görünür.
    detail?.let { open ->
        AlertDialog(
            onDismissRequest = { detail = null },
            containerColor = Ui2.colors.surface2,
            title = { Text(open.reference, style = MaterialTheme.typography.titleMedium, color = Ui2.colors.ink) },
            text = {
                SelectionContainer {
                    Text(open.text, style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink)
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Kapat", color = Ui2.colors.ink2) } },
        )
    }
}

/**
 * Yalnız iki parmakla çalışan kıstırma. `detectTransformGestures` kullanılamazdı:
 * o tek parmak sürüklemesini de tüketiyor ve tablonun kaydırması ölüyor.
 */
private suspend fun PointerInputScope.sheetPinch(onZoom: (Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var event: androidx.compose.ui.input.pointer.PointerEvent
        do {
            event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) {
                val change = event.calculateZoom()
                if (change != 1f) onZoom(change)
                // İki parmak varken kaydırma devre dışı: aksi hâlde tablo aynı
                // anda hem yakınlaşıp hem kayıyordu.
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

private data class CellDetail(val reference: String, val text: String)

/**
 * Yatay kaydırılan hücre şeridi: görünmeyen sütunların yerini iki dolgu tutar.
 * Toplam genişlik değişmediği için kaydırma aralığı da sabit kalır — pencere
 * kayarken çubuk zıplamaz.
 */
@Composable
private fun WindowedRow(
    horizontal: ScrollState,
    window: IntRange,
    starts: List<Dp>,
    height: Dp,
    modifier: Modifier = Modifier,
    cell: @Composable (Int) -> Unit,
) {
    Row(modifier.horizontalScroll(horizontal)) {
        if (window.isEmpty()) return@Row
        Filler(starts[window.first], height)
        for (column in window) cell(column)
        Filler(starts.last() - starts[window.last + 1], height)
    }
}

/** Dolgu zemini hücre rengiyle aynı: hızlı kaydırmada çizgi rengi çakmasın. */
@Composable
private fun Filler(width: Dp, height: Dp) {
    if (width <= 0.dp) return
    Box(Modifier.width(width).height(height).background(Ui2.colors.surface))
}

/**
 * Hücre biçemi. Ölçüm ve çizim AYNI yerden beslenmeli: satır sayısı hesabı bu
 * biçemle ölçülüp başka bir biçemle çizilseydi kırılma noktaları tutmazdı.
 */
@Composable
private fun cellTextStyle(zoom: Float): TextStyle {
    val density = LocalDensity.current
    return MaterialTheme.typography.labelSmall.copy(
        fontSize = with(density) { (CELL_FONT * zoom).toSp() },
        lineHeight = with(density) { (CELL_LINE * zoom).toSp() },
    )
}

@Composable
private fun GridCell(
    text: String,
    width: Dp,
    height: Dp,
    zoom: Float,
    header: Boolean = false,
    numeric: Boolean = false,
    center: Boolean = false,
    maxLines: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    val style = cellTextStyle(zoom)
    // Dış kutu ızgara çizgisini de kapsar; iç kutu hücrenin kendisi. Aradaki
    // 1dp'den üst katmanın zemini (çizgi rengi) görünür.
    Box(Modifier.width(width).height(height).padding(end = GRID_LINE)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (header) Ui2.colors.surface2 else Ui2.colors.surface)
                .let { if (onClick == null) it else it.clickable(onClick = onClick) }
                .padding(horizontal = CELL_HPAD * zoom, vertical = CELL_VPAD * zoom),
            // Sarılmış satırda üste yaslanır: yan yana duran kısa ve uzun
            // hücreler ortalanınca birbirine göre kaymış görünüyordu.
            contentAlignment = when {
                center -> if (maxLines > 1) Alignment.TopCenter else Alignment.Center
                numeric -> if (maxLines > 1) Alignment.TopEnd else Alignment.CenterEnd
                maxLines > 1 -> Alignment.TopStart
                else -> Alignment.CenterStart
            },
        ) {
            Text(
                text,
                style = style,
                color = if (header) Ui2.colors.ink3 else Ui2.colors.ink,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
