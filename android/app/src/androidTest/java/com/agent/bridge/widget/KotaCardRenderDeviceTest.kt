package com.agent.bridge.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Kota kartının görsel denetimi: durumların hepsini gerçek Canvas'ta çizip
 * uygulamanın dış dosya dizinine PNG olarak yazar
 * (`Android/data/com.agent.bridge/files/kota-widget/`). Ana ekrana widget
 * eklemeden düzeni (kırpılan metin, taşan halka) görmek için; Mac'teki
 * KotaWidget'ın `Tools/render-preview.sh`'ının karşılığı.
 *
 *   adb -s <emülatör> shell am instrument -w -e class com.agent.bridge.widget.KotaCardRenderDeviceTest \
 *       com.agent.bridge.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class KotaCardRenderDeviceTest {
    private val now = Instant.parse("2026-10-05T10:20:00Z")
    private val zone = ZoneId.of("Europe/Istanbul")

    private fun bucket(id: String, label: String, remaining: Double, reset: String?) =
        KotaBucket(id, label, remaining, reset?.let { Instant.parse(it) })

    private val normal = KotaClaude(
        plan = "Max 5x",
        hero = bucket("claude-5h", "Son 5 saat", 93.0, "2026-10-05T14:59:59Z"),
        rest = listOf(
            bucket("claude-week", "Son 7 gün", 50.0, "2026-10-09T05:00:00Z"),
            bucket("claude-week-fable", "7 gün (Fable)", 98.0, null),
        ),
        stale = false,
    )
    private val low = normal.copy(
        hero = bucket("claude-5h", "Son 5 saat", 8.0, "2026-10-05T11:02:00Z"),
        rest = listOf(bucket("claude-week", "Son 7 gün", 21.0, null)),
    )
    private val full = normal.copy(hero = bucket("claude-5h", "Son 5 saat", 100.0, "2026-10-05T14:59:59Z"))

    @Test
    fun rendersAllStates() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.getExternalFilesDir(null), "kota-widget").apply { mkdirs() }
        val density = min(ctx.resources.displayMetrics.density, 2.6f)
        // Magic7 Pro'da 4×2 ≈ 360×190 dp ölçüsünün yarısı; ayrıca dar ve geniş uçlar.
        val sizes = listOf(176 to 186, 150 to 120, 200 to 220)
        for (dark in listOf(false, true)) {
            for ((wDp, hDp) in sizes) {
                val p = KotaCardPainter(density, dark)
                val w = (wDp * density).roundToInt()
                val h = (hDp * density).roundToInt()
                val cards = listOf(
                    p.drawClaude(w, h, normal, false, false, "", now, zone),
                    p.drawNano(w, h, KotaNano(3.74, 0.0469, 39), false, false, ""),
                    p.drawClaude(w, h, low, true, false, "", now, zone),
                    p.drawNano(w, h, KotaNano(0.62, null, null), true, true, ""),
                    p.drawClaude(w, h, full, false, true, "", now, zone),
                    p.drawNano(w, h, KotaNano(42.0, 1.25, 120), false, false, ""),
                    p.drawClaude(w, h, null, false, false, "Köprüye ulaşılamadı", now, zone),
                    p.drawNano(w, h, null, false, false, "Köprüde Nano-GPT verisi yok"),
                )
                val sheet = sheet(cards, w, h, gap = (8 * density).roundToInt(), dark = dark)
                File(out, "kota-${if (dark) "koyu" else "acik"}-${wDp}x$hDp.png").outputStream().use {
                    sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                assertTrue(cards.all { it.width == w && it.height == h })
            }
        }
    }

    /** Kartları ikişerli (widget gibi yan yana) dizip duvar kâğıdı benzeri zemine koyar. */
    private fun sheet(cards: List<Bitmap>, w: Int, h: Int, gap: Int, dark: Boolean): Bitmap {
        val rows = (cards.size + 1) / 2
        val sheet = Bitmap.createBitmap(2 * w + 3 * gap, rows * h + (rows + 1) * gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(if (dark) Color.rgb(0x10, 0x18, 0x24) else Color.rgb(0x9C, 0xB8, 0xD6))
        cards.forEachIndexed { i, bmp ->
            c.drawBitmap(bmp, (gap + (i % 2) * (w + gap)).toFloat(), (gap + (i / 2) * (h + gap)).toFloat(), null)
        }
        return sheet
    }
}
