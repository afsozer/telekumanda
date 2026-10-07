package com.agent.bridge.ui2.components

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.agent.bridge.FittedSize
import com.agent.bridge.ImageViewerState
import com.agent.bridge.clampPan
import com.agent.bridge.fitInside
import com.agent.bridge.settleScale
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// Dahili görsel görüntüleyici: md/docx görüntüleyicilerin kardeşi.
//
// Klasördeki görseller bir HorizontalPager'ın sayfaları. Kaydırma jestini
// elle yazmak yerine pager'a bırakmak, parmakla birlikte kayma / komşunun aynı
// hızda gelmesi / fırlatma / kenarda direnç davranışlarının tamamını hazır
// veriyor. Zoom her sayfanın kendi işi; zoomluyken jestleri tüketip pager'ın
// sayfa çevirmesini engelliyoruz.

// Görünmeyen (komşu) sayfalar düşük çözünürlükte çözülür: kaydırırken üç
// fotoğrafın tam boyu bellekte durursa 12 MP'lik kareler 150 MB'ı bulur.
// Oturan sayfa tam çözünürlüğe yükseltilir, zoom keskin kalsın.
private const val PREVIEW_MAX_DIM = 1280
private const val FULL_MAX_DIM = 4096

@Composable
fun ImageViewer(
    state: ImageViewerState,
    actions: RemoteViewModel,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
) {
    val siblings = actions.imageSiblingPaths()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12)) {
        if (siblings.size > 1 && state.index >= 0) {
            // A changed order starts at the same file's new index, including after deletion.
            key(siblings) { ImagePager(state, actions, siblings, onClose) }
        } else {
            SingleImage(state, actions, onClose)
        }
    }
}

