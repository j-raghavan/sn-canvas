package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a drag on a shape's handle does: resize from a corner, rotate about its center. */
class ShapeEditsTest {
    @Test
    fun `resizeElement TOP_LEFT keeps BOTTOM_RIGHT fixed`() {
        val result = ShapeEdits.resizeElement(resizeBase, "r", Corner.TOP_LEFT, newWorldX = 0.0, newWorldY = 0.0)
        val r = result.elements.first()
        assertEquals(0.0, r.x, 0.0001)
        assertEquals(0.0, r.y, 0.0001)
        assertEquals(30.0, r.width, 0.0001)
        assertEquals(30.0, r.height, 0.0001)
    }

    @Test
    fun `resizeElement BOTTOM_RIGHT keeps TOP_LEFT fixed`() {
        val result = ShapeEdits.resizeElement(resizeBase, "r", Corner.BOTTOM_RIGHT, newWorldX = 50.0, newWorldY = 50.0)
        val r = result.elements.first()
        assertEquals(10.0, r.x, 0.0001)
        assertEquals(10.0, r.y, 0.0001)
        assertEquals(40.0, r.width, 0.0001)
        assertEquals(40.0, r.height, 0.0001)
    }

    @Test
    fun `resizeElement TOP_RIGHT keeps BOTTOM_LEFT fixed`() {
        val result = ShapeEdits.resizeElement(resizeBase, "r", Corner.TOP_RIGHT, newWorldX = 40.0, newWorldY = 0.0)
        val r = result.elements.first()
        assertEquals(10.0, r.x, 0.0001)
        assertEquals(0.0, r.y, 0.0001)
        assertEquals(30.0, r.width, 0.0001)
        assertEquals(30.0, r.height, 0.0001)
    }

    @Test
    fun `resizeElement BOTTOM_LEFT keeps TOP_RIGHT fixed`() {
        val result = ShapeEdits.resizeElement(resizeBase, "r", Corner.BOTTOM_LEFT, newWorldX = 0.0, newWorldY = 40.0)
        val r = result.elements.first()
        assertEquals(0.0, r.x, 0.0001)
        assertEquals(10.0, r.y, 0.0001)
        assertEquals(30.0, r.width, 0.0001)
        assertEquals(30.0, r.height, 0.0001)
    }

    @Test
    fun `resizeElement dragging past the fixed corner still yields a valid non-negative box`() {
        val result = ShapeEdits.resizeElement(resizeBase, "r", Corner.BOTTOM_RIGHT, newWorldX = 5.0, newWorldY = 5.0)
        val r = result.elements.first()
        assertEquals(5.0, r.x, 0.0001)
        assertEquals(5.0, r.y, 0.0001)
        assertEquals(5.0, r.width, 0.0001)
        assertEquals(5.0, r.height, 0.0001)
    }

    @Test
    fun `resizeElement is a no-op when the id does not exist`() {
        val result = ShapeEdits.resizeElement(resizeBase, "nonexistent", Corner.TOP_LEFT, 0.0, 0.0)
        assertEquals(resizeBase.elements, result.elements)
    }

    // --- handleAt (FR9 resize/endpoint handle hit-testing) -----------------------

    @Test
    fun `rotateElement points the handle at the pointer and snaps near 45-degree multiples`() {
        // resizeBase's rect "r" is at (10,10) 20x20, center (20,20).
        val right = ShapeEdits.rotateElement(resizeBase, "r", Point(100.0, 20.0)).elements.first()
        assertEquals(Math.PI / 2, right.rotation, 0.0001) // handle dragged to the right = 90° clockwise
        val above = ShapeEdits.rotateElement(resizeBase, "r", Point(20.0, -100.0)).elements.first()
        assertEquals(0.0, above.rotation, 0.0001)
        val near45 = ShapeEdits.rotateElement(resizeBase, "r", pointAtAngleFromUp(20.0, 20.0, 47.0)).elements.first()
        assertEquals(Math.PI / 4, near45.rotation, 0.0001) // within 5° of 45° snaps
        val at30 = ShapeEdits.rotateElement(resizeBase, "r", pointAtAngleFromUp(20.0, 20.0, 30.0)).elements.first()
        assertEquals(Math.toRadians(30.0), at30.rotation, 0.0001) // outside the snap window stays put
    }

    @Test
    fun `rotateElement is a no-op for a missing id or a line element`() {
        assertEquals(resizeBase.elements, ShapeEdits.rotateElement(resizeBase, "nope", Point(0.0, 0.0)).elements)
        assertEquals(endpointBase.elements, ShapeEdits.rotateElement(endpointBase, "arr", Point(99.0, 0.0)).elements)
    }

    @Test
    fun `snapRotation normalizes into the minus-pi to pi range`() {
        assertEquals(Math.PI / 2, ShapeEdits.snapRotation(Math.PI / 2 + 2 * Math.PI), 0.0001)
        assertEquals(-Math.toRadians(100.0), ShapeEdits.snapRotation(Math.toRadians(260.0)), 0.0001)
    }

    @Test
    fun `resizeElement on a rotated shape keeps the opposite corner fixed in world space`() {
        val rotated = Element(id = "r", type = "rectangle", x = 0.0, y = 0.0, width = 100.0, height = 50.0, rotation = Math.PI / 6)
        val state = CanvasState(elements = listOf(rotated), viewportX = 0.0, viewportY = 0.0, zoom = 1.0)
        val topLeftBefore = CanvasCore.cornerPoints(rotated)[0]
        val result = ShapeEdits.resizeElement(state, "r", Corner.BOTTOM_RIGHT, 150.0, 120.0).elements.first()
        val topLeftAfter = CanvasCore.cornerPoints(result)[0]
        assertEquals(topLeftBefore.x, topLeftAfter.x, 0.0001)
        assertEquals(topLeftBefore.y, topLeftAfter.y, 0.0001)
        assertEquals(Math.PI / 6, result.rotation, 0.0001)
    }

    @Test
    fun `resizeElement on a rotated shape keeps the opposite corner fixed for the other three corners`() {
        val rotated = Element(id = "r", type = "rectangle", x = 0.0, y = 0.0, width = 100.0, height = 50.0, rotation = Math.PI / 6)
        val state = CanvasState(elements = listOf(rotated), viewportX = 0.0, viewportY = 0.0, zoom = 1.0)
        val before = CanvasCore.cornerPoints(rotated) // TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
        // Each dragged corner moves outward along its diagonal; the diagonally-opposite corner must not move.
        val cases = listOf(Corner.TOP_LEFT to 3, Corner.TOP_RIGHT to 2, Corner.BOTTOM_LEFT to 1)
        for ((corner, oppositeIndex) in cases) {
            val dragged = before[3 - oppositeIndex]
            val fixed = before[oppositeIndex]
            val pointer = Point(dragged.x + (dragged.x - fixed.x) * 0.2, dragged.y + (dragged.y - fixed.y) * 0.2)
            val result = ShapeEdits.resizeElement(state, "r", corner, pointer.x, pointer.y).elements.first()
            val after = CanvasCore.cornerPoints(result)[oppositeIndex]
            assertEquals(fixed.x, after.x, 0.0001)
            assertEquals(fixed.y, after.y, 0.0001)
        }
    }
}
