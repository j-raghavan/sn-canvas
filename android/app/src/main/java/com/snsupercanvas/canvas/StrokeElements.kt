package com.snsupercanvas.canvas

import kotlin.math.hypot

/**
 * Freehand strokes (FR5): pen samples become an element, and its points come
 * back as world positions for drawing and hit-testing. Points are stored
 * normalized to the stroke's bounds, so moving, resizing and rotating it are
 * the bbox operations every shape already has.
 */
object StrokeElements {
    // Samples closer than this (world units) to the last one kept add nothing but weight.
    private const val MIN_SAMPLE_DISTANCE = 0.75

    /**
     * A pencil line's width on screen as it is drawn, in pixels: the nib of the
     * firmware's needle pen, which inks the stroke live (measured by inkread on
     * this device). A stroke keeps it in world units, so it matches the live ink
     * at the zoom it was drawn at, and scales with the canvas like ink on paper.
     */
    const val PEN_WIDTH_PX = 9.0

    /** A stroke from world-space [samples] (x, y, pressure) drawn at [zoom], thinned and lightly smoothed; null with no samples. */
    fun fromSamples(
        id: String,
        samples: List<StrokePoint>,
        zoom: Double = 1.0,
    ): Element? {
        if (samples.isEmpty()) return null
        val points = smooth(thin(samples))
        val bounds = ViewTransforms.boundsOf(points.map { Point(it.x, it.y) })
        return Element(
            id = id,
            type = CanvasTools.DRAW,
            x = bounds.left,
            y = bounds.top,
            width = bounds.width,
            height = bounds.height,
            points =
                points.map {
                    StrokePoint(
                        normalize(it.x, bounds.left, bounds.width),
                        normalize(it.y, bounds.top, bounds.height),
                        it.pressure,
                    )
                },
            strokeWidth = PEN_WIDTH_PX / zoom,
        )
    }

    /** [element]'s points in world space, in its unrotated frame. */
    fun worldPoints(element: Element): List<StrokePoint> =
        element.points.orEmpty().map { StrokePoint(element.x + it.x * element.width, element.y + it.y * element.height, it.pressure) }

    /** True when the world point lies within [tolerance] of the stroke's line (not merely inside its bounds). */
    fun isNear(
        element: Element,
        worldX: Double,
        worldY: Double,
        tolerance: Double,
    ): Boolean {
        val local = element.toLocal(worldX, worldY)
        val points = worldPoints(element).map { Point(it.x, it.y) }
        return if (points.size == 1) {
            hypot(local.x - points[0].x, local.y - points[0].y) <= tolerance
        } else {
            points.zipWithNext().any { (a, b) -> SuperCanvasCore.distanceToSegment(local, a, b) <= tolerance }
        }
    }

    private fun normalize(
        value: Double,
        min: Double,
        span: Double,
    ): Double = if (span == 0.0) 0.0 else (value - min) / span

    /** Drops samples that barely moved, keeping the first and the last (where the pen lifted). */
    private fun thin(samples: List<StrokePoint>): List<StrokePoint> {
        val kept = mutableListOf(samples.first())
        for (sample in samples.drop(1)) {
            val last = kept.last()
            if (hypot(sample.x - last.x, sample.y - last.y) >= MIN_SAMPLE_DISTANCE) kept += sample
        }
        if (kept.last() !== samples.last()) kept += samples.last()
        return kept
    }

    /** A light 1-2-1 average of each interior point with its neighbours; the ends stay where the pen was. */
    private fun smooth(points: List<StrokePoint>): List<StrokePoint> =
        points.mapIndexed { index, point ->
            if (index == 0 || index == points.lastIndex) {
                point
            } else {
                val before = points[index - 1]
                val after = points[index + 1]
                StrokePoint((before.x + 2 * point.x + after.x) / 4, (before.y + 2 * point.y + after.y) / 4, point.pressure)
            }
        }
}
