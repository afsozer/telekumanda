package com.agent.bridge.udf

/** Shared UDF point/centimetre constants. UDF stores page and tab positions in points. */
object UdfUnits {
    /** The existing UDF page-format convention used by this project. */
    const val POINTS_PER_CM = 28.35f
    const val DEFAULT_MARGIN_PT = 56.7f

    /** Official template default tab distance: 2.54 cm at 72 pt/inch. */
    const val DEFAULT_TAB_INTERVAL_PT = 72f

    /** Official UDF-to-ODF paragraph-height base: 0.2 inch = 14.4 pt. */
    const val LINE_HEIGHT_BASE_PT = 14.4f

    /** Official paragraph height: (LineSpacing + 1) × 0.2 inch. */
    fun lineHeightPoints(lineSpacing: Float?): Float =
        ((lineSpacing ?: 0f) + 1f) * LINE_HEIGHT_BASE_PT

    fun cmToPoints(cm: Float): Float = cm * POINTS_PER_CM

    fun pointsToCm(points: Float): Float = points / POINTS_PER_CM
}