@Composable
private fun ColumnScopeActions(
    state: ImageViewerState,
    actions: RemoteViewModel,
    counter: String?,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onClose: () -> Unit,
    onShuffle: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var saveState by remember(state.path) { mutableStateOf("idle") } // idle|saving|saved|failed
    var deleteArmed by remember(state.path) { mutableStateOf(false) }
    ViewerActionStrip(
        left = {
            // Kaydırmayı bilmeyen için görünür karşılık + "kaçıncı görseldeyim".
            if (counter != null) {
                ImageCompactIconAction(
                    Icons.Default.Shuffle,
                    if (state.shuffled) "Karıştır açık, kapat" else "Karıştır",
                    enabled = onShuffle != null,
                    checked = state.shuffled,
                ) { onShuffle?.invoke() }
                ImageCompactIconAction(Icons.Default.ChevronLeft, "Önceki görsel", enabled = onPrev != null) {
                    onPrev?.invoke()
                }
                Text(counter, style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                ImageCompactIconAction(Icons.Default.ChevronRight, "Sonraki görsel", enabled = onNext != null) {
                    onNext?.invoke()
                }
            }
        },
        right = {
            // Telefondaki dosyayı telefona indirmek anlamsız.
            if (!state.local) {
                ImageCompactIconAction(Icons.Outlined.Download, "Telefona indir") {
                    // Gorsel oldugu icin Pictures'a — Galeri'de gorunsun.
                    if (state.path.isNotBlank()) {
                        actions.downloadByPath(
                            state.path,
                            state.name.ifBlank { "gorsel" },
                            com.agent.bridge.PhoneFiles.PICTURES_DIR,
                        )
                    }
                }
            }
            // Dahili görüntüleyiciyi atlayıp galeriye/düzenleyiciye geçiş.
            ImageCompactIconAction(Icons.AutoMirrored.Filled.OpenInNew, "Birlikte aç", enabled = state.path.isNotBlank()) {
                actions.openOpenedImageExternally()
            }
            ImageCompactIconAction(Icons.Default.Share, "Paylaş") { actions.shareOpenedImage() }
            // "PC'de sakla" yalnız sohbet eklerinde: paylaşılan görseller iş bitince
            // siliniyor, saklama kararını kullanıcı verir. Gezgindeki normal dosya
            // zaten kalıcı, orada bu tuş anlamsız.
            if (state.attachment && state.path.isNotBlank()) {
                val save = {
                    saveState = "saving"
                    deleteArmed = false
                    scope.launch { saveState = if (actions.saveImageToGallery(state.path)) "saved" else "failed" }
                    Unit
                }
                // Metin etiketi gidince durum yalnız simgeden okunuyor: dönen
                // halka → yazılıyor, tik → bitti, ünlem → olmadı (yeniden denenir).
                when (saveState) {
                    "saving" -> CompactPill {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Ui2.colors.ink3,
                            strokeWidth = 2.dp,
                        )
                    }
                    "saved" -> ImageCompactIconAction(
                        Icons.Default.Check,
                        "PC'de saklandı",
                        enabled = false,
                        tint = Ui2.colors.accent,
                    ) {}
                    "failed" -> ImageCompactIconAction(
                        Icons.Outlined.ErrorOutline,
                        "PC'de saklanamadı, yeniden dene",
                        tint = Ui2.colors.danger,
                    ) { save() }
                    else -> ImageCompactIconAction(Icons.Outlined.Save, "PC'de sakla") { save() }
                }
            }
            // Silme HER görselde var (ek olsun, gezginden açılmış olsun) —
            // beğenilmeyeni görüntülerken atabilmek istenen davranış.
            // İki dokunuşlu: ilki soruyu sorar, ikincisi siler. Silince komşuya
            // geçilir, görüntüleyici AÇIK KALIR; ancak gidilecek görsel kalmadıysa
            // kapanır. Onay durumu `remember(state.path)` ile tutulduğu için komşuya
            // geçince kendiliğinden sıfırlanır — sıradaki fotoğraf tek dokunuşla
            // silinemez.
            if (state.path.isNotBlank()) {
                // Onay simgenin KENDİSİNDE: ilk dokunuş çöp kutusunu kırmızıya
                // çevirir, ikincisi siler. Yanına "Emin misin?" yazmak şeridin
                // simge diline aykırıydı.
                ImageCompactIconAction(
                    Icons.Default.Delete,
                    if (deleteArmed) "Silmeyi onayla" else "Sil",
                    tint = if (deleteArmed) Ui2.colors.danger else null,
                ) {
                    if (deleteArmed) {
                        if (!actions.deleteOpenedImage()) onClose()
                    } else {
                        deleteArmed = true
                    }
                }
            }
        },
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ColumnScope.ImagePager(
    state: ImageViewerState,
    actions: RemoteViewModel,
    siblings: List<String>,
    onClose: () -> Unit,
) {
    val local = actions.imageSiblingsLocal()
    val pagerState = rememberPagerState(
        initialPage = state.index.coerceIn(0, siblings.lastIndex),
        pageCount = { siblings.size },
    )
    val scope = rememberCoroutineScope()
    val page = pagerState.currentPage
    // Kaydırma oturunca ViewModel'i takip ettir: başlık, paylaş ve indir hep
    // görünen sayfaya ait olsun.
    LaunchedEffect(pagerState.settledPage) {
        actions.showImageAt(pagerState.settledPage)
        actions.prefetchImageNeighbours(pagerState.settledPage)
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        // Sayfalar arasında nefes payı: bitişik giden iki fotoğraf tek bir uzun
        // şerit gibi okunuyordu, "bir sonrakine geçtim" hissi kaybolmuştu. Boşluk
        // sayfa GENİŞLİĞİNE eklenir, görselden kırpmaz.
        pageSpacing = Ui2Tokens.s20,
        key = { siblings[it] },
    ) { index ->
        ImagePage(
            path = siblings[index],
            local = local,
            // Yalnız oturan sayfa tam çözünürlüğe yükseltilir; kaydırma
            // sırasında geçilen sayfalar önizleme kalitesinde kalır.
            full = index == pagerState.settledPage,
            actions = actions,
        )
    }
    ColumnScopeActions(
        state = state,
        actions = actions,
        counter = "${page + 1} / ${siblings.size}",
        onPrev = if (page > 0) ({ scope.launch { pagerState.animateScrollToPage(page - 1) } }) else null,
        onNext = if (page < siblings.lastIndex) ({ scope.launch { pagerState.animateScrollToPage(page + 1) } }) else null,
        onClose = onClose,
        onShuffle = if (!pagerState.isScrollInProgress && !state.loading) actions::toggleImageShuffle else null,
    )
}

@Composable
private fun ColumnScope.SingleImage(state: ImageViewerState, actions: RemoteViewModel, onClose: () -> Unit) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        when {
            state.loading -> CircularProgressIndicator()
            state.error.isNotBlank() -> Text(state.error, color = Ui2.colors.danger)
            state.bytes != null -> DecodedImage(state.bytes!!, state.name, FULL_MAX_DIM, isCurrent = true)
            else -> Text("Görsel yok", color = Ui2.colors.ink3)
        }
    }
    ColumnScopeActions(state, actions, counter = null, onPrev = null, onNext = null, onClose = onClose)
}

