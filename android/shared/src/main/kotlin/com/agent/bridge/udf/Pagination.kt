package com.agent.bridge.udf

/** A page window expressed in the same document-space pixels as the measured lines. */
data class PageSlice(val startY: Float, val endY: Float)

/**
 * Groups measured, unbreakable line/row units into page windows. A break is
 * always placed at a unit boundary, so a page never cuts through a glyph or a
 * table row. This is intentionally UI-independent so the regression corpus can
 * lock the page-boundary behavior with ordinary JVM unit tests.
 */
fun paginateLineUnits(
    lines: List<Pair<Float, Float>>,
    pageContentHeightPx: Float
): List<PageSlice> {
    require(pageContentHeightPx > 0f) { "pageContentHeightPx must be positive" }
    if (lines.isEmpty()) return listOf(PageSlice(0f, pageContentHeightPx))

    val slices = ArrayList<PageSlice>()
    var pageStart = lines.first().first
    var pageEnd = pageStart

    for ((top, bottom) in lines) {
        val lineFits = (bottom - pageStart <= pageContentHeightPx + 0.5f) ||
            (bottom - top >= pageContentHeightPx)
        if (pageEnd > pageStart && !lineFits) {
            slices.add(PageSlice(pageStart, pageEnd))
            pageStart = top
        }
        pageEnd = bottom
    }
    slices.add(PageSlice(pageStart, pageEnd))
    return slices
}
