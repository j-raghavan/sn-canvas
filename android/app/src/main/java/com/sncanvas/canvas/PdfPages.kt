package com.sncanvas.canvas

import kotlin.math.ceil
import kotlin.math.min

/**
 * The page "Export to PDF" draws the canvas on (FR11): one page fitted to all
 * of its content with a [MARGIN] round it, so a canvas of any shape exports
 * whole. A world unit prints at [POINTS_PER_UNIT], a screen pixel's size on
 * paper (96 to the inch), so the PDF shows the canvas at 100%; content too
 * big for a PDF page, whose sides stop at [MAX_SIDE_PT] (200 inches), is
 * scaled down to fit. An empty canvas exports a blank A4 page.
 */
object PdfPages {
    /** The margin round the content, in world units. */
    const val MARGIN = 48.0

    const val POINTS_PER_UNIT = 0.75
    const val MAX_SIDE_PT = 14_400.0
    private const val A4_WIDTH_PT = 595
    private const val A4_HEIGHT_PT = 842

    /** A [widthPt] × [heightPt] page, in PDF points, that the canvas draws on through [transform]. */
    data class Page(
        val widthPt: Int,
        val heightPt: Int,
        val transform: ViewTransform,
    )

    /** The page fitted to [elements]. */
    fun fitted(elements: List<Element>): Page {
        val bounds =
            ViewTransforms.contentBounds(elements) ?: return Page(A4_WIDTH_PT, A4_HEIGHT_PT, ViewTransform(0.0, 0.0, POINTS_PER_UNIT))
        val width = bounds.width + 2 * MARGIN
        val height = bounds.height + 2 * MARGIN
        val scale = min(POINTS_PER_UNIT, min(MAX_SIDE_PT / width, MAX_SIDE_PT / height))
        return Page(
            ceil(width * scale).toInt(),
            ceil(height * scale).toInt(),
            ViewTransform(bounds.left - MARGIN, bounds.top - MARGIN, scale),
        )
    }
}
