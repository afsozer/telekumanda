package com.agent.bridge.ui2.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.agent.bridge.isImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Gezgin listesinde ikon yerine küçük önizleme. YALNIZ telefonun kendi
// diskindeki dosyalar için: PC'deki bir dosyanın önizlemesi, 40 piksellik
// ikon uğruna dosyanın tamamını köprüden indirmek demek olurdu.

private const val THUMB_DP = 40

/** Desteklenen türler: görseller + PDF ilk sayfa. */
fun hasThumbnail(name: String): Boolean = isImageFile(name) || isPdfName(name)

private fun isPdfName(name: String): Boolean =
    name.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

/**
 * Çözülmüş küçük resimler bellekte tutulur — liste kaydırırken aynı dosyayı
 * defalarca çözmek pil yakar. Anahtar yol+mtime+boyut: dosya değişirse
 * önizleme kendiliğinden tazelenir.
 */
private object ThumbnailCache {
    // 40dp'lik ARGB kareler ~10 KB; 256 giriş birkaç MB, uzun listeye yeter.
    private val cache = LruCache<String, Bitmap>(256)

    fun key(file: File): String = "${file.absolutePath}|${file.lastModified()}|${file.length()}"

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

@Composable
fun FileThumbnail(
    path: String,
    name: String,
    size: Dp = THUMB_DP.dp,
    fallback: @Composable () -> Unit,
) {
    val targetPx = with(LocalDensity.current) { size.roundToPx() }
    // null = henüz çözülmedi; içerik null ise çözülemedi, ikona düşülür.
    val bitmap by produceState<Bitmap?>(initialValue = null, path, targetPx) {
        value = withContext(Dispatchers.IO) { loadThumbnail(path, name, targetPx) }
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp == null) {
            // Çözülene kadar ikon durur; boş kutu bırakmak liste zıplatıyordu.
            fallback()
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)),
            )
        }
    }
}

private fun loadThumbnail(path: String, name: String, targetPx: Int): Bitmap? {
    val file = File(path)
    if (!file.isFile) return null
    val key = ThumbnailCache.key(file)
    ThumbnailCache.get(key)?.let { return it }
    val bitmap = runCatching {
        if (isPdfName(name)) renderPdfFirstPage(file, targetPx) else decodeImageThumbnail(file, targetPx)
    }.getOrNull() ?: return null
    ThumbnailCache.put(key, bitmap)
    return bitmap
}

// Tam çözünürlükte açıp küçültmek yerine inSampleSize ile ÖRNEKLEYEREK okur:
// 50 megapiksellik fotoğrafın tamamını belleğe almadan 40 piksellik kareyi verir.
private fun decodeImageThumbnail(file: File, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}

private fun renderPdfFirstPage(file: File, targetPx: Int): Bitmap? =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount < 1) return null
            renderer.openPage(0).use { page ->
                val scale = targetPx.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // PdfRenderer saydam zemine çizer; beyaz dökmezsek koyu temada
                // sayfa metni görünmez oluyor.
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }
