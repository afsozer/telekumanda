package com.agent.bridge.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Kartın tamamı tek bitmap'e çiziliyor. RemoteViews (ve onun üstündeki Glance)
// serbest çizim taşımıyor: yuvarlatılmış kare halka, cam tüp ışığı, kenar
// kırılması gibi Mac kartının dilini kuran her şey Canvas istiyor. Metin de
// bitmap'te; dokunma alanları düzende ayrı (bkz. kota_widget.xml).
//
// Ölçüler Mac'teki küçük kartın pt değerleri (≈170 pt kare kart). Kart o
// boyuttan büyük/küçükse `s` ile orantılanır; artan dikey yer başlık-halka ve
// halka-barlar arasına eşit bölünür (Mac'teki Spacer düzeni).
internal class KotaCardPainter(
    /** Bitmap pikselinin dp karşılığı (ekran yoğunluğu, üstten kırpılmış). */
    private val density: Float,
    private val dark: Boolean,
) {
    private val textPrimary = if (dark) Color.WHITE else Color.rgb(0x1C, 0x1C, 0x1E)
    private val textSecondary = withAlpha(textPrimary, if (dark) 0.58f else 0.55f)
    private val textTertiary = withAlpha(textPrimary, if (dark) 0.32f else 0.30f)
    private val orange = if (dark) Color.rgb(0xFF, 0x9F, 0x0A) else Color.rgb(0xFF, 0x95, 0x00)
    private val red = if (dark) Color.rgb(0xFF, 0x45, 0x3A) else Color.rgb(0xFF, 0x3B, 0x30)
    // Mac'te `.fill.tertiary` sistemin plakası üstünde; ölçülen ton.
    private val base = if (dark) Color.rgb(0x2A, 0x2A, 0x2D) else Color.rgb(0xF1, 0xF1, 0xF4)

    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val semibold: Typeface = if (Build.VERSION.SDK_INT >= 28) {
        Typeface.create(Typeface.DEFAULT, 600, false)
    } else Typeface.DEFAULT_BOLD

    private var s = 1f // kart ölçeği (1 = Mac küçük kartı)
    private fun u(pt: Float): Float = pt * s * density

    fun tone(t: KotaTone, accent: Int): Int = when (t) {
        KotaTone.ACCENT -> accent
        KotaTone.ORANGE -> orange
        KotaTone.RED -> red
        KotaTone.OVERFLOW -> NANO_OVERFLOW
        KotaTone.MUTED -> textSecondary
    }

    // ── Claude kartı ───────────────────────────────────────────────────────

    fun drawClaude(
        widthPx: Int, heightPx: Int,
        claude: KotaClaude?, stale: Boolean, refreshing: Boolean,
        emptyMessage: String, now: Instant, zone: ZoneId,
    ): Bitmap = card(widthPx, heightPx, CLAUDE_ACCENT) { c, w, h ->
        val headerBottom = header(c, w, "Claude", CLAUDE_ACCENT, claude?.plan, null, stale, refreshing)
        val hero = claude?.hero
        if (hero == null) {
            emptyState(c, w, headerBottom, h, emptyMessage)
            return@card
        }
        val rest = claude.rest
        val ring = if (rest.size >= 2) 58f else 62f
        val numberSize = if (rest.size >= 2) 18f else 20f

        // Alt satırlar tabandan yukarı, iki katlı (Mac geniş kartının satırı):
        // üstte ad ve kalan yüzde, altında kartın eni boyunca bar. Tek satırda
        // "%100" değer sütununa sığmayıp "%1…" diye kırpılıyordu.
        val barHeight = if (rest.size > 1) 6f else 8f
        val rowGap = if (rest.size > 1) 7f else 8f
        val rowHeight = ROW_LINE_PT + ROW_INNER_GAP_PT + barHeight
        val rowsHeight = rest.size * u(rowHeight) + max(0, rest.size - 1) * u(rowGap)
        val rowsTop = h - u(PAD) - rowsHeight

        // Halka satırı ortada. Kısa kartta (yatay ekran, küçük hücre) iki katlı
        // satırlar yer kapladığı için halka boşluğa sığdırılıyor: 150x120 dp'de
        // "7 gün" satırına biniyordu (17.10.2026, cihaz render'ında görüldü).
        val freeTop = headerBottom + u(2f)
        val freeBottom = rowsTop - u(2f)
        val ringPx = min(u(ring), max(u(40f), freeBottom - freeTop))
        val ringTop = freeTop + max(0f, (freeBottom - freeTop - ringPx) / 2f)
        val ringLeft = u(PAD)
        val tone = tone(KotaWidgetData.remainingTone(hero.remaining), CLAUDE_ACCENT)
        // Halkadaki çizgi 5 saatlik pencerenin kalan süresi (Mac geniş kartında
        // bu pencerenin barında duruyor; kare kartta 5 saatin barı yok).
        gaugeDial(
            c, RectF(ringLeft, ringTop, ringLeft + ringPx, ringTop + ringPx), hero.remaining, null, tone, numberSize,
            marker = KotaWidgetData.timeLeftPercent(hero, now),
        )

        // Halkanın sağı: pencere adı, yenilenme saati, kullanılan yüzde.
        val textX = ringLeft + ringPx + u(9f)
        val textW = w - u(PAD) - textX
        val lineGap = u(2f)
        var lines = listOfNotNull(
            Triple(hero.label, 13f, textPrimary to semibold),
            hero.resetAt?.let { Triple("↻ " + KotaWidgetData.resetLabel(it, now, zone), 12f, textSecondary to regular) },
            Triple("%${(100 - hero.remaining).roundToInt()} dolu", 11f, textSecondary to regular),
        )
        // Halka küçüldüyse üç satır yanına sığmıyor: halkadaki sayının tersi
        // olan "dolu" satırı düşüyor.
        if (lines.sumOf { (u(it.second) * 1.2f).toDouble() } + lineGap * (lines.size - 1) > ringPx + u(4f)) {
            lines = lines.dropLast(1)
        }
        val heights = lines.map { u(it.second) * 1.2f }
        var y = ringTop + (ringPx - heights.sum() - lineGap * (lines.size - 1)) / 2f
        for ((i, line) in lines.withIndex()) {
            val (text, size, style) = line
            val p = textPaint(size, style.first, style.second)
            val mid = y + heights[i] / 2f
            if (text.startsWith("↻ ")) {
                // ↻ her yazı tipinde yok (Roboto'da yok): simge elle çiziliyor.
                val r = u(size) * 0.36f
                refreshGlyph(c, textX + r, mid, r, style.first, u(1.3f))
                drawFitted(c, text.removePrefix("↻ "), textX + r * 2 + u(4f), mid, textW - r * 2 - u(4f), p)
            } else {
                drawFitted(c, text, textX, mid, textW, p)
            }
            y += heights[i] + lineGap
        }

        // Kalan pencereler altta tam genişlikte; bardaki çizgi kalan süre.
        var rowTop = rowsTop
        for (b in rest) {
            val bTone = tone(KotaWidgetData.remainingTone(b.remaining), CLAUDE_ACCENT)
            stackedRow(
                c, u(PAD), rowTop, w - u(PAD),
                KotaWidgetData.compactLabel(b.label), "%${b.remaining.roundToInt()}", b.remaining,
                bTone, CLAUDE_ACCENT, barHeight = barHeight, fontSize = 11.5f,
                marker = KotaWidgetData.timeLeftPercent(b, now),
            )
            rowTop += u(rowHeight) + u(rowGap)
        }
    }

    // ── Nano-GPT kartı ─────────────────────────────────────────────────────

    fun drawNano(
        widthPx: Int, heightPx: Int,
        nano: KotaNano?, stale: Boolean, refreshing: Boolean, emptyMessage: String,
    ): Bitmap = card(widthPx, heightPx, NANO_ACCENT) { c, w, h ->
        val overflow = nano != null && KotaWidgetData.nanoOverflow(nano)
        val headerBottom = header(
            c, w, "Nano-GPT", NANO_ACCENT,
            chip = if (overflow) "10 $+" else null, chipColor = NANO_OVERFLOW,
            stale = stale, refreshing = refreshing,
        )
        if (nano == null) {
            emptyState(c, w, headerBottom, h, emptyMessage)
            return@card
        }
        val fill = KotaWidgetData.nanoFill(nano)
        val color = tone(KotaWidgetData.nanoTone(nano), NANO_ACCENT)
        val daily = nano.dailySpendUsd?.takeIf { it.isNotEmpty() }

        // Alt blok: 30 günlük harcama grafiği ve pencerenin toplamı (Mac geniş
        // kartı). Grafik yoksa (eski köprü, kullanım ucu düştü) eski kapasite
        // çubuğu ve ölçek satırı: yer boş kalmıyor.
        val lineSize = 10f
        val chartH = u(if (daily != null) 22f else 7f)
        val bottomH = chartH + u(5f) + u(lineSize) * 1.25f
        val bottomTop = h - u(PAD) - bottomH
        val area = RectF(u(PAD), bottomTop, w - u(PAD), bottomTop + chartH)
        if (daily != null) dailyChart(c, area, daily, NANO_ACCENT)
        else capsuleBar(c, area, fill, color, withAlpha(color, 0.12f), gradient = false)
        val caption = when {
            daily != null -> KotaWidgetData.nanoWindowLine(nano)
            nano.todaySpendUsd != null -> KotaWidgetData.nanoScaleLine(nano)
            else -> "harcama okunamadı"
        }
        drawFitted(
            c, caption, u(PAD), bottomTop + chartH + u(5f) + u(lineSize) * 0.625f, w - 2 * u(PAD),
            textPaint(lineSize, textSecondary, regular),
        )

        // Halka bakiyenin kendisini taşıyor (10 $ ölçeğinde doluyor); eskiden
        // ortada yüzde, sağında dolar yazıyordu.
        val freeTop = headerBottom + u(2f)
        val freeBottom = bottomTop - u(2f)
        val ringPx = min(u(62f), max(u(40f), freeBottom - freeTop))
        val ringTop = freeTop + max(0f, (freeBottom - freeTop - ringPx) / 2f)
        val ringLeft = u(PAD)
        gaugeDial(
            c, RectF(ringLeft, ringTop, ringLeft + ringPx, ringTop + ringPx), fill, "BAKİYE", color, 18f,
            valueText = KotaWidgetData.usdNumber(nano.usd), unit = "$", unitLeading = true,
        )

        // Halkanın sağı: bugünün harcaması (grafiğin parlak son çubuğu); o da
        // yoksa ölçek.
        val textX = ringLeft + ringPx + u(9f)
        val textW = w - u(PAD) - textX
        val today = nano.todaySpendUsd
        val (bigText, smallText) = when {
            today != null -> KotaWidgetData.usdLabel(today) to KotaWidgetData.nanoTodayCaption(nano)
            KotaWidgetData.nanoOverflow(nano) -> "10 $+" to "ölçeğin üstünde"
            else -> "%${fill.roundToInt()}" to "10 $ ölçeği"
        }
        val big = textPaint(15f, textPrimary, semibold)
        val small = textPaint(10f, textSecondary, regular)
        val bigH = u(15f) * 1.2f
        val smallH = u(10f) * 1.25f
        val top = ringTop + (ringPx - bigH - smallH) / 2f
        drawFitted(c, bigText, textX, top + bigH / 2f, textW, big)
        drawFitted(c, smallText, textX, top + bigH + smallH / 2f, textW, small)
    }

    // ── Ortak parçalar ─────────────────────────────────────────────────────

    private inline fun card(
        widthPx: Int, heightPx: Int, accent: Int,
        body: (Canvas, Float, Float) -> Unit,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        s = (min(w, h) / density / CARD_PT).coerceIn(0.8f, 1.5f)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        glassBackground(c, w.toFloat(), h.toFloat(), accent)
        body(c, w.toFloat(), h.toFloat())
        return bitmap
    }

    /**
     * Mac'in tam renkli moddaki cam zemini (GlassBase → ton → GlassSheen):
     * renkli gövde alt-sağda yoğun, üstten düşen ışık, sol üst yansıma, altta
     * kalınlık gölgesi, kenarda ışık çizgisi ve renkli kırılma. Android ana
     * ekranı da arkasını bulanıklaştırmıyor; cam hissi aynı işçilikten.
     */
    private fun glassBackground(c: Canvas, w: Float, h: Float, accent: Int) {
        val radius = u(CORNER_PT)
        val rect = RectF(0f, 0f, w, h)
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = base
        c.drawRect(rect, p)

        fun fill(shader: Shader) {
            p.shader = shader
            c.drawRect(rect, p)
            p.shader = null
        }
        // Kartın kendi tonu.
        fill(LinearGradient(0f, 0f, w, h, withAlpha(accent, 0.16f), Color.TRANSPARENT, Shader.TileMode.CLAMP))
        // GlassSheen: renkli gövde.
        fill(
            LinearGradient(
                0f, 0f, w, h,
                intArrayOf(withAlpha(accent, 0f), withAlpha(accent, 0f), withAlpha(accent, if (dark) 0.16f else 0.13f)),
                floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP,
            ),
        )
        fill(RadialGradient(w, h, u(150f), withAlpha(accent, if (dark) 0.30f else 0.26f), withAlpha(accent, 0f), Shader.TileMode.CLAMP))
        // Üstten düşen ışık.
        fill(
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(withAlpha(Color.WHITE, if (dark) 0.13f else 0.70f), withAlpha(Color.WHITE, if (dark) 0.03f else 0.18f), withAlpha(Color.WHITE, 0f)),
                floatArrayOf(0f, 0.42f, 0.62f), Shader.TileMode.CLAMP,
            ),
        )
        // Sol üst yansıma.
        fill(RadialGradient(0f, 0f, u(130f), withAlpha(Color.WHITE, if (dark) 0.16f else 0.75f), withAlpha(Color.WHITE, 0f), Shader.TileMode.CLAMP))
        // Altta camın kalınlığı.
        fill(
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(withAlpha(Color.BLACK, 0f), withAlpha(Color.BLACK, 0f), withAlpha(Color.BLACK, if (dark) 0.30f else 0.07f)),
                floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP,
            ),
        )
        c.restore()

        // Kenar ışığı: üst-solda parlak, ortada sönük, alt-sağda geri dönen yansıma.
        val edge = u(1.5f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = edge
        p.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(withAlpha(Color.WHITE, if (dark) 0.55f else 1f), withAlpha(Color.WHITE, if (dark) 0.08f else 0.35f), withAlpha(Color.WHITE, if (dark) 0.22f else 0.85f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        val inset = RectF(edge / 2, edge / 2, w - edge / 2, h - edge / 2)
        c.drawRoundRect(inset, radius - edge / 2, radius - edge / 2, p)
        // İçte renkli kırılma çizgisi (alt-sağda belirgin).
        val inner = u(1f)
        p.strokeWidth = inner
        p.shader = LinearGradient(0f, 0f, w, h, withAlpha(accent, 0f), withAlpha(accent, if (dark) 0.55f else 0.45f), Shader.TileMode.CLAMP)
        val innerRect = RectF(edge + inner / 2, edge + inner / 2, w - edge - inner / 2, h - edge - inner / 2)
        c.drawRoundRect(innerRect, radius - edge, radius - edge, p)
        p.shader = null
        p.strokeWidth = u(0.5f)
        p.color = withAlpha(Color.BLACK, if (dark) 0.35f else 0.05f)
        c.drawRoundRect(innerRect, radius - edge, radius - edge, p)
    }

    /** Başlık: parlayan renk noktası, ad, rozet; sağda (uyarı +) yenile simgesi. Alt kenarı döner. */
    private fun header(
        c: Canvas, w: Float, title: String, accent: Int,
        chip: String?, chipColor: Int?, stale: Boolean, refreshing: Boolean,
    ): Float {
        val top = u(PAD)
        val mid = top + u(HEADER_PT) / 2f
        val dotR = u(3.5f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = accent
        p.setShadowLayer(u(2f), 0f, 0f, withAlpha(accent, 0.6f))
        c.drawCircle(u(PAD) + dotR, mid, dotR, p)
        p.clearShadowLayer()

        // Sağdan: yenile simgesi, gerekirse uyarı üçgeni.
        val iconR = u(4.6f)
        val iconCx = w - u(PAD) - iconR
        refreshGlyph(c, iconCx, mid, iconR, if (refreshing) accent else textSecondary, u(1.5f))
        var rightEdge = iconCx - iconR - u(5f)
        if (stale) {
            val t = u(8f)
            warningTriangle(c, rightEdge - t, mid - t / 2, t, orange)
            rightEdge -= t + u(4f)
        }

        val titlePaint = textPaint(12.5f, textPrimary, semibold)
        val titleX = u(PAD) + dotR * 2 + u(4f)
        var x = titleX + drawFitted(c, title, titleX, mid, rightEdge - titleX, titlePaint)
        if (chip != null) {
            val cc = chipColor ?: accent
            val chipPaint = textPaint(9.5f, cc, semibold)
            val padH = u(4.5f)
            val textW = chipPaint.measureText(chip)
            val chipLeft = x + u(4f)
            val chipW = textW + 2 * padH
            if (chipLeft + chipW <= rightEdge) {
                val chipH = u(9.5f) * 1.25f + u(3f)
                val rect = RectF(chipLeft, mid - chipH / 2, chipLeft + chipW, mid + chipH / 2)
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(cc, 0.16f) }
                c.drawRoundRect(rect, chipH / 2, chipH / 2, bg)
                drawCentered(c, chip, chipLeft + padH, mid, chipPaint)
                x = rect.right
            }
        }
        return top + u(HEADER_PT)
    }

    private fun emptyState(c: Canvas, w: Float, top: Float, h: Float, message: String) {
        val p = textPaint(10.5f, textSecondary, regular)
        val words = message.split(" ")
        // İki satıra kadar sar.
        val maxW = w - 2 * u(PAD)
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(next) <= maxW || line.isEmpty()) line = next else {
                lines += line
                line = word
            }
        }
        if (line.isNotEmpty()) lines += line
        val lineH = u(10.5f) * 1.35f
        var y = top + (h - top - u(PAD) - lineH * lines.size) / 2f
        for (l in lines.take(3)) {
            val lw = min(p.measureText(l), maxW)
            drawFitted(c, l, (w - lw) / 2f, y + lineH / 2f, maxW, p)
            y += lineH
        }
    }

    /**
     * Ailenin ortak kadranı: yuvarlatılmış kare halka, ortada sayı, altında
     * isteğe bağlı etiket. `valueText` verilirse ortada yüzde yerine o yazıyor
     * (bakiye: "$2,22"), halka yine `value` kadar doluyor. `marker` (0..100)
     * halkayı dikine kesen kısa çizgi: pencerenin kalan süresi.
     */
    private fun gaugeDial(
        c: Canvas, rect: RectF, value: Double, label: String?, color: Int, numberSize: Float,
        valueText: String? = null, unit: String = "%", unitLeading: Boolean = false, marker: Double? = null,
    ) {
        val lw = rect.width() * 0.11f
        val r = RectF(rect).apply { inset(lw / 2 + u(1f), lw / 2 + u(1f)) }
        val outline = gaugeOutline(r)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lw
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        p.color = withAlpha(color, 0.14f)
        c.drawPath(outline, p)

        val measure = PathMeasure(outline, false)
        val seg = Path()
        measure.getSegment(0f, measure.length * (value / 100).coerceIn(0.004, 1.0).toFloat(), seg, true)
        val sweep = SweepGradient(r.centerX(), r.centerY(), intArrayOf(color, withAlpha(color, 0.65f), color), null)
        sweep.setLocalMatrix(Matrix().apply { setRotate(-90f, r.centerX(), r.centerY()) })
        // Shader'lı boya Paint'in ALFASINI devralır: iz için kalan %14 alfa
        // sıfırlanmazsa dolgu da %14 çizilir (05.10.2026, telefonda halka ve
        // barlar soluk çıktı).
        p.color = Color.BLACK
        p.shader = sweep
        p.setShadowLayer(u(2f), 0f, u(0.5f), withAlpha(color, 0.35f))
        c.drawPath(seg, p)
        p.clearShadowLayer()
        p.shader = null
        // Cam tüp: kalemin ortasında ince ışık çizgisi.
        p.color = withAlpha(Color.WHITE, 0.42f)
        p.strokeWidth = lw * 0.22f
        p.maskFilter = BlurMaskFilter(u(0.6f), BlurMaskFilter.Blur.NORMAL)
        c.drawPath(seg, p)
        p.maskFilter = null

        if (marker != null) {
            // Teğete dik, kalemin iki yanından biraz taşan çizgi.
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            measure.getPosTan(measure.length * (marker / 100).coerceIn(0.0, 1.0).toFloat(), pos, tan)
            val half = lw / 2f + u(3f)
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = withAlpha(textPrimary, 0.85f)
                strokeWidth = u(2f)
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(u(1f), 0f, 0f, withAlpha(Color.BLACK, 0.35f))
            }
            c.drawLine(pos[0] + tan[1] * half, pos[1] - tan[0] * half, pos[0] - tan[1] * half, pos[1] + tan[0] * half, tick)
        }

        // Ortadaki sayı (+ küçük birim), iç çapa sığdırılır. Öndeki para birimi
        // fiyat etiketindeki gibi üst simge, sondaki yüzde işareti tabanda.
        val num = valueText ?: value.roundToInt().toString()
        val numPaint = textPaint(numberSize, textPrimary, semibold)
        val pctPaint = textPaint(numberSize * (if (unitLeading) 0.5f else 0.42f), textSecondary, medium)
        val maxW = rect.width() * 0.66f
        val unitGap = if (unitLeading) u(1f) else 0f
        val total = numPaint.measureText(num) + pctPaint.measureText(unit) + unitGap
        if (total > maxW) {
            val k = max(0.6f, maxW / total)
            numPaint.textSize *= k
            pctPaint.textSize *= k
        }
        val labelPaint = label?.let { textPaint(max(7f, rect.width() / density / s * 0.105f), textSecondary, medium) }
        val numH = numPaint.textSize
        val labelH = labelPaint?.textSize?.times(1.1f) ?: 0f
        val blockTop = rect.centerY() - (numH + labelH) / 2f
        val numW = numPaint.measureText(num) + pctPaint.measureText(unit) + unitGap
        val baseline = blockTop + numH * 0.86f
        var x = rect.centerX() - numW / 2f
        if (unitLeading) {
            c.drawText(unit, x, baseline - numH * 0.3f, pctPaint)
            x += pctPaint.measureText(unit) + unitGap
            c.drawText(num, x, baseline, numPaint)
        } else {
            c.drawText(num, x, baseline, numPaint)
            x += numPaint.measureText(num)
            c.drawText(unit, x, baseline - numH * 0.04f, pctPaint)
        }
        if (label != null && labelPaint != null) {
            labelPaint.letterSpacing = 0.02f
            val text = label.uppercase()
            val lw2 = min(labelPaint.measureText(text), rect.width() * 0.56f)
            drawFitted(c, text, rect.centerX() - lw2 / 2f, blockTop + numH + labelH / 2f, rect.width() * 0.56f, labelPaint)
        }
    }

    /** Üst ortadan saat yönünde başlayan yuvarlatılmış kare (Mac GaugeOutline). */
    private fun gaugeOutline(r: RectF): Path {
        val rad = min(r.width(), r.height()) * 0.23f
        return Path().apply {
            moveTo(r.centerX(), r.top)
            lineTo(r.right - rad, r.top)
            arcTo(RectF(r.right - 2 * rad, r.top, r.right, r.top + 2 * rad), -90f, 90f)
            lineTo(r.right, r.bottom - rad)
            arcTo(RectF(r.right - 2 * rad, r.bottom - 2 * rad, r.right, r.bottom), 0f, 90f)
            lineTo(r.left + rad, r.bottom)
            arcTo(RectF(r.left, r.bottom - 2 * rad, r.left + 2 * rad, r.bottom), 90f, 90f)
            lineTo(r.left, r.top + rad)
            arcTo(RectF(r.left, r.top, r.left + 2 * rad, r.top + 2 * rad), 180f, 90f)
            lineTo(r.centerX(), r.top)
        }
    }

    /**
     * İki katlı ölçüm satırı (Mac StackedBucketRow): üstte ad ve sağda değer,
     * altında satırın eni boyunca bar. Değer önce yerleşiyor, ad kalan yere
     * sığdırılıyor: "%100" hiç kırpılmıyor.
     */
    private fun stackedRow(
        c: Canvas, left: Float, top: Float, right: Float,
        label: String, value: String, fill: Double, color: Int, accent: Int,
        barHeight: Float, fontSize: Float, marker: Double?,
    ) {
        val mid = top + u(ROW_LINE_PT) / 2f
        val valuePaint = textPaint(fontSize + 2f, textPrimary, semibold)
        val valueW = drawFittedRight(c, value, right, mid, (right - left) / 2f, valuePaint)
        drawFitted(c, label, left, mid, right - left - valueW - u(6f), textPaint(fontSize, textSecondary, regular))
        val barTop = top + u(ROW_LINE_PT) + u(ROW_INNER_GAP_PT)
        capsuleBar(
            c, RectF(left, barTop, right, barTop + u(barHeight)), fill, color, withAlpha(accent, 0.13f),
            gradient = true, marker = marker,
        )
    }

    /** Günlük harcama: her gün bir çubuk, en sağdaki bugün (parlak); harcamasız gün izde çentik. */
    private fun dailyChart(c: Canvas, rect: RectF, values: List<Double>, color: Int) {
        val n = values.size
        val gap = if (n > 20) u(1.5f) else u(3f)
        val barW = max(u(1.2f), (rect.width() - gap * (n - 1)) / n)
        // Ölçek pencerenin en pahalı günü.
        val peak = max(values.maxOrNull() ?: 0.0, 0.0001)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((i, v) in values.withIndex()) {
            val today = i == n - 1
            val barH = if (v <= 0) u(2f) else max(u(3f), rect.height() * (v / peak).toFloat())
            val left = rect.left + i * (barW + gap)
            val bar = RectF(left, rect.bottom - barH, left + barW, rect.bottom)
            val alpha = when {
                v <= 0 -> 0.22f
                today -> 1f
                else -> 0.68f
            }
            p.color = Color.BLACK // shader alfası Paint'ten gelir
            p.shader = LinearGradient(0f, bar.top, 0f, bar.bottom, withAlpha(color, alpha), withAlpha(color, alpha * 0.6f), Shader.TileMode.CLAMP)
            if (today && v > 0) p.setShadowLayer(u(2.5f), 0f, 0f, withAlpha(color, 0.7f)) else p.clearShadowLayer()
            val r = min(barW / 2f, u(1.6f))
            c.drawRoundRect(bar, r, r, p)
        }
        p.shader = null
        p.clearShadowLayer()
    }

    /**
     * Kapsül bar: iz + dolan gövde + üst yarıda cam tüp ışığı. `marker`
     * (0..100) barı dikine kesen ince çizgi: pencerenin kalan süresi.
     */
    private fun capsuleBar(
        c: Canvas, rect: RectF, fill: Double, color: Int, track: Int, gradient: Boolean,
        marker: Double? = null,
    ) {
        val r = rect.height() / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = track
        c.drawRoundRect(rect, r, r, p)
        val fw = max(rect.height(), rect.width() * (fill / 100).coerceIn(0.0, 1.0).toFloat())
        val body = RectF(rect.left, rect.top, rect.left + fw, rect.bottom)
        if (gradient) {
            p.color = Color.BLACK // shader alfası Paint'ten gelir; iz alfası taşınmasın
            p.shader = LinearGradient(body.left, 0f, body.right, 0f, withAlpha(color, 0.72f), color, Shader.TileMode.CLAMP)
        } else p.color = color
        c.drawRoundRect(body, r, r, p)
        p.color = Color.BLACK
        val glass = RectF(body.left + u(1.5f), body.top + u(1f), body.right - u(1.5f), body.bottom)
        if (glass.width() > 0) {
            p.shader = LinearGradient(
                0f, glass.top, 0f, glass.bottom,
                intArrayOf(withAlpha(Color.WHITE, 0.55f), withAlpha(Color.WHITE, 0.08f), withAlpha(Color.WHITE, 0f)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            val gr = glass.height() / 2f
            c.drawRoundRect(glass, gr, gr, p)
        }
        p.shader = null
        if (marker != null) {
            // Uçlarda kapsülün yuvarlağına binmesin.
            val x = rect.left + r + (rect.width() - 2 * r) * (marker / 100).coerceIn(0.0, 1.0).toFloat()
            val half = u(1f)
            val tick = RectF(x - half, rect.top - u(3f), x + half, rect.bottom + u(3f))
            p.color = withAlpha(textPrimary, 0.85f)
            p.setShadowLayer(u(1f), 0f, 0f, withAlpha(Color.BLACK, 0.35f))
            c.drawRoundRect(tick, half, half, p)
            p.clearShadowLayer()
        }
    }

    /** ↻: 300°'lik yay ve ucunda ok. */
    private fun refreshGlyph(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, stroke: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        val start = -60f
        val sweep = 290f
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, false, p)
        // Ok ucu yayın başında, saat yönünün tersine bakan küçük üçgen.
        val a = Math.toRadians(start.toDouble())
        val ex = cx + r * Math.cos(a).toFloat()
        val ey = cy + r * Math.sin(a).toFloat()
        val head = r * 0.75f
        p.style = Paint.Style.FILL
        val tri = Path().apply {
            moveTo(ex + head * 0.2f, ey - head * 0.9f)
            lineTo(ex + head * 0.55f, ey + head * 0.35f)
            lineTo(ex - head * 0.75f, ey + head * 0.1f)
            close()
        }
        c.drawPath(tri, p)
    }

    private fun warningTriangle(c: Canvas, left: Float, top: Float, size: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val path = Path().apply {
            moveTo(left + size / 2, top)
            lineTo(left + size, top + size * 0.9f)
            lineTo(left, top + size * 0.9f)
            close()
        }
        c.drawPath(path, p)
        p.color = base
        p.strokeWidth = size * 0.13f
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(left + size / 2, top + size * 0.32f, left + size / 2, top + size * 0.58f, p)
        c.drawCircle(left + size / 2, top + size * 0.74f, size * 0.07f, p)
    }

    private fun textPaint(sizePt: Float, color: Int, face: Typeface): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            typeface = face
            textSize = u(sizePt)
            fontFeatureSettings = "tnum"
        }

    /**
     * Metni dikey ortası `midY` olacak şekilde yazar; sığmazsa %60'a kadar
     * küçültür, yine sığmazsa üç noktayla keser. Çizilen genişliği döner.
     */
    private fun drawFitted(c: Canvas, text: String, x: Float, midY: Float, maxWidth: Float, paint: Paint): Float {
        if (maxWidth <= 0f || text.isEmpty()) return 0f
        val original = paint.textSize
        var w = paint.measureText(text)
        if (w > maxWidth) {
            paint.textSize = max(original * 0.6f, original * maxWidth / w)
            w = paint.measureText(text)
        }
        var out = text
        if (w > maxWidth) {
            var n = text.length
            while (n > 1 && paint.measureText(text.take(n) + "…") > maxWidth) n--
            out = text.take(n) + "…"
            w = paint.measureText(out)
        }
        val fm = paint.fontMetrics
        c.drawText(out, x, midY - (fm.ascent + fm.descent) / 2f, paint)
        paint.textSize = original
        return w
    }

    /** `drawFitted`in sağa yaslı eşi: metnin sağ kenarı `right`. Çizilen genişliği döner. */
    private fun drawFittedRight(c: Canvas, text: String, right: Float, midY: Float, maxWidth: Float, paint: Paint): Float {
        val original = paint.textSize
        var w = paint.measureText(text)
        if (w > maxWidth) {
            paint.textSize = max(original * 0.6f, original * maxWidth / w)
            w = min(paint.measureText(text), maxWidth)
        }
        val drawn = drawFitted(c, text, right - w, midY, maxWidth, paint)
        paint.textSize = original
        return drawn
    }

    private fun drawCentered(c: Canvas, text: String, x: Float, midY: Float, paint: Paint) {
        val fm = paint.fontMetrics
        c.drawText(text, x, midY - (fm.ascent + fm.descent) / 2f, paint)
    }

    companion object {
        /** Mac küçük kartının kenarı (pt). */
        private const val CARD_PT = 170f
        private const val PAD = 14f
        private const val HEADER_PT = 16f
        private const val CORNER_PT = 22f
        /** İki katlı satırın üst katı (13,5 pt değer) ve bara olan aralığı. */
        private const val ROW_LINE_PT = 15f
        private const val ROW_INNER_GAP_PT = 3.5f

        /** Anthropic turuncusu (Mac UsageModel.swift `.claude`). */
        val CLAUDE_ACCENT = Color.rgb(217, 112, 61)
        /** Limon yeşili (Mac NanoGPTModel.swift `nanoAccent`). */
        val NANO_ACCENT = Color.rgb(140, 209, 71)
        /** 10 $ üstü: gök mavisi (`nanoOverflowAccent`). */
        val NANO_OVERFLOW = Color.rgb(89, 199, 250)

        fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))
    }
}
