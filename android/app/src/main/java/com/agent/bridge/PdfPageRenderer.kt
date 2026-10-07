package com.agent.bridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * PdfRenderer sarmalayıcısı: sayfa → Bitmap.
 *
 * Üç kural buranın var oluş sebebi:
 * 1. **PdfRenderer thread-safe DEĞİL** ve aynı anda yalnız TEK sayfa açık olabilir
 *    (openPage ikinciyi çağırınca patlar). Bütün erişim tek mutex'ten geçer.
 * 2. Dosya **seekable** olmalı — ParcelFileDescriptor istiyor. Bu yüzden köprüdeki
 *    PDF akışla açılamaz, önce diske inmesi gerekir (RemoteViewModel.openPdf).
 * 3. Sayfa bitmap'i ARGB_8888 olmak zorunda (renderer başka biçim kabul etmiyor),
 *    yani piksel başına 4 bayt. A4'ü ekran genişliğinin iki katında çizmek 25 MB'a
 *    çıkabiliyor; bu yüzden çözünürlük [MAX_PAGE_PIXELS] ile tavanlanır.
 */
class PdfPageRenderer private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    // Tek sayfa kuralı + thread-safe olmama: her şey sırayla.
    private val lock = Mutex()
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    /**
     * Sayfayı istenen genişlikte çizer. Gerçek genişlik tavana takılabilir; en-boy
     * oranı her hâlükârda korunur. Kapatılmış kaynakta null döner (yarışta çökme yok).
     */
    suspend fun render(index: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || index !in 0 until renderer.pageCount) return@withLock null
            renderer.openPage(index).use { page ->
                val ratio = if (page.width > 0) page.height.toFloat() / page.width.toFloat() else 1.4f
                var width = targetWidthPx.coerceIn(MIN_PAGE_WIDTH, MAX_PAGE_WIDTH)
                var height = (width * ratio).toInt().coerceAtLeast(1)
                // Uzun/dar sayfalar (tarama, tablo eki) genişlik tavanını geçmeden de
                // toplam pikselde patlayabilir; ikinci tavan onları da yakalar.
                val pixels = width.toLong() * height.toLong()
                if (pixels > MAX_PAGE_PIXELS) {
                    val scale = Math.sqrt(MAX_PAGE_PIXELS.toDouble() / pixels.toDouble()).toFloat()
                    width = (width * scale).toInt().coerceAtLeast(1)
                    height = (height * scale).toInt().coerceAtLeast(1)
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // PDF sayfası saydam bölgeler içerebiliyor; beyaz zemin basmazsak
                // koyu temada metin okunmaz hale geliyor.
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    override fun close() {
        // Kapatma da kilitli: çizim sürerken descriptor'ı çekmek native çökme demek.
        closed = true
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        private const val MIN_PAGE_WIDTH = 320
        private const val MAX_PAGE_WIDTH = 2200
        // 6 MP × 4 bayt ≈ 24 MB tavan; önbellekteki sayfa sayısıyla çarpılır.
        private const val MAX_PAGE_PIXELS = 6_000_000L

        /**
         * Açar; şifreli ya da bozuk dosyada [PdfOpenException] fırlatır. Çağıran
         * kullanıcıya sebebi gösterebilsin diye ayrı tip: "açılamadı" ile "şifreli"
         * kullanıcı için çok farklı iki durum.
         */
        suspend fun open(file: File, fontFix: PdfFontFixRequest? = null): PdfPageRenderer =
            withContext(Dispatchers.IO) {
                if (!file.isFile || file.length() == 0L) throw PdfOpenException("PDF dosyası bulunamadı")
                // Gömülü fontu olmayan evrakta (UYAP/iText) Türkçe harfler düşüyor;
                // düzeltilmiş kopya varsa onu açarız. Kopya bozuksa orijinale döneriz —
                // düzeltme hiçbir durumda dosyayı açılamaz hale getirmemeli.
                val patched = fontFix?.let {
                    runCatching { ensurePdfFontsEmbedded(file, it.cacheDir, it.fonts) }.getOrDefault(file)
                } ?: file
                openRaw(patched) ?: openRaw(file) ?: throw lastOpenError(file)
            }

        /** Düzeltme parametreleri; [open] bunları almazsa dosya olduğu gibi açılır. */
        data class PdfFontFixRequest(val fonts: PdfFontAssets, val cacheDir: File)

        private fun openRaw(file: File): PdfPageRenderer? {
            if (!file.isFile || file.length() == 0L) return null
            val fd = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }.getOrNull() ?: return null
            val renderer = runCatching { PdfRenderer(fd) }.getOrNull()
            if (renderer == null) {
                runCatching { fd.close() }
                return null
            }
            return PdfPageRenderer(fd, renderer)
        }

        /** Açılamayan dosyanın sebebini kullanıcı diline çevirir. */
        private fun lastOpenError(file: File): PdfOpenException {
            val fd = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }.getOrElse { return PdfOpenException("PDF açılamadı: ${it.message ?: "okunamıyor"}") }
            val error = runCatching { PdfRenderer(fd).close() }.exceptionOrNull()
            runCatching { fd.close() }
            // PdfRenderer şifreli dosyada SecurityException atıyor.
            return if (error is SecurityException) PdfOpenException("Bu PDF parola korumalı; telefonda açılamıyor")
            else PdfOpenException("PDF okunamadı (dosya bozuk olabilir)")
        }
    }
}

class PdfOpenException(message: String) : Exception(message)
