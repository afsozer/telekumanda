package com.agent.bridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [PdfFontFix]'in cihazda gerçekten iş gördüğünü ölçer — birim testleri dosya
 * yapısını doğruluyor, asıl soru ise **piksel**: PdfRenderer harfleri çiziyor mu.
 *
 * Fikstür `gomulusuz.pdf`, UYAP evrakının iskeleti: gömülü fontu olmayan bir
 * `/TrueType ArialMT` ve Türkçe harfleri Adobe glyph adlarıyla veren bir
 * `/Differences`. Düzeltmeden önce Ğ ğ İ Ş ş hiç çizilmiyor; ölçüt bu — düzeltilmiş
 * sayfada mürekkep belirgin biçimde artmalı.
 *
 * Çıktı PNG'leri de dışa yazılır (`getExternalFilesDir`), gözle bakmak gerekirse.
 */
@RunWith(AndroidJUnit4::class)
class PdfFontFixDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext

    @Test
    fun gomulusuzFontGomulunceTurkceHarflerCizilir() {
        assertInkGrows("gomulusuz.pdf")
    }

    /**
     * İkinci fikstür üç ayrı gerçek dosya tuhaflığını birden taşıyor (hepsi UYAP
     * ihbarnamelerinde ölçüldü): fontlar temel-14 `/Type1 /Helvetica` ve tanım
     * nesnesi yok, `startxref` 64 bayt yanlış yeri gösteriyor ve `%%EOF`ten sonra
     * 11 KB e-imza (CMS) verisi geliyor. Üçü de düzeltmeyi sessizce iptal ediyordu.
     */
    @Test
    fun temel14FontImzaliKapsayicidaDaDuzeltilir() {
        assertInkGrows("temel14.pdf")
    }

    private fun assertInkGrows(fixture: String) {
        val original = copyFixture(fixture)
        val fonts = PdfFontAssets(app)
        val cache = File(app.cacheDir, "fontfix-$fixture").also { it.deleteRecursively() }

        val repaired = ensurePdfFontsEmbedded(original, cache, fonts)
        assertTrue("düzeltme uygulanmalı", repaired.absolutePath != original.absolutePath)

        val before = render(original)
        val after = render(repaired)
        assertEquals("sayfa sayısı korunmalı", before.second, after.second)
        dump("$fixture-once.png", before.first)
        dump("$fixture-sonra.png", after.first)

        val inkBefore = ink(before.first)
        val inkAfter = ink(after.first)
        // Beş harf (Ğ ğ İ Ş ş) sayfada birkaç kez geçiyor; %5'lik eşik gürültüden
        // uzak ama yazı tipi farkına takılmayacak kadar gevşek.
        assertTrue(
            "$fixture: düzeltmeden sonra daha çok harf çizilmeli (önce=$inkBefore sonra=$inkAfter)",
            inkAfter > inkBefore * 1.05,
        )
    }

    @Test
    fun ikinciAcilistaOnbellekKullanilir() {
        val original = copyFixture("gomulusuz.pdf")
        val fonts = PdfFontAssets(app)
        val cache = File(app.cacheDir, "fontfix-test2").also { it.deleteRecursively() }
        val first = ensurePdfFontsEmbedded(original, cache, fonts)
        val second = ensurePdfFontsEmbedded(original, cache, fonts)
        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(1, cache.listFiles()?.size)
    }

    // ── yardımcılar ────────────────────────────────────────────────────────────

    private fun copyFixture(name: String): File {
        val file = File(app.cacheDir, name)
        inst.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun render(file: File): Pair<Bitmap, Int> {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(fd).use { renderer ->
            val count = renderer.pageCount
            renderer.openPage(0).use { page ->
                val w = 1200
                val h = (w * page.height.toFloat() / page.width.toFloat()).toInt()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(bmp).drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                fd.close()
                return Bitmap.createBitmap(bmp, 0, 0, w, (h * 0.35f).toInt()) to count
            }
        }
    }

    /** Koyu piksel sayısı — "ne kadar harf çizildi"nin kaba ama sağlam ölçüsü. */
    private fun ink(bmp: Bitmap): Int {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return pixels.count { (it and 0xFF) < 128 }
    }

    private fun dump(name: String, bmp: Bitmap) {
        val dir = app.getExternalFilesDir(null) ?: return
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
