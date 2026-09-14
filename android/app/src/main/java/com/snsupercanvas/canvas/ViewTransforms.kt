package com.snsupercanvas.canvas

/**
 * Fit transforms: the [ViewTransform] that frames a set of elements (and, for the
 * minimap, the visible viewport too) inside a box. The note thumbnail and the
 * minimap both draw through these, reusing the live view's draw routines.
 */
object ViewTransforms {
    /**
     * Fallback world-space span used when content has zero width/height
     * (e.g. a single point), to avoid a divide-by-zero/infinite scale.
     */
    private const val MIN_CONTENT_SPAN = 100.0

    /** The identity for [WorldRect.union]: inside-out, so the first union yields the other rect unchanged. */
    private val NO_BOUNDS =
        WorldRect(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)

    /**
     * The (viewportX, viewportY, zoom) transform that fits [elements]' combined
     * bounding box into a [thumbnailSizePx] square, centered, with [paddingPx]
     * breathing room on every side — in exactly the shape [SuperCanvasView]'s
     * existing draw routines already expect (`screenX = (worldX - viewportX) *
     * zoom`), so rendering a thumbnail is "call the same draw functions with a
     * different transform," not a second renderer (FR12).
     *
     * An empty [elements] list returns an arbitrary identity-ish transform —
     * nothing will be drawn, so its exact values don't matter; it exists so a
     * "Save to Note" on a blank canvas still produces a valid (blank)
     * thumbnail rather than failing.
     */
    fun computeThumbnailTransform(
        elements: List<Element>,
        thumbnailSizePx: Double,
        paddingPx: Double,
    ): ViewTransform {
        val bounds = contentBounds(elements) ?: return ViewTransform(viewportX = 0.0, viewportY = 0.0, zoom = 1.0)
        return fitTransform(bounds, thumbnailSizePx, thumbnailSizePx, paddingPx)
    }

    /**
     * The minimap's transform: fits the union of all content *and* the currently
     * [visible] world rect into the minimap box, so the "you are here" rectangle
     * stays inside the minimap however far the user has panned away from every
     * element. With no content it fits the visible rect alone.
     */
    fun computeMinimapTransform(
        elements: List<Element>,
        visible: WorldRect,
        boxWidthPx: Double,
        boxHeightPx: Double,
        paddingPx: Double,
    ): ViewTransform {
        val content = contentBounds(elements)
        val world = if (content == null) visible else content.union(visible)
        return fitTransform(world, boxWidthPx, boxHeightPx, paddingPx)
    }

    /**
     * Fits [world] into a [boxWidthPx] x [boxHeightPx] box, centered, with
     * [paddingPx] on every side, using the more constraining axis. Shared by
     * the note thumbnail (a square box) and the minimap (a box shaped like the
     * view). A zero-size [world] axis falls back to [MIN_CONTENT_SPAN].
     */
    fun fitTransform(
        world: WorldRect,
        boxWidthPx: Double,
        boxHeightPx: Double,
        paddingPx: Double,
        zoomRange: ClosedFloatingPointRange<Double> = 0.0..Double.MAX_VALUE,
    ): ViewTransform {
        val contentWidth = world.width.let { if (it > 0.0) it else MIN_CONTENT_SPAN }
        val contentHeight = world.height.let { if (it > 0.0) it else MIN_CONTENT_SPAN }
        val availableWidth = (boxWidthPx - 2 * paddingPx).coerceAtLeast(1.0)
        val availableHeight = (boxHeightPx - 2 * paddingPx).coerceAtLeast(1.0)
        val zoom = minOf(availableWidth / contentWidth, availableHeight / contentHeight).coerceIn(zoomRange)
        // Centered on the content's center, so a zero-width/height extent (a straight line) sits mid-box too.
        val centerX = (world.left + world.right) / 2
        val centerY = (world.top + world.bottom) / 2
        return ViewTransform(viewportX = centerX - boxWidthPx / (2 * zoom), viewportY = centerY - boxHeightPx / (2 * zoom), zoom = zoom)
    }

    /**
     * Where the live view looks when a canvas opens (FR13's "sane default view"):
     * all content fitted into the [viewWidthPx] x [viewHeightPx] view with
     * [paddingPx] around it. It zooms out as far as [SuperCanvasCore.MIN_ZOOM]
     * when the content needs it, but never magnifies small content past 100%.
     * An empty canvas opens at the origin, at 100%.
     */
    fun fitToView(
        elements: List<Element>,
        viewWidthPx: Double,
        viewHeightPx: Double,
        paddingPx: Double,
    ): ViewTransform {
        val bounds = contentBounds(elements) ?: return ViewTransform(viewportX = 0.0, viewportY = 0.0, zoom = 1.0)
        return fitTransform(bounds, viewWidthPx, viewHeightPx, paddingPx, SuperCanvasCore.MIN_ZOOM..1.0)
    }

    /** The combined world-space bounding box of [elements] (bound line endpoints resolved live), or null if empty. */
    fun contentBounds(elements: List<Element>): WorldRect? {
        if (elements.isEmpty()) return null
        val points =
            elements.flatMap { element ->
                if (element.hasEndpoints()) {
                    SuperCanvasCore.resolveArrowEndpoints(element, elements).toList()
                } else {
                    // Corners, not x/y/width/height: a rotated shape's extent is its rotated corners'.
                    SuperCanvasCore.cornerPoints(element)
                }
            }
        return boundsOf(points)
    }

    /** The smallest rect holding every one of [points]; inside-out (and so empty) for none. */
    fun boundsOf(points: List<Point>): WorldRect = points.fold(NO_BOUNDS) { bounds, p -> bounds.union(WorldRect(p.x, p.y, p.x, p.y)) }
}
