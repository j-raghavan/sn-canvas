package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Connectors (FR7): bound-endpoint resolution, line hit-testing, unbinding on delete, endpoint drags and drawing. */
class ConnectorsTest {
    // --- resolveArrowEndpoints (FR7 connector binding) --------------------------

    @Test
    fun `resolveArrowEndpoints uses stored coordinates when unbound`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 1.0, startY = 2.0, endX = 3.0, endY = 4.0)
        val (start, end) = CanvasCore.resolveArrowEndpoints(arrow, listOf(boxA, boxB, arrow))
        assertEquals(1.0, start.x, 0.0001)
        assertEquals(2.0, start.y, 0.0001)
        assertEquals(3.0, end.x, 0.0001)
        assertEquals(4.0, end.y, 0.0001)
    }

    @Test
    fun `resolveArrowEndpoints resolves a bound start to a boundary point facing the other endpoint, not dead-center`() {
        val arrow =
            Element(
                id = "arr",
                type = "arrow",
                startX = 1.0,
                startY = 2.0,
                endX = 3.0,
                endY = 4.0,
                startElementId = "boxA",
            )
        val (start, _) = CanvasCore.resolveArrowEndpoints(arrow, listOf(boxA, boxB, arrow))
        // boxA is x=0,y=0,w=10,h=10 (center 5,5); the unbound end sits at (3,4),
        // i.e. up-and-left of center, so the ray from center hits the left edge first.
        assertEquals(0.0, start.x, 0.001)
        assertEquals(2.5, start.y, 0.001)
    }

    @Test
    fun `resolveArrowEndpoints resolves a bound end to a boundary point facing the other endpoint, not dead-center`() {
        val arrow =
            Element(
                id = "arr",
                type = "arrow",
                startX = 1.0,
                startY = 2.0,
                endX = 3.0,
                endY = 4.0,
                endElementId = "boxB",
            )
        val (_, end) = CanvasCore.resolveArrowEndpoints(arrow, listOf(boxA, boxB, arrow))
        // boxB is x=100,y=100,w=20,h=20 (center 110,110); the unbound start at (1,2)
        // is up-and-left of that center, so the ray hits the left edge first.
        assertEquals(100.0, end.x, 0.001)
        assertEquals(100.0917, end.y, 0.001)
    }

    @Test
    fun `resolveArrowEndpoints re-routes automatically when the bound shape moves (no arrow update needed)`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 1.0, startY = 2.0, endX = 3.0, endY = 4.0, startElementId = "boxA")
        val movedBoxA = boxA.copy(x = 50.0, y = 50.0)
        val (start, _) = CanvasCore.resolveArrowEndpoints(arrow, listOf(movedBoxA, boxB, arrow))
        assertEquals(50.0, start.x, 0.001)
        assertEquals(50.0962, start.y, 0.001)
    }

    @Test
    fun `resolveArrowEndpoints fans multiple connectors to the same shape out across its edge, not into one point`() {
        // Regression test: two arrows bound to the same target from opposite
        // directions must land on different boundary points, not both collapse
        // onto the target's center (the original bug — see the "why not
        // center" doc on projectToBoundary/resolveArrowEndpoints).
        val target = Element(id = "target", type = "rectangle", x = 0.0, y = 0.0, width = 20.0, height = 20.0)
        val fromAbove = Element(id = "a1", type = "arrow", startX = 10.0, startY = -100.0, endX = 0.0, endY = 0.0, endElementId = "target")
        val fromBelow = Element(id = "a2", type = "arrow", startX = 10.0, startY = 100.0, endX = 0.0, endY = 0.0, endElementId = "target")

        val (_, endAbove) = CanvasCore.resolveArrowEndpoints(fromAbove, listOf(target, fromAbove, fromBelow))
        val (_, endBelow) = CanvasCore.resolveArrowEndpoints(fromBelow, listOf(target, fromAbove, fromBelow))

        assertEquals(10.0, endAbove.x, 0.001)
        assertEquals(0.0, endAbove.y, 0.001) // top edge midpoint
        assertEquals(10.0, endBelow.x, 0.001)
        assertEquals(20.0, endBelow.y, 0.001) // bottom edge midpoint — distinct from the above
    }

    @Test
    fun `resolveArrowEndpoints projects along a purely horizontal approach (dy=0 branch)`() {
        // boxA center (5,5); the other end sits due right of it, so dy=0 and the
        // scaleY-is-unconstrained branch of projectToBoundary is what applies.
        val arrow = Element(id = "arr", type = "arrow", startX = 0.0, startY = 0.0, endX = 100.0, endY = 5.0, startElementId = "boxA")
        val (start, _) = CanvasCore.resolveArrowEndpoints(arrow, listOf(boxA, arrow))
        assertEquals(10.0, start.x, 0.001) // right-edge midpoint
        assertEquals(5.0, start.y, 0.001)
    }

    @Test
    fun `resolveArrowEndpoints returns dead-center when the other endpoint sits exactly on it (degenerate direction)`() {
        // boxA center is exactly (5,5); binding the other end to the same point
        // gives a zero direction vector — projectToBoundary's explicit early-out.
        val arrow = Element(id = "arr", type = "arrow", startX = 5.0, startY = 5.0, endX = 5.0, endY = 5.0, startElementId = "boxA")
        val (start, _) = CanvasCore.resolveArrowEndpoints(arrow, listOf(boxA, arrow))
        assertEquals(5.0, start.x, 0.001)
        assertEquals(5.0, start.y, 0.001)
    }

    @Test
    fun `resolveArrowEndpoints falls back to stored coordinates when the bound start element no longer exists`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 7.0, startY = 8.0, endX = 3.0, endY = 4.0, startElementId = "gone")
        val (start, _) = CanvasCore.resolveArrowEndpoints(arrow, listOf(boxB, arrow))
        assertEquals(7.0, start.x, 0.0001)
        assertEquals(8.0, start.y, 0.0001)
    }

    @Test
    fun `resolveArrowEndpoints falls back to stored coordinates when the bound end element no longer exists`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 1.0, startY = 2.0, endX = 9.0, endY = 10.0, endElementId = "gone")
        val (_, end) = CanvasCore.resolveArrowEndpoints(arrow, listOf(boxA, arrow))
        assertEquals(9.0, end.x, 0.0001)
        assertEquals(10.0, end.y, 0.0001)
    }

    @Test
    fun `resolveArrowEndpoints degrades to the origin when called on an element with no endpoint data at all`() {
        val bbox = Element(id = "b", type = "rectangle", x = 1.0, y = 1.0, width = 2.0, height = 2.0)
        val (start, end) = CanvasCore.resolveArrowEndpoints(bbox, listOf(bbox))
        assertEquals(0.0, start.x, 0.0001)
        assertEquals(0.0, start.y, 0.0001)
        assertEquals(0.0, end.x, 0.0001)
        assertEquals(0.0, end.y, 0.0001)
    }

    // --- hitTest against line/arrow elements ------------------------------------

    @Test
    fun `hitTest hits a point on a line element's segment`() {
        val line = Element(id = "ln", type = "line", startX = 0.0, startY = 0.0, endX = 100.0, endY = 0.0)
        val hit = CanvasCore.hitTest(worldX = 50.0, worldY = 0.0, elements = listOf(line))
        assertEquals("ln", hit?.id)
    }

    @Test
    fun `hitTest hits a point within tolerance of a line element's segment`() {
        val line = Element(id = "ln", type = "line", startX = 0.0, startY = 0.0, endX = 100.0, endY = 0.0)
        val hit = CanvasCore.hitTest(worldX = 50.0, worldY = 15.0, elements = listOf(line))
        assertEquals("ln", hit?.id)
    }

    @Test
    fun `hitTest misses a point far from a line element's segment`() {
        val line = Element(id = "ln", type = "line", startX = 0.0, startY = 0.0, endX = 100.0, endY = 0.0)
        val hit = CanvasCore.hitTest(worldX = 50.0, worldY = 100.0, elements = listOf(line))
        assertNull(hit)
    }

    @Test
    fun `hitTest handles a zero-length line segment as a point`() {
        val point = Element(id = "pt", type = "line", startX = 5.0, startY = 5.0, endX = 5.0, endY = 5.0)
        assertEquals("pt", CanvasCore.hitTest(worldX = 5.0, worldY = 5.0, elements = listOf(point))?.id)
        assertNull(CanvasCore.hitTest(worldX = 500.0, worldY = 500.0, elements = listOf(point)))
    }

    @Test
    fun `hitTest resolves a bound line's live position, not its stale stored coordinates`() {
        val boundLine =
            Element(
                id = "ln",
                type = "arrow",
                startX = 1.0,
                startY = 1.0,
                endX = 3.0,
                endY = 3.0,
                startElementId = "boxA",
                endElementId = "boxB",
            )
        // Both ends are bound, each projected onto its own shape's boundary facing
        // the other (not dead-center): boxA (0,0,10,10) -> (10,10), boxB (100,100,20,20)
        // -> (100,100) — still the same y=x diagonal, so (57,57) still lies on it.
        val hit = CanvasCore.hitTest(worldX = 57.0, worldY = 57.0, elements = listOf(boxA, boxB, boundLine))
        assertEquals("ln", hit?.id)
    }

    // --- deleteElement's connector unbind cascade (FR7) -------------------------

    @Test
    fun `deleteElement unbinds and freezes a line's start endpoint when its bound shape is deleted`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 1.0, startY = 1.0, endX = 3.0, endY = 3.0, startElementId = "boxA")
        val state = baseState.copy(elements = listOf(boxA, arrow))
        val result = CanvasCore.deleteElement(state, id = "boxA")
        val survivor = result.elements.first { it.id == "arr" }
        assertNull(survivor.startElementId)
        assertEquals(5.0, survivor.startX!!, 0.0001) // frozen at boxA's last center
        assertEquals(5.0, survivor.startY!!, 0.0001)
    }

    @Test
    fun `deleteElement unbinds and freezes a line's end endpoint when its bound shape is deleted`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 1.0, startY = 1.0, endX = 3.0, endY = 3.0, endElementId = "boxB")
        val state = baseState.copy(elements = listOf(boxB, arrow))
        val result = CanvasCore.deleteElement(state, id = "boxB")
        val survivor = result.elements.first { it.id == "arr" }
        assertNull(survivor.endElementId)
        assertEquals(110.0, survivor.endX!!, 0.0001)
        assertEquals(110.0, survivor.endY!!, 0.0001)
    }

    @Test
    fun `deleteElement leaves an unrelated line's bindings untouched`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 1.0, startY = 1.0, endX = 3.0, endY = 3.0, startElementId = "boxA")
        val state = baseState.copy(elements = listOf(boxA, boxB, arrow))
        val result = CanvasCore.deleteElement(state, id = "boxB")
        val survivor = result.elements.first { it.id == "arr" }
        assertEquals("boxA", survivor.startElementId)
    }

    // --- moveEndpoint (FR7/FR9) ---------------------------------------------------

    @Test
    fun `moveEndpoint moves the start point and clears binding when targetElementId is null`() {
        val result = ShapeEdits.moveEndpoint(endpointBase, "arr", Endpoint.START, Point(3.0, 4.0), targetElementId = null)
        val el = result.elements.first()
        assertEquals(3.0, el.startX!!, 0.0001)
        assertEquals(4.0, el.startY!!, 0.0001)
        assertNull(el.startElementId)
    }

    @Test
    fun `moveEndpoint moves the end point and sets a binding when targetElementId is provided`() {
        val result = ShapeEdits.moveEndpoint(endpointBase, "arr", Endpoint.END, Point(7.0, 7.0), targetElementId = "boxA")
        val el = result.elements.first()
        assertEquals(7.0, el.endX!!, 0.0001)
        assertEquals(7.0, el.endY!!, 0.0001)
        assertEquals("boxA", el.endElementId)
    }

    @Test
    fun `moveEndpoint is a no-op when the id does not exist`() {
        val result = ShapeEdits.moveEndpoint(endpointBase, "nonexistent", Endpoint.START, Point(1.0, 1.0), null)
        assertEquals(endpointBase.elements, result.elements)
    }

    @Test
    fun `resolveArrowEndpoints projects onto a rotated shape's rotated edge`() {
        // The bar's local top edge now faces left; approaching from straight above hits
        // its rotated end at y = 10 - 50 = -40, not the unrotated top edge at y = 0.
        val arrow = Element(id = "a", type = "arrow", startX = 50.0, startY = -200.0, endX = 0.0, endY = 0.0, endElementId = "bar")
        val (_, end) = CanvasCore.resolveArrowEndpoints(arrow, listOf(barRotated90, arrow))
        assertEquals(50.0, end.x, 0.001)
        assertEquals(-40.0, end.y, 0.001)
    }

    // --- drawing a connector ------------------------------------------------------------

    @Test
    fun `connectorFromDrag binds each end to the shape it was drawn from or to`() {
        val arrow = CanvasCore.connectorFromDrag("n", "arrow", Point(5.0, 5.0), Point(110.0, 110.0), listOf(boxA, boxB))
        assertEquals(listOf(5.0, 5.0, 110.0, 110.0), listOf(arrow.startX, arrow.startY, arrow.endX, arrow.endY))
        assertEquals("boxA", arrow.startElementId)
        assertEquals("boxB", arrow.endElementId)
    }

    @Test
    fun `connectorFromDrag leaves an end drawn on empty canvas unbound`() {
        val intoShape = CanvasCore.connectorFromDrag("n", "line", Point(50.0, 50.0), Point(5.0, 5.0), listOf(boxA))
        assertNull(intoShape.startElementId)
        assertEquals("boxA", intoShape.endElementId)
        val outOfShape = CanvasCore.connectorFromDrag("n", "line", Point(5.0, 5.0), Point(50.0, 50.0), listOf(boxA))
        assertEquals("boxA", outOfShape.startElementId)
        assertNull(outOfShape.endElementId)
    }

    @Test
    fun `bindingTargetAt never binds to another connector, even one drawn on top`() {
        val over = Element(id = "l", type = "line", startX = 0.0, startY = 5.0, endX = 10.0, endY = 5.0)
        assertEquals("boxA", CanvasCore.bindingTargetAt(Point(5.0, 5.0), listOf(boxA, over))?.id)
    }
}