/** Pager sayfası: baytını kendi çeker (ViewModel önbelleğinden) ve çözer. */
@Composable
private fun ImagePage(path: String, local: Boolean, full: Boolean, actions: RemoteViewModel) {
    val name = remember(path) { path.substringAfterLast('/').substringAfterLast('\\') }
    val bytes by produceState<Result<ByteArray>?>(initialValue = null, path, local) {
        value = runCatching { actions.loadImageBytes(path, local) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val result = bytes
        when {
            result == null -> CircularProgressIndicator()
            result.isFailure -> Text(
                result.exceptionOrNull()?.message ?: "Görsel açılamadı",
                color = Ui2.colors.danger,
            )
            else -> DecodedImage(
                bytes = result.getOrThrow(),
                name = name,
                maxDim = if (full) FULL_MAX_DIM else PREVIEW_MAX_DIM,
                isCurrent = full,
            )
        }
    }
}

// Çözüm sonucu: duraağan kare mi, oynatılacak animasyon mu.
internal sealed interface DecodedImageContent {
    data class Still(val bitmap: Bitmap) : DecodedImageContent
    data class Animated(val drawable: Drawable) : DecodedImageContent
}

@Composable
private fun DecodedImage(bytes: ByteArray, name: String, maxDim: Int, isCurrent: Boolean) {
    // Çözülen içerik maxDim değişince YENİDEN çözülür (önizleme -> tam), ama
    // eskisi ekranda kalır: aksi halde sayfa oturur oturmaz bir kare boşluk
    // görünüyordu.
    var shown by remember(bytes) { mutableStateOf<DecodedImageContent?>(null) }
    var failure by remember(bytes) { mutableStateOf("") }
    LaunchedEffect(bytes, maxDim) {
        withContext(Dispatchers.Default) { runCatching { decodeImageContent(bytes, name, maxDim) } }
            .onSuccess { shown = it; failure = "" }
            .onFailure { if (shown == null) failure = it.message ?: "desteklenmeyen biçim" }
    }
    val content = shown
    when {
        failure.isNotBlank() && content == null -> Text("Görsel çözülemedi: $failure", color = Ui2.colors.danger)
        content == null -> CircularProgressIndicator()
        // pageKey = bytes: SAYFANIN kimliği. Zoom durumunu bitmap'e bağlamak
        // yanlıştı, çünkü aynı fotoğraf iki kez çözülüyor (önizleme → tam).
        content is DecodedImageContent.Still -> ZoomableImage(content.bitmap, name, isCurrent, bytes)
        // Hareketli içerikte zoom yok: kare kare ölçeklemek hem pahalı hem istenmedi.
        content is DecodedImageContent.Animated -> AnimatedImageSurface(content.drawable, name)
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap, name: String, isCurrent: Boolean, pageKey: Any) {
    // Zoom durumu SAYFAYA bağlı, bitmap'e değil. Her sayfa iki kez çözülüyor
    // (komşuyken önizleme, oturunca tam çözünürlük) ve bitmap'e bağlamak her
    // yükseltmede TAZE bir durum nesnesi yaratıyordu. Tazenin viewport'u 0
    // kalıyordu: ölçüyü yalnız onSizeChanged veriyor, o da ölçü DEĞİŞMEDİĞİ için
    // bir daha ateşlemiyor. Sonuç: clampPan sınırı hep 0, kaydırma ölü.
    val zoom = remember(pageKey) { ImageZoomState() }
    // Yüksek çözünürlüklü kopya gelince içeriğin piksel ölçüsü değişir (oran aynı).
    LaunchedEffect(zoom, bitmap) {
        zoom.onContentChanged(bitmap.width.toFloat(), bitmap.height.toFloat())
    }
    val gestures = Modifier
        .fillMaxSize()
        .clipToBounds()
        // Sınırları hesaplamak için çizim alanı gerekiyor; fillMaxSize olduğu
        // için bu ölçü doğrudan görüntü penceresi.
        .onSizeChanged { zoom.onViewportChanged(it.width.toFloat(), it.height.toFloat()) }
        // Anahtar `zoom`, `Unit` DEĞİL. pointerInput anahtarı değişmedikçe bloğunu
        // yeniden başlatmaz; blok da içeride yakaladığı durum nesnesini ömür boyu
        // tutar. `Unit` iken jestler ARTIK ÇİZİLMEYEN eski nesneyi oynatıyordu:
        // ekranda hiçbir şey kımıldamıyor, üstelik o ölü nesne zoomlu kaldıysa
        // olayları tüketip pager'ın sayfa çevirmesini de kilitliyordu. Kullanıcının
        // "birkaç fotoğraf sonra zoom ve kaydırma donuyor, çık-gir düzeltiyor"
        // dediği hata buydu — çık-gir composition'ı baştan kurduğu için düzeliyordu.
        .pointerInput(zoom) {
            detectTapGestures(onDoubleTap = { zoom.toggle() })
        }
        .pointerInput(zoom) { imageGestures(zoom) }
        .graphicsLayer {
            scaleX = zoom.scale
            scaleY = zoom.scale
            translationX = zoom.offset.x
            translationY = zoom.offset.y
        }
    // Gerçek Ultra HDR fotoğrafın kendi gainmap'i var; onu ImageView çizsin.
    val hdr = hasGainmap(bitmap)
    // Pencere renk modunu yalnız GÖRÜNEN sayfa değiştirsin; komşu sayfalar da
    // istese mod açılıp kapanıp dururdu.
    HdrWindowMode(enabled = hdr && isCurrent)
    if (hdr) {
        // Donanım hızlandırmalı Canvas gainmap'i kendisi uyguluyor ve Google'ın
        // belgelediği yol bu. Compose'un Image'ında aynı garanti yok.
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    contentDescription = name
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            },
            update = { it.setImageBitmap(bitmap) },
            modifier = gestures,
        )
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = gestures,
        )
    }
}

private fun hasGainmap(bitmap: Bitmap): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()

/**
 * Pencereyi HDR renk moduna alır. Ultra HDR görselin parlak aralığı ancak
 * Activity penceresi bu moddayken çiziliyor. GERİ ALMAK ŞART: mod açıkken ekran
 * daha çok güç harcıyor, görüntüleyiciden çıkınca normale dönmeli.
 */
@Composable
private fun HdrWindowMode(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled, context) {
        val window = (context as? Activity)?.window
        if (window == null || !enabled) return@DisposableEffect onDispose {}
        val previous = window.colorMode
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        onDispose { window.colorMode = previous }
    }
}

@Composable
private fun AnimatedImageSurface(drawable: Drawable, name: String) {
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                contentDescription = name
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { view ->
            view.setImageDrawable(drawable)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (drawable as? AnimatedImageDrawable)?.takeIf { !it.isRunning }?.start()
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// internal: PDF okuyucu da aynı zoom/pan durumunu kullanıyor. Kopyalamak yerine
// paylaşmak bilinçli — dünkü "sayfa değişince donan zoom" hatasının düzeltmesi
// (durumun sayfaya bağlanması, pointerInput anahtarı) tek yerde kalsın.
@Stable
internal class ImageZoomState {
    var scale by mutableStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var viewportWidth = 0f
    private var viewportHeight = 0f
    // İçerik ölçüsü de değişebilir (önizleme → tam çözünürlük), bu yüzden
    // constructor'da sabit değil. İkisi de sonradan gelebildiği için hangisinin
    // önce geldiği önemsiz: her ikisi de refit()'i çağırır.
    private var contentWidth = 0f
    private var contentHeight = 0f
    private var fitted = FittedSize(0f, 0f)

    val zoomed: Boolean get() = scale > 1f

    fun onViewportChanged(width: Float, height: Float) {
        viewportWidth = width
        viewportHeight = height
        refit()
    }

    fun onContentChanged(width: Float, height: Float) {
        contentWidth = width
        contentHeight = height
        refit()
    }

    private fun refit() {
        fitted = fitInside(viewportWidth, viewportHeight, contentWidth, contentHeight)
        clamp()
    }

    fun apply(pan: Offset, zoomChange: Float) {
        scale = (scale * zoomChange).coerceIn(1f, 8f)
        // Parmakla BİREBİR: eskiden pan * scale uygulanıyordu, zoom arttıkça
        // görsel parmaktan kat kat hızlı kayıyordu.
        offset += pan
        clamp()
    }

    /** Jest bitti: az kalmış zoom 1x'e kilitlenir, kalan kayma sınırlara oturur. */
    fun settle() {
        scale = settleScale(scale)
        clamp()
    }

    fun toggle() {
        scale = if (zoomed) 1f else 2.5f
        offset = Offset.Zero
        clamp()
    }

    // Kayma fotoğrafın kenarlarıyla sınırlı; 1x'te hiç kayma hakkı yok, yani
    // görsel her zaman ortada durur.
    private fun clamp() {
        offset = Offset(
            clampPan(offset.x, fitted.width * scale, viewportWidth),
            clampPan(offset.y, fitted.height * scale, viewportHeight),
        )
    }
}

/**
 * Zoom ve kaydırma arasındaki iş bölümü. Jest ÇOK PARMAKLI ya da görsel
 * zoomluysa burası sahiplenir (yakınlaştırma/gezinme) ve olayları tüketir —
 * böylece pager sayfa çevirmez. Tek parmak ve 1x ise hiçbir şey tüketilmez,
 * jest pager'a geçer ve sayfa parmakla birlikte kayar.
 */
internal suspend fun PointerInputScope.imageGestures(zoom: ImageZoomState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accumulatedZoom = 1f
        var accumulatedPan = Offset.Zero
        var pastSlop = false
        var multiTouch = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            if (event.changes.count { it.pressed } > 1) multiTouch = true
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!pastSlop) {
                accumulatedZoom *= zoomChange
                accumulatedPan += panChange
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                val zoomMotion = abs(1 - accumulatedZoom) * centroidSize
                if (zoomMotion > viewConfiguration.touchSlop ||
                    accumulatedPan.getDistance() > viewConfiguration.touchSlop
                ) {
                    pastSlop = true
                }
            }
            if (pastSlop && (multiTouch || zoom.zoomed)) {
                zoom.apply(panChange, zoomChange)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        // Parmak kalkınca oturt: 1.1x gibi kalıntı zoom 1x'e kilitlenir, böylece
        // bir sonraki tek parmak hareketi sayfa çevirmeye gider.
        zoom.settle()
    }
}

/**
 * Görsel / PDF / tablo / video görüntüleyicilerin ORTAK işlem şeridi. Dördü de
 * bunu kullanır, tasarım dili tek yerde durur.
 *
 * İki kutup: solda görünüm ve gezinme (sayaç, geçiş, zoom, yazı boyu), sağda
 * dosya işlemleri (indir, aç, paylaş, sil). Ayrım keyfi değil — soldakiler
 * BELGENİN İÇİNDE dolaştırır, sağdakiler belgeyi DIŞARI çıkarır.
 *
 * Neden BoxWithConstraints + widthIn(min): SpaceBetween yalnız satır ekran
 * genişliğindeyken iki kutbu ayırır, yatay kaydırma ise satırı sonsuz genişlikte
 * ölçtürüp SpaceBetween'i anlamsız kılar. Alt sınırı ekran genişliğine sabitleyip
 * kaydırmayı açık bırakmak ikisini birden veriyor: sığdığında kutuplaşır, taşarsa
 * kaydırılır — hiçbir tuş erişilemez hâle gelmez.
 */
@Composable
internal fun ViewerActionStrip(
    modifier: Modifier = Modifier,
    left: @Composable RowScope.() -> Unit = {},
    right: @Composable RowScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stripMinWidth = maxWidth
        Row(
            Modifier.horizontalScroll(rememberScrollState()).widthIn(min = stripMinWidth),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
                verticalAlignment = Alignment.CenterVertically,
                content = left,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
                verticalAlignment = Alignment.CenterVertically,
                content = right,
            )
        }
    }
}

