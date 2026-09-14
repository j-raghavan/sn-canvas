package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Freehand strokes (FR5): samples to an element and back, thinning, smoothing and line hit-testing. */
class StrokeElementsTest {
    private fun sample(
        x: Double,
        y: Double,
        pressure: Double = 0.5,
    ) = StrokePoint(x, y, pressure)

    private fun strokeOf(vararg samples: StrokePoint): Element = StrokeElements.fromSamples("s", samples.toList()) ?: error("no stroke")

    @Test
    fun `no samples make no stroke`() {
        assertNull(StrokeElements.fromSamples("s", emptyList()))
    }

    @Test
    fun `a single sample is a dot`() {
        val dot = strokeOf(sample(5.0, 6.0))
        assertEquals(listOf("draw", 5.0, 6.0, 0.0, 0.0), listOf(dot.type, dot.x, dot.y, dot.width, dot.height))
        assertEquals(listOf(StrokePoint(0.0, 0.0, 0.5)), dot.points)
    }

    @Test
    fun `points are smoothed, then stored normalized to the stroke's bounds`() {
        // The middle sample (10, 0) is averaged with its neighbours to (10, 2.5).
        val stroke = strokeOf(sample(0.0, 0.0), sample(10.0, 0.0), sample(20.0, 10.0, 1.0))
        assertEquals(listOf(0.0, 0.0, 20.0, 10.0), listOf(stroke.x, stroke.y, stroke.width, stroke.height))
        assertEquals(listOf(StrokePoint(0.0, 0.0, 0.5), StrokePoint(0.5, 0.25, 0.5), StrokePoint(1.0, 1.0, 1.0)), stroke.points)
    }

    @Test
    fun `samples that barely moved are dropped, but not the one where the pen lifted`() {
        val stroke = strokeOf(sample(0.0, 0.0), sample(0.1, 0.0), sample(0.2, 0.0), sample(10.0, 0.0), sample(10.1, 0.0))
        assertEquals(3, stroke.points?.size)
        assertEquals(10.1, stroke.width, 1e-9)
    }

    @Test
    fun `world points map back through the bounds, and follow a resize`() {
        val stroke = strokeOf(sample(0.0, 0.0), sample(10.0, 0.0), sample(20.0, 10.0))
        assertEquals(StrokePoint(20.0, 10.0, 0.5), StrokeElements.worldPoints(stroke).last())
        assertEquals(40.0, StrokeElements.worldPoints(stroke.copy(width = 40.0)).last().x, 1e-9)
        assertEquals(emptyList<StrokePoint>(), StrokeElements.worldPoints(Element(id = "r", type = "rectangle")))
    }

    @Test
    fun `a flat stroke keeps its points on its line`() {
        val flat = strokeOf(sample(0.0, 5.0), sample(10.0, 5.0))
        assertEquals(0.0, flat.height, 0.0)
        assertTrue(StrokeElements.worldPoints(flat).all { it.y == 5.0 })
    }

    @Test
    fun `a stroke is near a point by its line, not by its bounds`() {
        val diagonal = strokeOf(sample(0.0, 0.0), sample(100.0, 100.0))
        assertTrue(StrokeElements.isNear(diagonal, 50.0, 50.0, 5.0))
        assertFalse(StrokeElements.isNear(diagonal, 90.0, 10.0, 5.0))
    }

    @Test
    fun `a dot is near what lies within the tolerance of it`() {
        val dot = strokeOf(sample(5.0, 6.0))
        assertTrue(StrokeElements.isNear(dot, 7.0, 6.0, 3.0))
        assertFalse(StrokeElements.isNear(dot, 20.0, 6.0, 3.0))
    }

    @Test
    fun `a rotated stroke is hit where it is drawn`() {
        // Flat from (0, 0) to (100, 0), turned 90° about its center (50, 0): now it runs from (50, -50) to (50, 50).
        val turned = strokeOf(sample(0.0, 0.0), sample(100.0, 0.0)).copy(rotation = Math.PI / 2)
        assertTrue(StrokeElements.isNear(turned, 50.0, 40.0, 5.0))
        assertFalse(StrokeElements.isNear(turned, 90.0, 0.0, 5.0))
    }

    @Test
    fun `a stroke is as wide as the pen's nib at the zoom it was drawn at, so it matches the live ink`() {
        assertEquals(StrokeElements.PEN_WIDTH_PX, strokeOf(sample(0.0, 0.0)).strokeWidth!!, 1e-9)
        val zoomedIn = StrokeElements.fromSamples("s", listOf(sample(0.0, 0.0)), zoom = 2.0) ?: error("no stroke")
        assertEquals(StrokeElements.PEN_WIDTH_PX / 2, zoomedIn.strokeWidth!!, 1e-9)
        assertThrows(IllegalArgumentException::class.java) { Element(id = "s", type = "draw", strokeWidth = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Element(id = "s", type = "draw", strokeWidth = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Element(id = "s", type = "draw", strokeWidth = Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { Element(id = "s", type = "draw", strokeWidth = Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun `a stroke's width survives a save and load, and a missing or bad one falls back to its style`() {
        val stroke = strokeOf(sample(0.0, 0.0), sample(10.0, 0.0))
        val loaded = CanvasJson.deserializeElements(CanvasJson.serializeElements(listOf(stroke))).single()
        assertEquals(StrokeElements.PEN_WIDTH_PX, loaded.strokeWidth!!, 1e-9)

        fun widthIn(field: String) =
            CanvasJson.deserializeElements("""{"version":1,"elements":[{"id":"s","type":"draw"$field}]}""").single().strokeWidth
        assertNull(widthIn(""))
        assertNull(widthIn(""","strokeWidth":0"""))
        assertNull(widthIn(""","strokeWidth":1e999"""))
    }
}
