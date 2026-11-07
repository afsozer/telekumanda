package com.agent.bridge

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.text.Spannable
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import io.noties.markwon.ext.tables.TableRowSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MarkdownLinkTouchTest {
    private val opened = mutableListOf<String>()
    private lateinit var activity: ActivityController<Activity>

    @After
    fun tearDown() {
        if (::activity.isInitialized) activity.pause().stop().destroy()
    }

    @Test
    fun tableWebLinkOpensBrowserWithTheFullUrl() {
        val url = "https://www.trendyol.com/urun-p-1200082603?merchantId=1119255"
        val view = render("| Ürün | Fiyat |\n| --- | --- |\n| [Trendyol]($url) | 587 TL |")
        tapCell(view, rowIndex = 1, column = 0)
        val intent = shadowOf(activity.get()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent?.action)
        assertEquals(url, intent?.dataString)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun linksInBothTableColumnsOpenTheirOwnTarget() {
        val view = render("""
            | Ürün | Alternatif |
            | --- | --- |
            | [Amazon](agfile://amazon.txt) | [Trendyol](agfile://trendyol.txt) |
        """.trimIndent())
        tapCell(view, rowIndex = 1, column = 0)
        tapCell(view, rowIndex = 1, column = 1)
        assertEquals(listOf("amazon.txt", "trendyol.txt"), opened)
    }

    @Test
    fun tableHeaderLinkWorksAndPlainCellDoesNotOpenLink() {
        val view = render("""
            | [Kaynak](agfile://kaynak.txt) | Fiyat |
            | --- | --- |
            | Düz metin | 299 TL |
        """.trimIndent())
        tapCell(view, rowIndex = 0, column = 0)
        tapCell(view, rowIndex = 1, column = 1)
        assertEquals(listOf("kaynak.txt"), opened)
    }

    @Test
    fun ordinaryLinksAndTextSelectionRemainAvailable() {
        val view = render("Liste: [Ürün](agfile://urun.txt)")
        val buffer = view.text as Spanned
        val link = buffer.getSpans(0, buffer.length, ClickableSpan::class.java).single()
        val offset = buffer.getSpanStart(link) + 1
        val line = view.layout.getLineForOffset(offset)
        tap(view, view.layout.getPrimaryHorizontal(offset), view.layout.getLineBaseline(line).toFloat())
        assertEquals(listOf("urun.txt"), opened)
        assertTrue(view.isTextSelectable)
        assertTrue(view.movementMethod.canSelectArbitrarily())
    }

    private fun render(markdown: String): TextView {
        activity = Robolectric.buildActivity(Activity::class.java).setup()
        val view = TextView(activity.get()).apply {
            textSize = 16f
            setPadding(12, 8, 12, 8)
            setTextIsSelectable(true)
            movementMethod = SelectableLinkMovementMethod
        }
        buildMarkwon(view.context, opened::add).setMarkdown(view, markdown)
        view.movementMethod = SelectableLinkMovementMethod
        val bitmap = Bitmap.createBitmap(720, 1000, Bitmap.Config.ARGB_8888)
        try {
            // TableRowSpan builds its cell layouts on draw and then requests remeasurement.
            repeat(3) {
                view.forceLayout()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                view.draw(Canvas(bitmap))
                shadowOf(Looper.getMainLooper()).idle()
            }
        } finally {
            bitmap.recycle()
        }
        return view
    }

    private fun tapCell(view: TextView, rowIndex: Int, column: Int) {
        val buffer = view.text as Spanned
        val rows = buffer.getSpans(0, buffer.length, TableRowSpan::class.java)
            .sortedBy { buffer.getSpanStart(it) }
        val row = rows[rowIndex]
        val line = view.layout.getLineForOffset(buffer.getSpanStart(row))
        val cell = requireNotNull(row.findLayoutForHorizontalOffset(column * row.cellWidth() + 1))
        tap(view, column * row.cellWidth() + 16f, view.layout.getLineTop(line) + cell.getLineBaseline(0).toFloat())
    }

    private fun tap(view: TextView, x: Float, y: Float) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 10, action, x + view.totalPaddingLeft, y + view.totalPaddingTop, 0)
            try {
                view.movementMethod.onTouchEvent(view, view.text as Spannable, event)
            } finally {
                event.recycle()
            }
        }
    }
}