// Şeridin tek kabuğu: metin de simge de ilerleme halkası da bunun içinde durur,
// böylece hepsi aynı yükseklik/kenarlık/yuvarlaklıkta kalır.
@Composable
private fun CompactPill(
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    horizontalPadding: Dp = 10.dp,
    checked: Boolean? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .background(if (checked == true) Ui2.colors.accent else Ui2.colors.surface, Ui2Tokens.pill)
            .border(1.dp, if (checked == true) Ui2.colors.accent else Ui2.colors.line, Ui2Tokens.pill)
            .then(when {
                onClick == null -> Modifier
                checked != null -> Modifier.toggleable(
                    value = checked, enabled = enabled, role = Role.Switch,
                    onValueChange = { onClick() },
                )
                else -> Modifier.clickable(enabled = enabled, onClick = onClick)
            })
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// Simgeli tuş. Etiket ekrandan kalktığı için contentDescription ZORUNLU —
// TalkBack'in okuyacağı tek şey o.
// internal: PDF/tablo/video şeritleri de aynı tuşu kullanıyor.
@Composable
internal fun ImageCompactIconAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color? = null,
    checked: Boolean? = null,
    onClick: () -> Unit,
) {
    CompactPill(enabled = enabled, onClick = onClick, checked = checked) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint ?: when {
                checked == true -> Ui2.colors.onAccent
                enabled -> Ui2.colors.ink
                else -> Ui2.colors.ink3
            },
            modifier = Modifier.size(18.dp),
        )
    }
}

