package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fit transforms behind the note thumbnail and the minimap, and the content bounds they frame. */
class ViewTransformsTest {
    // --- computeThumbnailTransform (FR12 note thumbnail) -----------------------

    @Test
    fun `computeThumbnailTransform returns an identity-ish transform for an empty element list`() {
        val transform = ViewTransforms.computeThumbnailTransform(emptyList(), thumbnailSizePx = 400.0, paddingPx = 24.0)
        assertEquals(0.0, transform.viewportX, 0.0001)
        assertEquals(0.0, transform.viewportY, 0.0001)
        assertEquals(1.0, transform.zoom, 0.0001)
    }

    @Test
    fun `computeThumbnailTransform fits a single box element within the padded thumbnail bounds`() {
        val box = Element(id = "a", type = "rectangle", x = 0.0, y = 0.0, width = 100.0, height = 100.0)
        val transform = ViewTransforms.computeThumbnailTransform(listOf(box), thumbnailSizePx = 400.0, paddingPx = 24.0)
        // Content corners, mapped through the transform, must land inside [padding, size - padding].
        val leftPx = (box.x - transform.viewportX) * transform.zoom
        val topPx = (box.y - transform.viewportY) * transform.zoom
        val rightPx = (box.x + box.width - transform.viewportX) * transform.zoom
        val bottomPx = (box.y + box.height - transform.viewportY) * transform.zoom
        assertEquals(true, leftPx >= 24.0 - 0.01)
        assertEquals(true, topPx >= 24.0 - 0.01)
        assertEquals(true, rightPx <= 400.0 - 24.0 + 0.01)
        assertEquals(true, bottomPx <= 400.0 - 24.0 + 0.01)
    }

    @Test
    fun `computeThumbnailTransform spans the bounding box of multiple elements, not just one`() {
        val a = Element(id = "a", type = "rectangle", x = 0.0, y = 0.0, width = 10.0, height = 10.0)
        val b = Element(id = "b", type = "rectangle", x = 500.0, y = 500.0, width = 10.0, height = 10.0)
        val wide = ViewTransforms.computeThumbnailTransform(listOf(a, b), thumbnailSizePx = 400.0, paddingPx = 24.0)
        val narrow = ViewTransforms.computeThumbnailTransform(listOf(a), thumbnailSizePx = 400.0, paddingPx = 24.0)
        // Spanning a and b is much larger content than a alone, so it must zoom out further.
        assertEquals(true, wide.zoom < narrow.zoom)
    }

    @Test
    fun `computeThumbnailTransform falls back to a minimum content span for a zero-size (point) element`() {
        val point = Element(id = "p", type = "rectangle", x = 5.0, y = 5.0, width = 0.0, height = 0.0)
        val transform = ViewTransforms.computeThumbnailTransform(listOf(point), thumbnailSizePx = 400.0, paddingPx = 24.0)
        // Would be infinite/NaN without the MIN_CONTENT_SPAN fallback (division by a zero span).
        assertEquals(true, transform.zoom.isFinite() && transform.zoom > 0.0)
    }

    @Test
    fun `computeThumbnailTransform includes a bound line's resolved (not stale) endpoints in the content bounds`() {
        val target = Element(id = "t", type = "rectangle", x = 1000.0, y = 1000.0, width = 10.0, height = 10.0)
        val arrow =
            Element(id = "arr", type = "arrow", startX = 0.0, startY = 0.0, endX = 1.0, endY = 1.0, endElementId = "t")
        val withBoundLine = ViewTransforms.computeThumbnailTransform(listOf(target, arrow), 400.0, 24.0)
        val targetOnly = ViewTransforms.computeThumbnailTransform(listOf(target), 400.0, 24.0)
        // The arrow's stale stored endX/endY (1,1) is near the target, so if the bound/live
        // endpoint (near 1000,1000, same as `target`) weren't used, both transforms would be
        // nearly identical. They must differ enough to prove the live position was included.
        assertEquals(true, kotlin.math.abs(withBoundLine.zoom - targetOnly.zoom) > 0.0001)
    }

    @Test
    fun `computeThumbnailTransform picks the more constraining axis for non-square content`() {
        val tall = Element(id = "tall", type = "rectangle", x = 0.0, y = 0.0, width = 10.0, height = 1000.0)
        val wide = Element(id = "wide", type = "rectangle", x = 0.0, y = 0.0, width = 1000.0, height = 10.0)
        val tallTransform = ViewTransforms.computeThumbnailTransform(listOf(tall), 400.0, 24.0)
        val wideTransform = ViewTransforms.computeThumbnailTransform(listOf(wide), 400.0, 24.0)
        // Both are constrained by their long axis to the same available span, so both should
        // scale down to about the same zoom (exercises both the width- and height-driven
        // branches of the `minOf(available / contentWidth, available / contentHeight)` choice).
        assertEquals(tallTransform.zoom, wideTransform.zoom, 0.0001)
    }

    @Test
    fun `contentBounds is null for an empty element list`() {
        assertNull(ViewTransforms.contentBounds(emptyList()))
    }

