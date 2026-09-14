package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

/** The PDF page (FR11): fitted to the content with a margin, at 100%, scaled down only past a PDF page's limit. */
class PdfPagesTest {
    @Test
    fun `an empty canvas exports a blank A4 page`() {
        assertEquals(PdfPages.Page(595, 842, ViewTransform(0.0, 0.0, 0.75)), PdfPages.fitted(emptyList()))
    }

    @Test
    fun `the page fits the content and its margin, at a screen pixel's size on paper`() {
        val box = Element(id = "b", type = "rectangle", x = 100.0, y = 50.0, width = 400.0, height = 300.0)
        val page = PdfPages.fitted(listOf(box))
        // (400 + 2 × 48) × 0.75 by (300 + 2 × 48) × 0.75 points.
        assertEquals(372, page.widthPt)
        assertEquals(297, page.heightPt)
        assertEquals(ViewTransform(52.0, 2.0, 0.75), page.transform)
        // The content starts one margin in from the page's edge.
        assertEquals(PdfPages.MARGIN * 0.75, page.transform.screenX(box.x), 1e-9)
    }

    @Test
    fun `content too big for a PDF page is scaled down to the largest page there is`() {
        val long = Element(id = "l", type = "rectangle", x = 0.0, y = 0.0, width = 100_000.0, height = 10.0)
        val page = PdfPages.fitted(listOf(long))
        assertEquals(PdfPages.MAX_SIDE_PT.toInt(), page.widthPt)
        assertEquals(PdfPages.MAX_SIDE_PT / (100_000.0 + 2 * PdfPages.MARGIN), page.transform.zoom, 1e-12)
    }

    @Test
    fun `a rotated shape's page spans its rotated corners`() {
        val page = PdfPages.fitted(listOf(barRotated90))
        // A 100 × 20 bar turned upright spans 20 × 100.
        assertEquals(87, page.widthPt)
        assertEquals(147, page.heightPt)
    }
}