// internal: PDF/tablo/video görüntüleyicilerin aksiyon şeridi hâlâ metin pill
// kullanıyor. Görsel şeridi simgeye geçti, onlar geçmedi.
@Composable
internal fun ImageCompactAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    CompactPill(enabled = enabled, onClick = onClick, horizontalPadding = 8.dp) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) Ui2.colors.ink else Ui2.colors.ink3,
        )
    }
}

// GIF ve animasyonlu WebP oynatılabilsin diye önce ImageDecoder denenir (API 28+);
// sonuç AnimatedImageDrawable değilse duraağan yola düşülür. Duraağan görsellerde
// BitmapFactory yolu korundu: büyük fotoğrafları örnekleyerek çözmek ImageDecoder'ın
// varsayılanından daha öngörülebilir.
internal fun decodeImageContent(bytes: ByteArray, name: String, maxDim: Int = FULL_MAX_DIM): DecodedImageContent {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isAnimatableFormat(name)) {
        val drawable = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
        }.getOrNull()
        if (drawable is AnimatedImageDrawable) return DecodedImageContent.Animated(drawable)
    }
    // Ultra HDR'ın gainmap'i BitmapFactory yolunda korunma garantisi taşımıyor;
    // Android 14+ üzerinde önce ImageDecoder denenir. Başarısız olursa (bozuk
    // dosya, desteklenmeyen biçim) eski yola düşülür — davranış kaybı yok.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        runCatching { decodeSampledWithImageDecoder(bytes, maxDim) }.getOrNull()
            ?.let { return DecodedImageContent.Still(it) }
    }
    return DecodedImageContent.Still(decodeSampledBitmap(bytes, maxDim))
}

// ImageDecoder'ın kendi örnekleme çengeli. ALLOCATOR_SOFTWARE, bitmap'in hem
// Compose'da hem ImageView'da sorunsuz çizilebilmesi için — gainmap yine korunur.
@androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun decodeSampledWithImageDecoder(bytes: ByteArray, maxDim: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        var sample = 1
        while (info.size.width / sample > maxDim || info.size.height / sample > maxDim) sample *= 2
        if (sample > 1) decoder.setTargetSampleSize(sample)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

private fun isAnimatableFormat(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("gif", "webp")

// Büyük fotoğrafları belleğe sığacak şekilde örnekleyerek çözer. inSampleSize
// 2'nin katı olmalıdır; 4096 hedefi hem GPU doku sınırının hem de tipik telefon
// belleğinin güvenli tarafında kalır.
internal fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int = FULL_MAX_DIM): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "desteklenmeyen biçim" }
    var sample = 1
    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("görsel çözülemedi")
}
