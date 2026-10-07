package com.agent.bridge.ui2.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.agent.bridge.PageLangState
import com.agent.bridge.PdfPageRenderer
import com.agent.bridge.PdfReadState
import com.agent.bridge.PdfViewerState
import com.agent.bridge.ReaderSearchResult
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.readerListIndexToPage
import com.agent.bridge.readerPageToListIndex
import com.agent.bridge.searchReaderPages
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

/**
 * Uygulama içi PDF okuyucu.
 *
 * Kararlar:
 * - **Dikey** sayfa geçişi: belge okumanın yönü bu. Görsel görüntüleyicideki yatay
 *   pager fotoğraf albümü için doğruydu, evrak için değil.
 * - Zoom/pan görsel görüntüleyiciyle AYNI durum sınıfını kullanır (ImageZoomState):
 *   1x'te jest pager'a geçer (sayfa çevrilir), zoomluyken sayfa gezinir.
 * - Sayfalar bitmap olarak çizilir ve bayt bütçeli bir LRU'da tutulur. Sayfa
 *   bitmap'i ARGB_8888 olmak zorunda (PdfRenderer şartı) — sınırsız önbellek
 *   birkaç sayfada OOM demek.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun PdfViewer(
    state: PdfViewerState,
    actions: RemoteViewModel,
    modifier: Modifier = Modifier,
) {
    val read by actions.pdfRead.collectAsState()
    val fontSize by actions.readerFontSize.collectAsState()
    val lang by actions.pageLang.collectAsState()
    val scope = rememberCoroutineScope()
    // Pager durumu iki dalın ÜSTÜNDE: okuma modu açılıp kapanırken sayfa
    // konumu kaybolmasın ve iki görünüm birbirini takip edebilsin.
    //
    // `key(state.localPath)`: belge değişince (aynı PdfViewer örneği canlı
    // kalsa bile) başlangıç sayfası kaldığı yerden yeniden hesaplanmalı.
    // localPath boşken (yükleniyor) yer tutucu bir durum kurulur, dosya
    // gelince gerçek initialPage'le yeniden kurulur.
    val pagerState = key(state.localPath) {
        val resumePage = if (state.localPath.isNotBlank()) actions.pdfResume(state.path)?.page ?: 1 else 1
        val initialPage = (resumePage - 1).coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
        rememberPagerState(initialPage = initialPage) { state.pageCount }
    }
    val readerState = rememberLazyListState()
    var searchOpen by remember { mutableStateOf(false) }
    when {
        state.error.isNotBlank() -> EmptyState(
            title = "PDF açılamadı",
            description = state.error,
            icon = Icons.Outlined.Description,
            modifier = modifier,
        )
        state.loading -> PdfLoading(state, modifier)
        state.pageCount <= 0 -> EmptyState(
            title = "Boş belge",
            description = "Bu PDF'te görüntülenecek sayfa yok.",
            icon = Icons.Outlined.Description,
            modifier = modifier,
        )
        else -> Column(modifier.fillMaxSize()) {
            PdfActions(state, read, fontSize, actions, onSearchClick = {
                // Aramaya basınca içerik gerekiyorsa yüklenir AMA okuma moduna
                // geçilmez — kullanıcı sayfa görünümünde kalırken arayabilmeli.
                actions.ensurePdfReadContent()
                searchOpen = true
            })
            if (read.active) {
                PdfReaderWithBadge(read, state.pageCount, fontSize, lang, readerState, actions, Modifier.fillMaxSize())
            } else {
                PdfPages(state, actions, pagerState, Modifier.fillMaxSize())
            }
        }
    }
    // İki görünüm arasında sayfa takibi. Metin akışa girdiği için eşleme
    // yaklaşıktır (boş sayfalar okuma modunda hiç görünmez), ama "31. sayfadaydım"
    // bilgisi kaybolmuyor.
    PdfViewSync(read, pagerState, readerState, actions)
    // Konum debounce'lu kaydedilir; yerel dosyada da çalışır.
    PdfPositionAutosave(state, read, pagerState, readerState, actions)
    if (searchOpen) {
        PdfSearchDialog(
            read = read,
            onDismiss = { searchOpen = false },
            onJump = { page, listIndex ->
                searchOpen = false
                scope.launch {
                    if (read.active && listIndex != null) {
                        readerState.scrollToItem(listIndex)
                    } else {
                        pagerState.scrollToPage((page - 1).coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)))
                    }
                }
            },
        )
    }
}

/**
 * Okuma modu ile sayfa görünümü arasında konum aktarımı.
 *
 * Tek yönlü değil: okuma moduna geçerken pager'ın sayfası, geri dönerken
 * okuyucunun ilk görünen sayfası taşınır. Aktarım YALNIZ geçiş anında olur —
 * sürekli senkron iki liste arasında salınıma yol açardı.
 *
 * İlk okuma moduna girişte (belge açıldıktan sonraki İLK aktivasyon) kayıtlı
 * bir konum varsa ("m"=="r") pager'ın o anki sayfası yerine TAM konum
 * (ri/ro) kullanılır — pager'dan türetilen sayfa kaba bir yaklaşımken, kayıtlı
 * konum kullanıcının kaldığı satırı bilir. Sonraki toggle'larda normal
 * pager→reader senkronuna dönülür (kullanıcı o sırada gerçekten sayfa
 * görünümündeydi, orası daha güncel).
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PdfViewSync(
    read: PdfReadState,
    pagerState: PagerState,
    readerState: LazyListState,
    actions: RemoteViewModel,
) {
    var resumeApplied by remember(read.path) { mutableStateOf(false) }
    LaunchedEffect(read.active, read.pages) {
        if (read.pages.isEmpty()) return@LaunchedEffect
        // Uyarı şeridi listenin ilk öğesi olabilir; sayfa dizini o kadar kayar.
        val offset = if (read.truncated || read.tablesTruncated) 1 else 0
        if (read.active) {
            if (!resumeApplied) {
                resumeApplied = true
                val resume = actions.pdfResume(read.path)
                if (resume != null && resume.mode == "r") {
                    val maxIndex = (read.pages.size - 1 + offset).coerceAtLeast(0)
                    readerState.scrollToItem(resume.ri.coerceIn(0, maxIndex), resume.ro.coerceAtLeast(0))
                    return@LaunchedEffect
                }
            }
            val target = pagerState.currentPage + 1
            val index = readerPageToListIndex(read.pages, offset, target)
            if (index != null) readerState.scrollToItem(index)
        } else {
            val page = readerListIndexToPage(read.pages, offset, readerState.firstVisibleItemIndex)
                ?: return@LaunchedEffect
            pagerState.scrollToPage((page - 1).coerceIn(0, pagerState.pageCount - 1))
        }
    }
}

/**
 * Konumu debounce'lu kaydeder. Yerel dosyada da çalışır — konum kaydı köprü
 * gerektirmiyor, ViewModel doğrudan SharedPreferences'a yazıyor.
 *
 * `rememberUpdatedState(read)` bilinçli: efekt yalnız (localPath, active)
 * değişince yeniden başlıyor ama her debounce turunda `read.pages`in EN GÜNCEL
 * hâlini görmesi gerekiyor (içerik efekt başladıktan SONRA da yüklenebilir —
 * arama tetiklediğinde tam bu sırayla olur).
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PdfPositionAutosave(
    state: PdfViewerState,
    read: PdfReadState,
    pagerState: PagerState,
    readerState: LazyListState,
    actions: RemoteViewModel,
) {
    val latestRead = rememberUpdatedState(read)
    LaunchedEffect(state.localPath, read.active) {
        if (state.localPath.isBlank()) return@LaunchedEffect
        val reading = read.active
        snapshotFlow {
            if (reading) {
                Triple(readerState.firstVisibleItemIndex, readerState.firstVisibleItemScrollOffset, true)
            } else {
                Triple(pagerState.currentPage, 0, false)
            }
        }
            .debounce(500)
            .collect { (index, itemOffset, isReading) ->
                val currentRead = latestRead.value
                val offset = if (currentRead.truncated || currentRead.tablesTruncated) 1 else 0
                val page = if (isReading) {
                    readerListIndexToPage(currentRead.pages, offset, index) ?: return@collect
                } else {
                    index + 1
                }
                // Sayfa modu kaydı yalnız sayfa DEĞİŞTİYSE yazılır. Belge kayıtlı
                // sayfadan açılıyor ve ilk emisyon aynı sayfayı "p" olarak yazıp
                // okuma modunun tam konum kaydını (ri/ro) yarım saniyede eziyordu —
                // kullanıcı okuma moduna girdiğinde kaldığı satır çoktan kaybolmuş
                // oluyordu. Gerçek bir sayfa çevirme ise bilinçli harekettir, "p"
                // kaydına dönmesi doğru.
                if (!isReading && actions.pdfResume(state.path)?.page == page) return@collect
                actions.savePdfPosition(
                    path = state.path,
                    page = page,
                    ri = if (isReading) index else 0,
                    ro = if (isReading) itemOffset else 0,
                    mode = if (isReading) "r" else "p",
                    // Köprüdeki belgede konum cihazlar arası paylaşılır; telefon
                    // içi dosyada paylaşılacak bir karşı taraf yok.
                    remote = !state.local,
                )
            }
    }
}

/**
 * Belge üstündeki işlem şeridi. Görsel görüntüleyiciyle aynı pill'ler — iki
 * görüntüleyicinin dili ayrışmasın.
 *
 * "Birlikte aç" burada gerçekten gerekli: imzalama, form doldurma ve yazdırma
 * bizim yapmadığımız (ve yakında yapmayacağımız) işler; okuyucu bunları
 * kullanıcının kendi uygulamasına devredebilmeli.
 */
