package com.snsupercanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Bbox outlines and the stable hand-drawn wobble of the "draw" dash (FR19). */
class ShapeOutlineTest {
    private val box = Element(id = "b", type = "rectangle", x = 10.0, y = 20.0, width = 100.0, height = 50.0)

    @Test
    fun `a rectangle's outline is its four corners, clockwise from top-left`() {
        assertEquals(
            listOf(Point(10.0, 20.0), Point(110.0, 20.0), Point(110.0, 70.0), Point(10.0, 70.0)),
            ShapeOutline.of(box),
        )
    }

    @Test
    fun `an ellipse's outline lies on the ellipse`() {
        val outline = ShapeOutline.of(box.copy(type = "ellipse"))
        assertEquals(64, outline.size)
        for (p in outline) {
            val nx = (p.x - 60.0) / 50.0
            val ny = (p.y - 45.0) / 25.0
            assertEquals(1.0, nx * nx + ny * ny, 1e-9)
        }
    }

    @Test
    fun `the wobble is the same on every redraw and differs between elements`() {
        val outline = ShapeOutline.of(box)
        val once = ShapeOutline.handDrawn(outline, closed = true, seed = 42, amplitude = 2.0)
        assertEquals(once, ShapeOutline.handDrawn(outline, closed = true, seed = 42, amplitude = 2.0))
        assertNotEquals(once, ShapeOutline.handDrawn(outline, closed = true, seed = "other".hashCode(), amplitude = 2.0))
    }

    @Test
    fun `the wobble stays within its amplitude of the true outline`() {
        val line = listOf(Point(0.0, 0.0), Point(200.0, 0.0))
        val wobbly = ShapeOutline.handDrawn(line, closed = false, seed = 7, amplitude = 3.0)
        assertTrue(wobbly.size > 10)
        wobbly.forEach { assertTrue(kotlin.math.abs(it.y) <= 3.0 + 1e-9) }
        assertEquals(200.0, wobbly.last().x, 1e-9)
    }

    @Test
    fun `a closed outline is walked back to its start`() {
        val wobbly = ShapeOutline.handDrawn(ShapeOutline.of(box), closed = true, seed = 1, amplitude = 2.0)
        val start = wobbly.first()
        val end = wobbly.last()
        assertTrue(hypot(end.x - start.x, end.y - start.y) <= 4.0 + 1e-9)
    }

    @Test
    fun `zero amplitude, zero-length segments and single points are handled`() {
        val flat = ShapeOutline.handDrawn(listOf(Point(0.0, 0.0), Point(50.0, 0.0)), closed = false, seed = 3, amplitude = 0.0)
        flat.forEach { assertEquals(0.0, it.y, 0.0) }
        val point = box.copy(width = 0.0, height = 0.0)
        assertEquals(5, ShapeOutline.handDrawn(ShapeOutline.of(point), closed = true, seed = 3, amplitude = 2.0).size)
        assertEquals(listOf(Point(1.0, 1.0)), ShapeOutline.handDrawn(listOf(Point(1.0, 1.0)), closed = true, seed = 3, amplitude = 2.0))
    }
}
