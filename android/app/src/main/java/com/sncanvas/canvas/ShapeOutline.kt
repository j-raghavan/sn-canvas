package com.sncanvas.canvas

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Shape outlines as polylines in world units: what the renderer strokes and
 * fills for the hand-drawn dash (FR19), and where the FR17 geo shapes will
 * join. Bbox outlines are in the element's unrotated frame; the renderer
 * applies the rotation.
 */
object ShapeOutline {
    private const val ELLIPSE_SEGMENTS = 64

    // Hand-drawn wobble: a new sample every WOBBLE_STEP along the outline,
    // following two sines of incommensurate wavelengths so it never visibly repeats.
    private const val WOBBLE_STEP = 16.0
    private const val WOBBLE_WAVELENGTH = 60.0
    private const val WOBBLE_SECOND_WAVELENGTH = 23.0
    private const val WOBBLE_MIX = 0.65

    /** A bbox element's closed outline (its first point is not repeated at the end). */
    fun of(element: Element): List<Point> = if (element.type == CanvasTools.ELLIPSE) ellipse(element) else rectangle(element)

    private fun rectangle(e: Element): List<Point> =
        listOf(Point(e.x, e.y), Point(e.x + e.width, e.y), Point(e.x + e.width, e.y + e.height), Point(e.x, e.y + e.height))

    private fun ellipse(e: Element): List<Point> {
        val cx = e.x + e.width / 2
        val cy = e.y + e.height / 2
        return (0 until ELLIPSE_SEGMENTS).map { i ->
            val angle = 2 * PI * i / ELLIPSE_SEGMENTS
            Point(cx + e.width / 2 * cos(angle), cy + e.height / 2 * sin(angle))
        }
    }

    /**
     * The hand-drawn look (DashStyle.DRAW): [points] subdivided and nudged
     * sideways by up to [amplitude], smoothly, following a wobble seeded by
     * [seed] (the element's id), so a shape wobbles the same way on every
     * redraw. A [closed] outline is walked back to its first point.
     */
    fun handDrawn(
        points: List<Point>,
        closed: Boolean,
        seed: Int,
        amplitude: Double,
    ): List<Point> {
        if (points.size < 2) return points
        val path = if (closed) points + points.first() else points
        val phase1 = (seed and 0xFFFF) / 65_535.0 * 2 * PI
        val phase2 = (seed ushr 16) / 65_535.0 * 2 * PI
        val result = mutableListOf<Point>()
        var travelled = 0.0
        var normal = Point(0.0, 0.0)
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val length = hypot(b.x - a.x, b.y - a.y)
            normal = if (length == 0.0) normal else Point(-(b.y - a.y) / length, (b.x - a.x) / length)
            val steps = ceil(length / WOBBLE_STEP).toInt().coerceAtLeast(1)
            for (step in 0 until steps) {
                val t = step.toDouble() / steps
                val offset = amplitude * wobble(travelled + length * t, phase1, phase2)
                result += Point(a.x + (b.x - a.x) * t + normal.x * offset, a.y + (b.y - a.y) * t + normal.y * offset)
            }
            travelled += length
        }
        val last = path.last()
        val offset = amplitude * wobble(travelled, phase1, phase2)
        result += Point(last.x + normal.x * offset, last.y + normal.y * offset)
        return result
    }

    /** A smooth value in -1..1 along the outline. */
    private fun wobble(
        distance: Double,
        phase1: Double,
        phase2: Double,
    ): Double =
        WOBBLE_MIX * sin(distance / WOBBLE_WAVELENGTH + phase1) +
            (1 - WOBBLE_MIX) * sin(distance / WOBBLE_SECOND_WAVELENGTH + phase2)
}