@Composable
private fun PdfActions(
    state: PdfViewerState,
    read: PdfReadState,
    fontSize: Float,
    actions: RemoteViewModel,
    onSearchClick: () -> Unit,
) {
    ViewerActionStrip(
        modifier = Modifier.padding(bottom = Ui2Tokens.s8),
        left = {
            // Okuma modu çıkarımı KÖPRÜDE yapılıyor: telefondaki yerel dosyayı köprü
            // göremez, o yüzden tuş orada hiç görünmez ("çalışmayan tuş" göstermek
            // yerine yokluğu dürüst). Arama da aynı sebeple aynı koşulla kapalı —
            // içerik yine köprüden geliyor.
            if (!state.local) {
                // Tek tuş iki yönlü: simge GİDİLECEK görünümü gösterir, bulunulanı
                // değil — "Okuma modu"/"Sayfa görünümü" etiketleri de öyleydi.
                if (read.active) {
                    ImageCompactIconAction(Icons.AutoMirrored.Outlined.Article, "Sayfa görünümü") {
                        actions.togglePdfReadingMode()
                    }
                } else {
                    ImageCompactIconAction(Icons.AutoMirrored.Outlined.MenuBook, "Okuma modu") {
                        actions.togglePdfReadingMode()
                    }
                }
                ImageCompactIconAction(Icons.Default.Search, "Ara", onClick = onSearchClick)
            }
            // Yazı boyutu YALNIZ okuma modunda anlamlı; sayfa görünümü bitmap çiziyor.
            // A−/A+ zaten simge sayılır, metin kalıyor.
            if (read.active) ReaderFontButtons(fontSize) { actions.setReaderFontSize(it) }
        },
        right = {
            // Telefondaki dosyayı telefona indirmek anlamsız.
            if (!state.local) {
                ImageCompactIconAction(Icons.Outlined.Download, "Telefona indir") {
                    actions.downloadOpenedPdf()
                }
            }
            ImageCompactIconAction(Icons.AutoMirrored.Filled.OpenInNew, "Birlikte aç") {
                actions.openOpenedPdfExternally()
            }
            ImageCompactIconAction(Icons.Default.Share, "Paylaş") { actions.shareOpenedPdf() }
            // Modele sorabilmek için: "bu tensipte süreler ne?" Uzak dosyada aktarım
            // yok, köprü zaten diskinde — yalnız yol eklenir.
            ImageCompactIconAction(Icons.Outlined.AddComment, "Sohbete ekle") {
                actions.attachOpenedPdfToChat()
            }
        },
    )
}