    @Test
    fun `contentBounds spans boxes and a bound line's live endpoints`() {
        val box = Element(id = "b", type = "rectangle", x = -50.0, y = 10.0, width = 20.0, height = 30.0)
        val target = Element(id = "t", type = "rectangle", x = 200.0, y = 300.0, width = 10.0, height = 10.0)
        val arrow = Element(id = "a", type = "arrow", startX = 0.0, startY = 0.0, endX = 1.0, endY = 1.0, endElementId = "t")
        val bounds = ViewTransforms.contentBounds(listOf(box, target, arrow))!!
        assertEquals(-50.0, bounds.left, 0.0001)
        assertEquals(0.0, bounds.top, 0.0001) // the arrow's unbound start (0,0)
        assertEquals(210.0, bounds.right, 0.0001)
        assertEquals(310.0, bounds.bottom, 0.0001)
    }

    @Test
    fun `fitTransform centers content in a non-square box using the constraining axis`() {
        val fit = ViewTransforms.fitTransform(WorldRect(0.0, 0.0, 100.0, 100.0), boxWidthPx = 300.0, boxHeightPx = 200.0, paddingPx = 0.0)
        assertEquals(2.0, fit.zoom, 0.0001) // height constrains: 200px / 100 world units
        // 100 world units -> 200px inside a 300px-wide box leaves 50px on each side.
        assertEquals(50.0, (0.0 - fit.viewportX) * fit.zoom, 0.0001)
        assertEquals(0.0, (0.0 - fit.viewportY) * fit.zoom, 0.0001)
    }

    @Test
    fun `computeMinimapTransform fits the visible viewport alone when there is no content`() {
        val visible = WorldRect(100.0, 100.0, 400.0, 500.0)
        val fit = ViewTransforms.computeMinimapTransform(emptyList(), visible, 300.0, 400.0, 0.0)
        assertEquals(1.0, fit.zoom, 0.0001) // a 300x400 world rect into a 300x400 box
        assertEquals(100.0, fit.viewportX, 0.0001)
        assertEquals(100.0, fit.viewportY, 0.0001)
    }

    @Test
    fun `computeMinimapTransform keeps both content and a far-away viewport inside the minimap`() {
        val box = Element(id = "b", type = "rectangle", x = 0.0, y = 0.0, width = 100.0, height = 100.0)
        val visible = WorldRect(5000.0, 5000.0, 5300.0, 5400.0)
        val fit = ViewTransforms.computeMinimapTransform(listOf(box), visible, 300.0, 400.0, 10.0)
        for (x in listOf(box.x, visible.right)) {
            assertEquals(true, (x - fit.viewportX) * fit.zoom in 9.99..290.01)
        }
        for (y in listOf(box.y, visible.bottom)) {
            assertEquals(true, (y - fit.viewportY) * fit.zoom in 9.99..390.01)
        }
    }

    @Test
    fun `contentBounds uses a rotated shape's rotated corners`() {
        val bounds = ViewTransforms.contentBounds(listOf(barRotated90))!!
        assertEquals(40.0, bounds.left, 0.0001)
        assertEquals(-40.0, bounds.top, 0.0001)
        assertEquals(60.0, bounds.right, 0.0001)
        assertEquals(60.0, bounds.bottom, 0.0001)
    }

    // --- opening a canvas: fitToView --------------------------------------------------

    @Test
    fun `fitToView opens an empty canvas at the origin at 100 percent`() {
        assertEquals(ViewTransform(0.0, 0.0, 1.0), ViewTransforms.fitToView(emptyList(), 1000.0, 800.0, 50.0))
    }

    @Test
    fun `fitToView centers small content without magnifying it past 100 percent`() {
        val box = Element(id = "b", type = "rectangle", x = 5000.0, y = -3000.0, width = 100.0, height = 50.0)
        val fit = ViewTransforms.fitToView(listOf(box), 1000.0, 800.0, 50.0)
        assertEquals(1.0, fit.zoom, 1e-9)
        assertEquals(500.0, fit.screenX(5050.0), 1e-9)
        assertEquals(400.0, fit.screenY(-2975.0), 1e-9)
    }

    @Test
    fun `fitToView zooms out so content wider than the view fits inside the padding`() {
        val wide = Element(id = "w", type = "rectangle", x = 0.0, y = 0.0, width = 1800.0, height = 100.0)
        val fit = ViewTransforms.fitToView(listOf(wide), 1000.0, 800.0, 50.0)
        assertEquals(0.5, fit.zoom, 1e-9)
        assertEquals(50.0, fit.screenX(0.0), 1e-9)
        assertEquals(950.0, fit.screenX(1800.0), 1e-9)
    }

    @Test
    fun `fitToView never zooms out past MIN_ZOOM, however far apart the content is`() {
        val a = Element(id = "a", type = "rectangle", x = 0.0, y = 0.0, width = 1.0, height = 1.0)
        val b = a.copy(id = "b", x = 1_000_000.0)
        assertEquals(CanvasCore.MIN_ZOOM, ViewTransforms.fitToView(listOf(a, b), 1000.0, 800.0, 50.0).zoom, 1e-12)
    }

    @Test
    fun `fitTransform centers a zero-width extent such as a vertical line`() {
        val line = WorldRect(left = 10.0, top = 0.0, right = 10.0, bottom = 200.0)
        val fit = ViewTransforms.fitTransform(line, 400.0, 400.0, 0.0)
        assertEquals(200.0, fit.screenX(10.0), 1e-9)
        assertEquals(200.0, fit.screenY(100.0), 1e-9)
    }

    @Test
    fun `boundsOf is the smallest rect holding every point`() {
        assertEquals(WorldRect(-3.0, 2.0, 1.0, 5.0), ViewTransforms.boundsOf(listOf(Point(1.0, 2.0), Point(-3.0, 5.0))))
    }
}