@Composable
private fun PdfLoading(state: PdfViewerState, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(Ui2Tokens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Belirsiz çubuk yalnız boyut bilinmiyorken: "yüzde kaç" bilgisi büyük
        // evrakta beklemenin ne kadar süreceğini söyleyen tek ipucu.
        if (state.progress >= 0f) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "%${(state.progress * 100).toInt()} indirildi" +
                    (if (state.sizeBytes > 0) " · ${state.sizeBytes / (1024 * 1024)} MB" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = Ui2.colors.ink2,
            )
        } else {
            CircularProgressIndicator()
            Text("PDF açılıyor…", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PdfPages(
    state: PdfViewerState,
    actions: RemoteViewModel,
    pagerState: PagerState,
    modifier: Modifier,
) {
    // Önbellek belgeye bağlı: başka PDF açılınca sıfırdan başlar.
    val cache = remember(state.localPath) { PdfPageCache() }
    val scope = rememberCoroutineScope()
    var jumpOpen by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = Ui2Tokens.s8,
        ) { index ->
            PdfPage(index = index, state = state, actions = actions, cache = cache)
        }
        if (state.pageCount > 1) {
            PageBadge(
                current = pagerState.currentPage + 1,
                pageCount = state.pageCount,
                onClick = { jumpOpen = true },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
    // Sayfaya git: 200 sayfalık bir dosyada parmakla gezinmek işkence. Rozete
    // dokunmak açar — ayrı bir tuş şeridi şişirmesin.
    if (jumpOpen) {
        PageJumpDialog(
            pageCount = state.pageCount,
            current = pagerState.currentPage + 1,
            onGo = { page ->
                jumpOpen = false
                scope.launch { pagerState.scrollToPage((page - 1).coerceIn(0, state.pageCount - 1)) }
            },
            onDismiss = { jumpOpen = false },
        )
    }
}

@Composable
private fun PageJumpDialog(
    pageCount: Int,
    current: Int,
    onGo: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(current.toString()) }
    val page = value.trim().toIntOrNull()
    val valid = page != null && page in 1..pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui2.colors.surface2,
        title = { Text("Sayfaya git", style = MaterialTheme.typography.titleMedium, color = Ui2.colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ui2.colors.ink),
                )
                Text("1 – $pageCount", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { page?.let(onGo) }) {
                Text("Git", color = if (valid) Ui2.colors.accent else Ui2.colors.ink3)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç", color = Ui2.colors.ink2) } },
    )
}

/** Sağ alttaki "N / M" rozeti — hem sayfa görünümü hem okuma modu kullanır. */
@Composable
private fun PageBadge(
    current: Int,
    pageCount: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier
            .padding(Ui2Tokens.s8)
            .background(Ui2.colors.surface, Ui2Tokens.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            "$current / $pageCount",
            style = MaterialTheme.typography.labelSmall,
            color = Ui2.colors.ink2,
        )
    }
}

/**
 * Okuma modunu rozet ve "sayfaya git" diyaloguyla sarmalar. PdfReader.kt'nin
 * kendisi saf içerik çizer (metin akışı); rozet/diyalog diğer görüntüleyici
 * kontrolleriyle (PageBadge, PageJumpDialog) aynı dosyada kalsın diye burada.
 */
@Composable
private fun PdfReaderWithBadge(
    read: PdfReadState,
    pageCount: Int,
    fontSize: Float,
    lang: Map<Int, PageLangState>,
    readerState: LazyListState,
    actions: RemoteViewModel,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var jumpOpen by remember { mutableStateOf(false) }
    val offset = if (read.truncated || read.tablesTruncated) 1 else 0
    Box(modifier) {
        PdfReader(read, fontSize, lang, readerState, actions, Modifier.fillMaxSize())
        val current = readerListIndexToPage(read.pages, offset, readerState.firstVisibleItemIndex)
            ?: read.pages.firstOrNull()?.number
        if (current != null && pageCount > 1) {
            PageBadge(
                current = current,
                pageCount = pageCount,
                onClick = { jumpOpen = true },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
    if (jumpOpen) {
        val current = readerListIndexToPage(read.pages, offset, readerState.firstVisibleItemIndex)
            ?: read.pages.firstOrNull()?.number ?: 1
        PageJumpDialog(
            pageCount = pageCount,
            current = current,
            onGo = { page ->
                jumpOpen = false
                val index = readerPageToListIndex(read.pages, offset, page) ?: return@PageJumpDialog
                scope.launch { readerState.scrollToItem(index) }
            },
            onDismiss = { jumpOpen = false },
        )
    }
}

/**
 * Belgede arama diyaloğu. PageJumpDialog'la aynı görsel dil (AlertDialog +
 * OutlinedTextField), ama sonuç listesi var.
 *
 * Taranmış belgede ("reason"=="taranmis") arama anlamsız — metin katmanı yok,
 * bunu açıkça söyler. İçerik henüz yüklenmemişse (arama tuşu tetikledi ama
 * ağ cevabı gelmedi) yükleniyor durumunu gösterir.
 */
@Composable
private fun PdfSearchDialog(
    read: PdfReadState,
    onDismiss: () -> Unit,
    onJump: (page: Int, listIndex: Int?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(ReaderSearchResult(emptyList(), false)) }
    val offset = if (read.truncated || read.tablesTruncated) 1 else 0
    LaunchedEffect(query, read.pages) {
        if (query.isBlank()) {
            results = ReaderSearchResult(emptyList(), false)
            return@LaunchedEffect
        }
        // 225 KB'lık bir kitapta arama ana iş parçacığını kilitleyecek kadar
        // ağır değil ama yine de arka planda hesaplanır — tuşa her basışta
        // UI'yi bekletmesin.
        results = withContext(Dispatchers.Default) { searchReaderPages(read.pages, query) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui2.colors.surface2,
        title = { Text("Belgede ara", style = MaterialTheme.typography.titleMedium, color = Ui2.colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Ara…", color = Ui2.colors.ink3) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ui2.colors.ink),
                )
                when {
                    read.reason == "taranmis" -> Text(
                        "Taranmış belgede arama yok: metin katmanı bulunmuyor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ui2.colors.ink2,
                    )
                    read.loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ui2.colors.ink3)
                        Text("İçerik hazırlanıyor…", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
                    }
                    query.isBlank() -> Text(
                        "Aramak için yazın.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ui2.colors.ink3,
                    )
                    results.hits.isEmpty() -> Text(
                        "Sonuç yok.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ui2.colors.ink3,
                    )
                    else -> LazyColumn(
                        Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
                    ) {
                        items(results.hits) { hit ->
                            Text(
                                "s. ${hit.page} — ${hit.snippet}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ui2.colors.ink,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onJump(hit.page, readerPageToListIndex(read.pages, offset, hit.page))
                                    }
                                    .padding(vertical = Ui2Tokens.s4),
                            )
                        }
                        if (results.truncated) {
                            item {
                                Text(
                                    "Sonuçlar kesildi; ilk eşleşmeler gösteriliyor.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ui2.colors.ink3,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Kapat", color = Ui2.colors.ink2) } },
    )
}

@Composable
private fun PdfPage(
    index: Int,
    state: PdfViewerState,
    actions: RemoteViewModel,
    cache: PdfPageCache,
) {
    // Zoom durumu SAYFAYA bağlı (ImageViewer'daki aynı kural): bitmap'e bağlamak,
    // çözünürlük değişince viewport'u ölçülmemiş taze bir durum bırakıyor ve
    // kaydırma ölüyordu.
    val zoom = remember(state.localPath, index) { ImageZoomState() }
    var bitmap by remember(state.localPath, index) { mutableStateOf<Bitmap?>(null) }
    var widthPx by remember { mutableStateOf(0) }

    LaunchedEffect(state.localPath, index, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        val renderer: PdfPageRenderer = actions.pdfRenderer() ?: return@LaunchedEffect
        // Ekran genişliğinin 1.5 katı: 1x'te fazlası görünmüyor ama biraz
        // yakınlaştırınca metin bulanıklaşmasın.
        val target = (widthPx * 1.5f).toInt()
        val key = "$index@$target"
        val cached = cache.get(key)
        bitmap = cached ?: renderer.render(index, target)?.also { cache.put(key, it) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                widthPx = it.width
                zoom.onViewportChanged(it.width.toFloat(), it.height.toFloat())
            },
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current == null) {
            CircularProgressIndicator()
        } else {
            LaunchedEffect(zoom, current) {
                zoom.onContentChanged(current.width.toFloat(), current.height.toFloat())
            }
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "${state.name} — sayfa ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    // Anahtar `zoom`: durum nesnesi değişince jest bloğu da yenilenmeli,
                    // yoksa artık çizilmeyen eski nesneyi oynatır (ImageViewer'daki hata).
                    .pointerInput(zoom) { detectTapGestures(onDoubleTap = { zoom.toggle() }) }
                    .pointerInput(zoom) { imageGestures(zoom) }
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    },
            )
        }
    }
}

/**
 * Sayfa bitmap'leri için BAYT bütçeli LRU. Adet bazlı önbellek yanıltıcı olurdu:
 * A4 sayfa ~15 MB, uzun bir tarama sayfası çok daha fazla.
 */
private class PdfPageCache {
    private val cache = object : LruCache<String, Bitmap>(MAX_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }

    private companion object {
        // ~48 MB: üç dolu sayfa. Atılan bitmap'ler recycle EDİLMEZ — hâlâ çizimde
        // olabilirler; toplaması GC'ye bırakılır.
        const val MAX_KB = 48 * 1024
    }
}
