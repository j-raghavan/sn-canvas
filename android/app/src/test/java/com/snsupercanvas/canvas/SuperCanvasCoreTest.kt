package com.snsupercanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

// One test file per source file is this codebase's established convention (every
// prior slice follows it) — splitting this file would break that rather than fix
// anything; the JSON parser's edge cases are what's driving the size, not a design
// problem in the tests themselves.
@Suppress("LargeClass")
class SuperCanvasCoreTest {
    private val baseState =
        CanvasState(
            elements =
                listOf(
                    Element(id = "a", type = "rect", x = 0.0, y = 0.0, width = 10.0, height = 10.0),
                    Element(id = "b", type = "rect", x = 5.0, y = 5.0, width = 10.0, height = 10.0),
                ),
            viewportX = 0.0,
            viewportY = 0.0,
            zoom = 1.0,
        )

    @Test
    fun `hitTest returns topmost overlapping element`() {
        val hit = SuperCanvasCore.hitTest(worldX = 7.0, worldY = 7.0, elements = baseState.elements)
        assertEquals("b", hit?.id)
    }

    @Test
    fun `hitTest returns null when no element contains the point`() {
        val hit = SuperCanvasCore.hitTest(worldX = 100.0, worldY = 100.0, elements = baseState.elements)
        assertNull(hit)
    }

    @Test
    fun `panBy shifts viewport opposite the pan delta`() {
        val panned = SuperCanvasCore.panBy(baseState, dx = 5.0, dy = -3.0)
        assertEquals(-5.0, panned.viewportX, 0.0001)
        assertEquals(3.0, panned.viewportY, 0.0001)
    }

    @Test
    fun `zoomTo doubles zoom and keeps the focal point stationary`() {
        val zoomed = SuperCanvasCore.zoomTo(baseState, scaleFactor = 2.0, focalWorldX = 10.0, focalWorldY = 10.0)
        assertEquals(2.0, zoomed.zoom, 0.0001)
        // Focal point (10,10) should map to the same screen position before/after:
        // (focal - viewport_old) * zoom_old == (focal - viewport_new) * zoom_new
        val before = (10.0 - baseState.viewportX) * baseState.zoom
        val after = (10.0 - zoomed.viewportX) * zoomed.zoom
        assertEquals(before, after, 0.0001)
    }

    @Test
    fun `zoomTo clamps to MIN_ZOOM and MAX_ZOOM`() {
        val zoomedOut = SuperCanvasCore.zoomTo(baseState, scaleFactor = 0.0001, focalWorldX = 0.0, focalWorldY = 0.0)
        assertEquals(SuperCanvasCore.MIN_ZOOM, zoomedOut.zoom, 0.0001)

        val zoomedIn = SuperCanvasCore.zoomTo(baseState, scaleFactor = 1000.0, focalWorldX = 0.0, focalWorldY = 0.0)
        assertEquals(SuperCanvasCore.MAX_ZOOM, zoomedIn.zoom, 0.0001)
    }

    @Test
    fun `hitTest on an empty element list returns null`() {
        assertNull(SuperCanvasCore.hitTest(worldX = 0.0, worldY = 0.0, elements = emptyList()))
    }

    @Test
    fun `zoomTo rejects a non-positive scaleFactor`() {
        assertThrows(IllegalArgumentException::class.java) {
            SuperCanvasCore.zoomTo(baseState, scaleFactor = 0.0, focalWorldX = 0.0, focalWorldY = 0.0)
        }
    }

    @Test
    fun `Element rejects a negative width`() {
        assertThrows(IllegalArgumentException::class.java) {
            Element(id = "x", type = "rect", x = 0.0, y = 0.0, width = -1.0, height = 1.0)
        }
    }

    @Test
    fun `Element rejects a negative height`() {
        assertThrows(IllegalArgumentException::class.java) {
            Element(id = "x", type = "rect", x = 0.0, y = 0.0, width = 1.0, height = -1.0)
        }
    }

    @Test
    fun `Element accepts a zero-size bounding box`() {
        val point = Element(id = "x", type = "rect", x = 0.0, y = 0.0, width = 0.0, height = 0.0)
        assertEquals(0.0, point.width, 0.0001)
        assertEquals(0.0, point.height, 0.0001)
    }

    @Test
    fun `containsPoint is false when the point is left of, above, right of, or below the box`() {
        val box = Element(id = "x", type = "rect", x = 10.0, y = 10.0, width = 10.0, height = 10.0)
        assertEquals(false, box.containsPoint(worldX = 5.0, worldY = 15.0)) // left of box
        assertEquals(false, box.containsPoint(worldX = 15.0, worldY = 5.0)) // above box
        assertEquals(false, box.containsPoint(worldX = 25.0, worldY = 15.0)) // right of box
        assertEquals(false, box.containsPoint(worldX = 15.0, worldY = 25.0)) // below box
        assertEquals(true, box.containsPoint(worldX = 15.0, worldY = 15.0)) // inside
    }

    @Test
    fun `CanvasState rejects a non-positive zoom`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanvasState(elements = emptyList(), viewportX = 0.0, viewportY = 0.0, zoom = 0.0)
        }
    }

    @Test
    fun `insertElement appends to the end of the list`() {
        val newElement = Element(id = "c", type = "ellipse", x = 1.0, y = 1.0, width = 2.0, height = 2.0)
        val result = SuperCanvasCore.insertElement(baseState, newElement)
        assertEquals(3, result.elements.size)
        assertEquals("c", result.elements.last().id)
    }

    @Test
    fun `insertElement into an empty-element state yields a single-element list`() {
        val empty = baseState.copy(elements = emptyList())
        val newElement = Element(id = "solo", type = "rect", x = 0.0, y = 0.0, width = 1.0, height = 1.0)
        val result = SuperCanvasCore.insertElement(empty, newElement)
        assertEquals(listOf("solo"), result.elements.map { it.id })
    }

    @Test
    fun `deleteElement removes the matching element by id`() {
        val result = SuperCanvasCore.deleteElement(baseState, id = "a")
        assertEquals(listOf("b"), result.elements.map { it.id })
    }

    @Test
    fun `deleteElement is a no-op when the id does not exist`() {
        val result = SuperCanvasCore.deleteElement(baseState, id = "nonexistent")
        assertEquals(baseState.elements, result.elements)
    }

    @Test
    fun `deleteElement on an empty element list is a no-op`() {
        val empty = baseState.copy(elements = emptyList())
        val result = SuperCanvasCore.deleteElement(empty, id = "anything")
        assertEquals(emptyList<Element>(), result.elements)
    }

    @Test
    fun `moveElement translates only the matching element by dx dy`() {
        val result = SuperCanvasCore.moveElement(baseState, id = "a", dx = 3.0, dy = -2.0)
        val moved = result.elements.first { it.id == "a" }
        val untouched = result.elements.first { it.id == "b" }
        assertEquals(3.0, moved.x, 0.0001)
        assertEquals(-2.0, moved.y, 0.0001)
        assertEquals(baseState.elements.first { it.id == "b" }.x, untouched.x, 0.0001)
        assertEquals(baseState.elements.first { it.id == "b" }.y, untouched.y, 0.0001)
    }

    @Test
    fun `moveElement is a no-op when the id does not exist`() {
        val result = SuperCanvasCore.moveElement(baseState, id = "nonexistent", dx = 5.0, dy = 5.0)
        assertEquals(baseState.elements, result.elements)
    }

    @Test
    fun `moveElement preserves element size and type`() {
        val result = SuperCanvasCore.moveElement(baseState, id = "a", dx = 1.0, dy = 1.0)
        val moved = result.elements.first { it.id == "a" }
        assertEquals(10.0, moved.width, 0.0001)
        assertEquals(10.0, moved.height, 0.0001)
        assertEquals("rect", moved.type)
    }

    // --- v1b: Element endpoint-field consistency -----------------------------------

    @Test
    fun `Element rejects a partially-null endpoint field set`() {
        assertThrows(IllegalArgumentException::class.java) {
            Element(id = "x", type = "line", startX = 0.0, startY = 0.0, endX = null, endY = 5.0)
        }
    }

    @Test
    fun `Element accepts all-null endpoint fields (bbox element)`() {
        val el = Element(id = "x", type = "rect", x = 0.0, y = 0.0, width = 1.0, height = 1.0)
        assertEquals(false, el.hasEndpoints())
    }

    @Test
    fun `Element accepts all-non-null endpoint fields (line element)`() {
        val el = Element(id = "x", type = "line", startX = 0.0, startY = 0.0, endX = 1.0, endY = 1.0)
        assertEquals(true, el.hasEndpoints())
    }

    // --- v1b: Point structural equality (exercises its generated equals/hashCode) ----

    @Test
    fun `Point implements structural equality`() {
        assertEquals(Point(1.0, 2.0), Point(1.0, 2.0))
        assertEquals(false, Point(1.0, 2.0) == Point(2.0, 1.0))
        @Suppress("EqualsBetweenInconvertibleTypes")
        assertEquals(false, Point(1.0, 2.0).equals("not a point"))
    }

    // --- v1b: cornerPoints ----------------------------------------------------------

    @Test
    fun `cornerPoints returns the four bbox corners in TOP_LEFT,TOP_RIGHT,BOTTOM_LEFT,BOTTOM_RIGHT order`() {
        val el = Element(id = "x", type = "rect", x = 10.0, y = 20.0, width = 5.0, height = 8.0)
        val points = SuperCanvasCore.cornerPoints(el)
        assertEquals(listOf(10.0, 15.0, 10.0, 15.0), points.map { it.x })
        assertEquals(listOf(20.0, 20.0, 28.0, 28.0), points.map { it.y })
    }

    // --- v1b: resolveArrowEndpoints (FR7 connector binding) --------------------------

    private val boxA = Element(id = "boxA", type = "rectangle", x = 0.0, y = 0.0, width = 10.0, height = 10.0)
    private val boxB = Element(id = "boxB", type = "rectangle", x = 100.0, y = 100.0, width = 20.0, height = 20.0)

    @Test
    fun `resolveArrowEndpoints uses stored coordinates when unbound`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 1.0, startY = 2.0, endX = 3.0, endY = 4.0)
        val (start, end) = SuperCanvasCore.resolveArrowEndpoints(arrow, listOf(boxA, boxB, arrow))
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
        val (start, _) = SuperCanvasCore.resolveArrowEndpoints(arrow, listOf(boxA, boxB, arrow))
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
        val (_, end) = SuperCanvasCore.resolveArrowEndpoints(arrow, listOf(boxA, boxB, arrow))
        // boxB is x=100,y=100,w=20,h=20 (center 110,110); the unbound start at (1,2)
        // is up-and-left of that center, so the ray hits the left edge first.
        assertEquals(100.0, end.x, 0.001)
        assertEquals(100.0917, end.y, 0.001)
    }

    @Test
    fun `resolveArrowEndpoints re-routes automatically when the bound shape moves (no arrow update needed)`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 1.0, startY = 2.0, endX = 3.0, endY = 4.0, startElementId = "boxA")
        val movedBoxA = boxA.copy(x = 50.0, y = 50.0)
        val (start, _) = SuperCanvasCore.resolveArrowEndpoints(arrow, listOf(movedBoxA, boxB, arrow))
        assertEquals(50.0, start.x, 0.001)
        assertEquals(50.0962, start.y, 0.001)
    }

    @Test
    fun `resolveArrowEndpoints fans multiple connectors to the same shape out across its edge, not into one point`() {
        // Regression test: two arrows bound to the same target from opposite
        // directions must land on different boundary points, not both collapse
        // onto the target's center (the original v1b bug — see the "why not
        // center" doc on projectToBoundary/resolveArrowEndpoints).
        val target = Element(id = "target", type = "rectangle", x = 0.0, y = 0.0, width = 20.0, height = 20.0)
        val fromAbove = Element(id = "a1", type = "arrow", startX = 10.0, startY = -100.0, endX = 0.0, endY = 0.0, endElementId = "target")
        val fromBelow = Element(id = "a2", type = "arrow", startX = 10.0, startY = 100.0, endX = 0.0, endY = 0.0, endElementId = "target")

        val (_, endAbove) = SuperCanvasCore.resolveArrowEndpoints(fromAbove, listOf(target, fromAbove, fromBelow))
        val (_, endBelow) = SuperCanvasCore.resolveArrowEndpoints(fromBelow, listOf(target, fromAbove, fromBelow))

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
        val (start, _) = SuperCanvasCore.resolveArrowEndpoints(arrow, listOf(boxA, arrow))
        assertEquals(10.0, start.x, 0.001) // right-edge midpoint
        assertEquals(5.0, start.y, 0.001)
    }

    @Test
    fun `resolveArrowEndpoints returns dead-center when the other endpoint sits exactly on it (degenerate direction)`() {
        // boxA center is exactly (5,5); binding the other end to the same point
        // gives a zero direction vector — projectToBoundary's explicit early-out.
        val arrow = Element(id = "arr", type = "arrow", startX = 5.0, startY = 5.0, endX = 5.0, endY = 5.0, startElementId = "boxA")
        val (start, _) = SuperCanvasCore.resolveArrowEndpoints(arrow, listOf(boxA, arrow))
        assertEquals(5.0, start.x, 0.001)
        assertEquals(5.0, start.y, 0.001)
    }

    @Test
    fun `resolveArrowEndpoints falls back to stored coordinates when the bound start element no longer exists`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 7.0, startY = 8.0, endX = 3.0, endY = 4.0, startElementId = "gone")
        val (start, _) = SuperCanvasCore.resolveArrowEndpoints(arrow, listOf(boxB, arrow))
        assertEquals(7.0, start.x, 0.0001)
        assertEquals(8.0, start.y, 0.0001)
    }

    @Test
    fun `resolveArrowEndpoints falls back to stored coordinates when the bound end element no longer exists`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 1.0, startY = 2.0, endX = 9.0, endY = 10.0, endElementId = "gone")
        val (_, end) = SuperCanvasCore.resolveArrowEndpoints(arrow, listOf(boxA, arrow))
        assertEquals(9.0, end.x, 0.0001)
        assertEquals(10.0, end.y, 0.0001)
    }

    @Test
    fun `resolveArrowEndpoints degrades to the origin when called on an element with no endpoint data at all`() {
        val bbox = Element(id = "b", type = "rectangle", x = 1.0, y = 1.0, width = 2.0, height = 2.0)
        val (start, end) = SuperCanvasCore.resolveArrowEndpoints(bbox, listOf(bbox))
        assertEquals(0.0, start.x, 0.0001)
        assertEquals(0.0, start.y, 0.0001)
        assertEquals(0.0, end.x, 0.0001)
        assertEquals(0.0, end.y, 0.0001)
    }

    // --- v1b: hitTest against line/arrow elements ------------------------------------

    @Test
    fun `hitTest hits a point on a line element's segment`() {
        val line = Element(id = "ln", type = "line", startX = 0.0, startY = 0.0, endX = 100.0, endY = 0.0)
        val hit = SuperCanvasCore.hitTest(worldX = 50.0, worldY = 0.0, elements = listOf(line))
        assertEquals("ln", hit?.id)
    }

    @Test
    fun `hitTest hits a point within tolerance of a line element's segment`() {
        val line = Element(id = "ln", type = "line", startX = 0.0, startY = 0.0, endX = 100.0, endY = 0.0)
        val hit = SuperCanvasCore.hitTest(worldX = 50.0, worldY = 15.0, elements = listOf(line))
        assertEquals("ln", hit?.id)
    }

    @Test
    fun `hitTest misses a point far from a line element's segment`() {
        val line = Element(id = "ln", type = "line", startX = 0.0, startY = 0.0, endX = 100.0, endY = 0.0)
        val hit = SuperCanvasCore.hitTest(worldX = 50.0, worldY = 100.0, elements = listOf(line))
        assertNull(hit)
    }

    @Test
    fun `hitTest handles a zero-length line segment as a point`() {
        val point = Element(id = "pt", type = "line", startX = 5.0, startY = 5.0, endX = 5.0, endY = 5.0)
        assertEquals("pt", SuperCanvasCore.hitTest(worldX = 5.0, worldY = 5.0, elements = listOf(point))?.id)
        assertNull(SuperCanvasCore.hitTest(worldX = 500.0, worldY = 500.0, elements = listOf(point)))
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
        val hit = SuperCanvasCore.hitTest(worldX = 57.0, worldY = 57.0, elements = listOf(boxA, boxB, boundLine))
        assertEquals("ln", hit?.id)
    }

    // --- v1b: deleteElement's connector unbind cascade (FR7) -------------------------

    @Test
    fun `deleteElement unbinds and freezes a line's start endpoint when its bound shape is deleted`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 1.0, startY = 1.0, endX = 3.0, endY = 3.0, startElementId = "boxA")
        val state = baseState.copy(elements = listOf(boxA, arrow))
        val result = SuperCanvasCore.deleteElement(state, id = "boxA")
        val survivor = result.elements.first { it.id == "arr" }
        assertNull(survivor.startElementId)
        assertEquals(5.0, survivor.startX!!, 0.0001) // frozen at boxA's last center
        assertEquals(5.0, survivor.startY!!, 0.0001)
    }

    @Test
    fun `deleteElement unbinds and freezes a line's end endpoint when its bound shape is deleted`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 1.0, startY = 1.0, endX = 3.0, endY = 3.0, endElementId = "boxB")
        val state = baseState.copy(elements = listOf(boxB, arrow))
        val result = SuperCanvasCore.deleteElement(state, id = "boxB")
        val survivor = result.elements.first { it.id == "arr" }
        assertNull(survivor.endElementId)
        assertEquals(110.0, survivor.endX!!, 0.0001)
        assertEquals(110.0, survivor.endY!!, 0.0001)
    }

    @Test
    fun `deleteElement leaves an unrelated line's bindings untouched`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 1.0, startY = 1.0, endX = 3.0, endY = 3.0, startElementId = "boxA")
        val state = baseState.copy(elements = listOf(boxA, boxB, arrow))
        val result = SuperCanvasCore.deleteElement(state, id = "boxB")
        val survivor = result.elements.first { it.id == "arr" }
        assertEquals("boxA", survivor.startElementId)
    }

    // --- v1b: resizeElement (FR9) -----------------------------------------------------

    private val resizeBase =
        CanvasState(
            elements = listOf(Element(id = "r", type = "rectangle", x = 10.0, y = 10.0, width = 20.0, height = 20.0)),
            viewportX = 0.0,
            viewportY = 0.0,
            zoom = 1.0,
        )

    @Test
    fun `resizeElement TOP_LEFT keeps BOTTOM_RIGHT fixed`() {
        val result = SuperCanvasCore.resizeElement(resizeBase, "r", Corner.TOP_LEFT, newWorldX = 0.0, newWorldY = 0.0)
        val r = result.elements.first()
        assertEquals(0.0, r.x, 0.0001)
        assertEquals(0.0, r.y, 0.0001)
        assertEquals(30.0, r.width, 0.0001)
        assertEquals(30.0, r.height, 0.0001)
    }

    @Test
    fun `resizeElement BOTTOM_RIGHT keeps TOP_LEFT fixed`() {
        val result = SuperCanvasCore.resizeElement(resizeBase, "r", Corner.BOTTOM_RIGHT, newWorldX = 50.0, newWorldY = 50.0)
        val r = result.elements.first()
        assertEquals(10.0, r.x, 0.0001)
        assertEquals(10.0, r.y, 0.0001)
        assertEquals(40.0, r.width, 0.0001)
        assertEquals(40.0, r.height, 0.0001)
    }

    @Test
    fun `resizeElement TOP_RIGHT keeps BOTTOM_LEFT fixed`() {
        val result = SuperCanvasCore.resizeElement(resizeBase, "r", Corner.TOP_RIGHT, newWorldX = 40.0, newWorldY = 0.0)
        val r = result.elements.first()
        assertEquals(10.0, r.x, 0.0001)
        assertEquals(0.0, r.y, 0.0001)
        assertEquals(30.0, r.width, 0.0001)
        assertEquals(30.0, r.height, 0.0001)
    }

    @Test
    fun `resizeElement BOTTOM_LEFT keeps TOP_RIGHT fixed`() {
        val result = SuperCanvasCore.resizeElement(resizeBase, "r", Corner.BOTTOM_LEFT, newWorldX = 0.0, newWorldY = 40.0)
        val r = result.elements.first()
        assertEquals(0.0, r.x, 0.0001)
        assertEquals(10.0, r.y, 0.0001)
        assertEquals(30.0, r.width, 0.0001)
        assertEquals(30.0, r.height, 0.0001)
    }

    @Test
    fun `resizeElement dragging past the fixed corner still yields a valid non-negative box`() {
        val result = SuperCanvasCore.resizeElement(resizeBase, "r", Corner.BOTTOM_RIGHT, newWorldX = 5.0, newWorldY = 5.0)
        val r = result.elements.first()
        assertEquals(5.0, r.x, 0.0001)
        assertEquals(5.0, r.y, 0.0001)
        assertEquals(5.0, r.width, 0.0001)
        assertEquals(5.0, r.height, 0.0001)
    }

    @Test
    fun `resizeElement is a no-op when the id does not exist`() {
        val result = SuperCanvasCore.resizeElement(resizeBase, "nonexistent", Corner.TOP_LEFT, 0.0, 0.0)
        assertEquals(resizeBase.elements, result.elements)
    }

    // --- v1b: moveEndpoint (FR7/FR9) ---------------------------------------------------

    private val endpointBase =
        CanvasState(
            elements = listOf(Element(id = "arr", type = "arrow", startX = 0.0, startY = 0.0, endX = 10.0, endY = 10.0)),
            viewportX = 0.0,
            viewportY = 0.0,
            zoom = 1.0,
        )

    @Test
    fun `moveEndpoint moves the start point and clears binding when targetElementId is null`() {
        val result = SuperCanvasCore.moveEndpoint(endpointBase, "arr", Endpoint.START, Point(3.0, 4.0), targetElementId = null)
        val el = result.elements.first()
        assertEquals(3.0, el.startX!!, 0.0001)
        assertEquals(4.0, el.startY!!, 0.0001)
        assertNull(el.startElementId)
    }

    @Test
    fun `moveEndpoint moves the end point and sets a binding when targetElementId is provided`() {
        val result = SuperCanvasCore.moveEndpoint(endpointBase, "arr", Endpoint.END, Point(7.0, 7.0), targetElementId = "boxA")
        val el = result.elements.first()
        assertEquals(7.0, el.endX!!, 0.0001)
        assertEquals(7.0, el.endY!!, 0.0001)
        assertEquals("boxA", el.endElementId)
    }

    @Test
    fun `moveEndpoint is a no-op when the id does not exist`() {
        val result = SuperCanvasCore.moveEndpoint(endpointBase, "nonexistent", Endpoint.START, Point(1.0, 1.0), null)
        assertEquals(endpointBase.elements, result.elements)
    }

    // --- v1b: handleAt (FR9 resize/endpoint handle hit-testing) -----------------------

    @Test
    fun `handleAt returns null when the selected element id does not exist`() {
        assertNull(SuperCanvasCore.handleAt(resizeBase, "nonexistent", 0.0, 0.0, toleranceWorld = 5.0))
    }

    @Test
    fun `handleAt finds each bbox corner handle within tolerance`() {
        assertEquals(HandleTarget.CornerHandle(Corner.TOP_LEFT), SuperCanvasCore.handleAt(resizeBase, "r", 10.0, 10.0, 2.0))
        assertEquals(HandleTarget.CornerHandle(Corner.TOP_RIGHT), SuperCanvasCore.handleAt(resizeBase, "r", 30.0, 10.0, 2.0))
        assertEquals(HandleTarget.CornerHandle(Corner.BOTTOM_LEFT), SuperCanvasCore.handleAt(resizeBase, "r", 10.0, 30.0, 2.0))
        assertEquals(HandleTarget.CornerHandle(Corner.BOTTOM_RIGHT), SuperCanvasCore.handleAt(resizeBase, "r", 30.0, 30.0, 2.0))
    }

    @Test
    fun `handleAt returns null for a bbox element when the point is far from every corner`() {
        assertNull(SuperCanvasCore.handleAt(resizeBase, "r", 20.0, 20.0, toleranceWorld = 2.0))
    }

    @Test
    fun `handleAt finds a line element's start and end endpoint handles`() {
        assertEquals(HandleTarget.EndpointHandle(Endpoint.START), SuperCanvasCore.handleAt(endpointBase, "arr", 0.0, 0.0, 2.0))
        assertEquals(HandleTarget.EndpointHandle(Endpoint.END), SuperCanvasCore.handleAt(endpointBase, "arr", 10.0, 10.0, 2.0))
    }

    @Test
    fun `handleAt returns null for a line element when the point is far from both endpoints`() {
        assertNull(SuperCanvasCore.handleAt(endpointBase, "arr", 5.0, 5.0, toleranceWorld = 2.0))
    }

    @Test
    fun `handleAt uses a bound line's live resolved endpoint position, not its stale stored coordinate`() {
        val boundArrow =
            Element(id = "arr", type = "arrow", startX = 999.0, startY = 999.0, endX = 50.0, endY = 5.0, startElementId = "boxA")
        val state = CanvasState(elements = listOf(boxA, boundArrow), viewportX = 0.0, viewportY = 0.0, zoom = 1.0)
        // boxA (center 5,5, half-extents 5,5) projected toward the fixed end (50,5) —
        // a point due right of center — lands on boxA's right-edge midpoint (10,5),
        // not the stale stored (999,999) and not boxA's raw center either.
        assertEquals(HandleTarget.EndpointHandle(Endpoint.START), SuperCanvasCore.handleAt(state, "arr", 10.0, 5.0, 2.0))
    }

    // --- v1c: serializeElements / deserializeElements (persistence) ------------------

    @Test
    fun `serializeElements then deserializeElements round-trips a mix of bbox and connector elements`() {
        val rect = Element(id = "r1", type = "rectangle", x = 1.0, y = 2.0, width = 3.0, height = 4.0)
        val ellipse = Element(id = "e1", type = "ellipse", x = -5.5, y = 0.0, width = 10.0, height = 20.0)
        val freeLine = Element(id = "l1", type = "line", startX = 1.0, startY = 2.0, endX = 3.0, endY = 4.0)
        val boundArrow =
            Element(
                id = "a1",
                type = "arrow",
                startX = 0.0,
                startY = 0.0,
                endX = 5.0,
                endY = 5.0,
                startElementId = "r1",
                endElementId = "e1",
            )
        val original = listOf(rect, ellipse, freeLine, boundArrow)

        val json = SuperCanvasCore.serializeElements(original)
        val restored = SuperCanvasCore.deserializeElements(json)

        assertEquals(original, restored)
    }

    @Test
    fun `serializeElements round-trips an empty element list`() {
        val json = SuperCanvasCore.serializeElements(emptyList())
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements(json))
    }

    @Test
    fun `serializeElements escapes special characters in string fields`() {
        val tricky = Element(id = "id-with-\"quote\"-and-\\backslash\\-and-\nnewline", type = "rectangle")
        val restored = SuperCanvasCore.deserializeElements(SuperCanvasCore.serializeElements(listOf(tricky)))
        assertEquals(listOf(tricky), restored)
    }

    @Test
    fun `deserializeElements degrades to an empty list for malformed JSON`() {
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("{not valid json"))
    }

    @Test
    fun `deserializeElements degrades to an empty list for an empty string`() {
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements(""))
    }

    @Test
    fun `deserializeElements degrades to an empty list when the top-level shape is wrong`() {
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("[1,2,3]"))
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("42"))
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("\"just a string\""))
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("null"))
    }

    @Test
    fun `deserializeElements degrades to an empty list when the elements field is missing or the wrong type`() {
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("""{"version":1}"""))
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("""{"version":1,"elements":"not an array"}"""))
    }

    @Test
    fun `deserializeElements skips one malformed element but keeps the rest of a valid array`() {
        val json =
            """{"version":1,"elements":[
                {"id":"good1","type":"rectangle","x":0,"y":0,"width":1,"height":1},
                {"id":"bad-negative-width","type":"rectangle","x":0,"y":0,"width":-1,"height":1},
                "not even an object",
                {"id":"good2","type":"ellipse","x":0,"y":0,"width":1,"height":1}
            ]}"""
        val restored = SuperCanvasCore.deserializeElements(json)
        assertEquals(listOf("good1", "good2"), restored.map { it.id })
    }

    @Test
    fun `deserializeElements skips an element missing its required id field`() {
        val json = """{"version":1,"elements":[{"type":"rectangle"}]}"""
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements(json))
    }

    @Test
    fun `deserializeElements skips an element missing its required type field`() {
        val json = """{"version":1,"elements":[{"id":"r1"}]}"""
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements(json))
    }

    @Test
    fun `serializeElements escapes an arbitrary control character via unicode escape, not just the named ones`() {
        val tricky = Element(id = "id-with--control-char", type = "rectangle")
        val json = SuperCanvasCore.serializeElements(listOf(tricky))
        assertEquals(listOf(tricky), SuperCanvasCore.deserializeElements(json))
    }

    @Test
    fun `deserializeElements defaults missing bbox fields to zero rather than throwing`() {
        val json = """{"version":1,"elements":[{"id":"r1","type":"rectangle"}]}"""
        val restored = SuperCanvasCore.deserializeElements(json)
        assertEquals(listOf(Element(id = "r1", type = "rectangle")), restored)
    }

    @Test
    fun `deserializeElements ignores unknown extra fields rather than failing`() {
        val json =
            """{"version":1,"elements":[
                {"id":"r1","type":"rectangle","x":0,"y":0,"width":1,"height":1,"futureField":"ignored","nested":{"a":1}}
            ],"futureTopLevelField":true}"""
        val restored = SuperCanvasCore.deserializeElements(json)
        assertEquals(listOf(Element(id = "r1", type = "rectangle", width = 1.0, height = 1.0)), restored)
    }

    @Test
    fun `deserializeElements handles a unicode escape sequence in a string field`() {
        val json = "{\"version\":1,\"elements\":[{\"id\":\"caf\\u00e9\",\"type\":\"rectangle\"}]}"
        assertEquals(listOf("café"), SuperCanvasCore.deserializeElements(json).map { it.id })
    }

    @Test
    fun `deserializeElements degrades to empty on an invalid escape sequence`() {
        val json = "{\"version\":1,\"elements\":[{\"id\":\"bad\\qescape\",\"type\":\"rectangle\"}]}"
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements(json))
    }

    @Test
    fun `deserializeElements degrades to empty on trailing content after the top-level value`() {
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("""{"version":1,"elements":[]}garbage"""))
    }

    @Test
    fun `deserializeElements degrades to empty on a truncated boolean or null literal`() {
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("tru"))
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("nul"))
    }

    @Test
    fun `deserializeElements parses an empty top-level object and an empty elements array without error`() {
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("{}"))
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("""{"version":1,"elements":[]}"""))
    }

    @Test
    fun `deserializeElements degrades to empty when an object key is not followed by a colon`() {
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("""{"version" 1}"""))
    }

    @Test
    fun `deserializeElements degrades to empty when an object is missing a comma between entries`() {
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("""{"version":1 "elements":[]}"""))
    }

    @Test
    fun `deserializeElements degrades to empty when an array is missing a comma between entries`() {
        val json = """{"version":1,"elements":[{"id":"a","type":"rectangle"} {"id":"b","type":"rectangle"}]}"""
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements(json))
    }

    @Test
    fun `deserializeElements degrades to empty on a truncated unicode escape`() {
        // Runtime document text is exactly `"\u12"` — a backslash-u escape with
        // only 2 of the required 4 hex digits before the string (and input) ends.
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("\"\\u12\""))
    }

    @Test
    fun `deserializeElements degrades to empty when a string ends immediately after a lone escaping backslash`() {
        // Runtime document text is exactly `"\` — nothing follows the backslash at all.
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("\"\\"))
    }

    @Test
    fun `deserializeElements degrades to empty when a string is never closed`() {
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("\"abc"))
    }

    @Test
    fun `deserializeElements degrades to empty on a malformed number literal`() {
        val json = """{"version":1,"elements":[{"id":"a","type":"rectangle","x":1.2.3}]}"""
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements(json))
    }

    @Test
    fun `deserializeElements degrades to empty when input runs out right where a specific character was expected`() {
        // Ends immediately after '{' — parseObject's very first inner call, `parseStringLiteral`'s
        // `expect('"')`, sees end-of-input rather than a mismatched-but-present character.
        assertEquals(emptyList<Element>(), SuperCanvasCore.deserializeElements("{"))
    }

    @Test
    fun `deserializeElements parses explicit true and false literal values without throwing`() {
        val json = """{"version":1,"elements":[{"id":"a","type":"rectangle","extraTrue":true,"extraFalse":false}]}"""
        assertEquals(listOf("a"), SuperCanvasCore.deserializeElements(json).map { it.id })
    }

    @Test
    fun `deserializeElements handles whitespace, negative numbers, and true-false-null literals without throwing`() {
        val json =
            "  { \"version\" : 1 , \"elements\" : [ { \"id\" : \"r1\" , \"type\" : \"rectangle\" , " +
                "\"x\" : -1.5 , \"y\" : 0 , \"width\" : 2 , \"height\" : 3 , \"startElementId\" : null } ] }  "
        val restored = SuperCanvasCore.deserializeElements(json)
        assertEquals(listOf(Element(id = "r1", type = "rectangle", x = -1.5, width = 2.0, height = 3.0)), restored)
    }

    // --- v1d: computeThumbnailTransform (FR12 note thumbnail) -----------------------

    @Test
    fun `computeThumbnailTransform returns an identity-ish transform for an empty element list`() {
        val transform = SuperCanvasCore.computeThumbnailTransform(emptyList(), thumbnailSizePx = 400.0, paddingPx = 24.0)
        assertEquals(0.0, transform.viewportX, 0.0001)
        assertEquals(0.0, transform.viewportY, 0.0001)
        assertEquals(1.0, transform.zoom, 0.0001)
    }

    @Test
    fun `computeThumbnailTransform fits a single box element within the padded thumbnail bounds`() {
        val box = Element(id = "a", type = "rectangle", x = 0.0, y = 0.0, width = 100.0, height = 100.0)
        val transform = SuperCanvasCore.computeThumbnailTransform(listOf(box), thumbnailSizePx = 400.0, paddingPx = 24.0)
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
        val wide = SuperCanvasCore.computeThumbnailTransform(listOf(a, b), thumbnailSizePx = 400.0, paddingPx = 24.0)
        val narrow = SuperCanvasCore.computeThumbnailTransform(listOf(a), thumbnailSizePx = 400.0, paddingPx = 24.0)
        // Spanning a and b is much larger content than a alone, so it must zoom out further.
        assertEquals(true, wide.zoom < narrow.zoom)
    }

    @Test
    fun `computeThumbnailTransform falls back to a minimum content span for a zero-size (point) element`() {
        val point = Element(id = "p", type = "rectangle", x = 5.0, y = 5.0, width = 0.0, height = 0.0)
        val transform = SuperCanvasCore.computeThumbnailTransform(listOf(point), thumbnailSizePx = 400.0, paddingPx = 24.0)
        // Would be infinite/NaN without the MIN_CONTENT_SPAN fallback (division by a zero span).
        assertEquals(true, transform.zoom.isFinite() && transform.zoom > 0.0)
    }

    @Test
    fun `computeThumbnailTransform includes a bound line's resolved (not stale) endpoints in the content bounds`() {
        val target = Element(id = "t", type = "rectangle", x = 1000.0, y = 1000.0, width = 10.0, height = 10.0)
        val arrow =
            Element(id = "arr", type = "arrow", startX = 0.0, startY = 0.0, endX = 1.0, endY = 1.0, endElementId = "t")
        val withBoundLine = SuperCanvasCore.computeThumbnailTransform(listOf(target, arrow), 400.0, 24.0)
        val targetOnly = SuperCanvasCore.computeThumbnailTransform(listOf(target), 400.0, 24.0)
        // The arrow's stale stored endX/endY (1,1) is near the target, so if the bound/live
        // endpoint (near 1000,1000, same as `target`) weren't used, both transforms would be
        // nearly identical. They must differ enough to prove the live position was included.
        assertEquals(true, kotlin.math.abs(withBoundLine.zoom - targetOnly.zoom) > 0.0001)
    }

    @Test
    fun `computeThumbnailTransform picks the more constraining axis for non-square content`() {
        val tall = Element(id = "tall", type = "rectangle", x = 0.0, y = 0.0, width = 10.0, height = 1000.0)
        val wide = Element(id = "wide", type = "rectangle", x = 0.0, y = 0.0, width = 1000.0, height = 10.0)
        val tallTransform = SuperCanvasCore.computeThumbnailTransform(listOf(tall), 400.0, 24.0)
        val wideTransform = SuperCanvasCore.computeThumbnailTransform(listOf(wide), 400.0, 24.0)
        // Both are constrained by their long axis to the same available span, so both should
        // scale down to about the same zoom (exercises both the width- and height-driven
        // branches of the `minOf(available / contentWidth, available / contentHeight)` choice).
        assertEquals(tallTransform.zoom, wideTransform.zoom, 0.0001)
    }

    // --- minimap: WorldRect / contentBounds / fitTransform / computeMinimapTransform ----

    @Test
    fun `WorldRect union covers both rects`() {
        val u = WorldRect(0.0, 0.0, 10.0, 10.0).union(WorldRect(-5.0, 20.0, 5.0, 30.0))
        assertEquals(WorldRect(-5.0, 0.0, 10.0, 30.0), u)
        assertEquals(15.0, u.width, 0.0001)
        assertEquals(30.0, u.height, 0.0001)
    }

    @Test
    fun `contentBounds is null for an empty element list`() {
        assertNull(SuperCanvasCore.contentBounds(emptyList()))
    }

    @Test
    fun `contentBounds spans boxes and a bound line's live endpoints`() {
        val box = Element(id = "b", type = "rectangle", x = -50.0, y = 10.0, width = 20.0, height = 30.0)
        val target = Element(id = "t", type = "rectangle", x = 200.0, y = 300.0, width = 10.0, height = 10.0)
        val arrow = Element(id = "a", type = "arrow", startX = 0.0, startY = 0.0, endX = 1.0, endY = 1.0, endElementId = "t")
        val bounds = SuperCanvasCore.contentBounds(listOf(box, target, arrow))!!
        assertEquals(-50.0, bounds.left, 0.0001)
        assertEquals(0.0, bounds.top, 0.0001) // the arrow's unbound start (0,0)
        assertEquals(210.0, bounds.right, 0.0001)
        assertEquals(310.0, bounds.bottom, 0.0001)
    }

    @Test
    fun `fitTransform centers content in a non-square box using the constraining axis`() {
        val fit = SuperCanvasCore.fitTransform(WorldRect(0.0, 0.0, 100.0, 100.0), boxWidthPx = 300.0, boxHeightPx = 200.0, paddingPx = 0.0)
        assertEquals(2.0, fit.zoom, 0.0001) // height constrains: 200px / 100 world units
        // 100 world units -> 200px inside a 300px-wide box leaves 50px on each side.
        assertEquals(50.0, (0.0 - fit.viewportX) * fit.zoom, 0.0001)
        assertEquals(0.0, (0.0 - fit.viewportY) * fit.zoom, 0.0001)
    }

    @Test
    fun `computeMinimapTransform fits the visible viewport alone when there is no content`() {
        val visible = WorldRect(100.0, 100.0, 400.0, 500.0)
        val fit = SuperCanvasCore.computeMinimapTransform(emptyList(), visible, 300.0, 400.0, 0.0)
        assertEquals(1.0, fit.zoom, 0.0001) // a 300x400 world rect into a 300x400 box
        assertEquals(100.0, fit.viewportX, 0.0001)
        assertEquals(100.0, fit.viewportY, 0.0001)
    }

    @Test
    fun `computeMinimapTransform keeps both content and a far-away viewport inside the minimap`() {
        val box = Element(id = "b", type = "rectangle", x = 0.0, y = 0.0, width = 100.0, height = 100.0)
        val visible = WorldRect(5000.0, 5000.0, 5300.0, 5400.0)
        val fit = SuperCanvasCore.computeMinimapTransform(listOf(box), visible, 300.0, 400.0, 10.0)
        for (x in listOf(box.x, visible.right)) {
            assertEquals(true, (x - fit.viewportX) * fit.zoom in 9.99..290.01)
        }
        for (y in listOf(box.y, visible.bottom)) {
            assertEquals(true, (y - fit.viewportY) * fit.zoom in 9.99..390.01)
        }
    }

    // --- rotation ----------------------------------------------------------------------

    // 100x20 bar centered at (50,10), rotated 90° clockwise: spans x 40..60, y -40..60.
    private val barRotated90 =
        Element(id = "bar", type = "rectangle", x = 0.0, y = 0.0, width = 100.0, height = 20.0, rotation = Math.PI / 2)

    private fun pointAtAngleFromUp(
        cx: Double,
        cy: Double,
        degreesClockwise: Double,
    ): Point {
        val a = Math.toRadians(degreesClockwise)
        return Point(cx + 100.0 * Math.sin(a), cy - 100.0 * Math.cos(a))
    }

    @Test
    fun `Element rejects a non-finite rotation`() {
        assertThrows(IllegalArgumentException::class.java) {
            Element(id = "x", type = "rectangle", width = 1.0, height = 1.0, rotation = Double.NaN)
        }
    }

    @Test
    fun `containsPoint respects rotation about the bbox center`() {
        assertEquals(true, barRotated90.containsPoint(50.0, 50.0))
        assertEquals(false, barRotated90.containsPoint(90.0, 10.0)) // inside only when unrotated
    }

    @Test
    fun `toWorld and toLocal are inverses`() {
        val world = barRotated90.toWorld(3.0, 7.0)
        val back = barRotated90.toLocal(world.x, world.y)
        assertEquals(3.0, back.x, 0.0001)
        assertEquals(7.0, back.y, 0.0001)
    }

    @Test
    fun `cornerPoints rotate with the element`() {
        val topLeft = SuperCanvasCore.cornerPoints(barRotated90)[0]
        // Local (0,0) rotated 90° clockwise about (50,10) lands at (60,-40).
        assertEquals(60.0, topLeft.x, 0.0001)
        assertEquals(-40.0, topLeft.y, 0.0001)
    }

    @Test
    fun `rotateElement points the handle at the pointer and snaps near 45-degree multiples`() {
        // resizeBase's rect "r" is at (10,10) 20x20, center (20,20).
        val right = SuperCanvasCore.rotateElement(resizeBase, "r", Point(100.0, 20.0)).elements.first()
        assertEquals(Math.PI / 2, right.rotation, 0.0001) // handle dragged to the right = 90° clockwise
        val above = SuperCanvasCore.rotateElement(resizeBase, "r", Point(20.0, -100.0)).elements.first()
        assertEquals(0.0, above.rotation, 0.0001)
        val near45 = SuperCanvasCore.rotateElement(resizeBase, "r", pointAtAngleFromUp(20.0, 20.0, 47.0)).elements.first()
        assertEquals(Math.PI / 4, near45.rotation, 0.0001) // within 5° of 45° snaps
        val at30 = SuperCanvasCore.rotateElement(resizeBase, "r", pointAtAngleFromUp(20.0, 20.0, 30.0)).elements.first()
        assertEquals(Math.toRadians(30.0), at30.rotation, 0.0001) // outside the snap window stays put
    }

    @Test
    fun `rotateElement is a no-op for a missing id or a line element`() {
        assertEquals(resizeBase.elements, SuperCanvasCore.rotateElement(resizeBase, "nope", Point(0.0, 0.0)).elements)
        assertEquals(endpointBase.elements, SuperCanvasCore.rotateElement(endpointBase, "arr", Point(99.0, 0.0)).elements)
    }

    @Test
    fun `snapRotation normalizes into the minus-pi to pi range`() {
        assertEquals(Math.PI / 2, SuperCanvasCore.snapRotation(Math.PI / 2 + 2 * Math.PI), 0.0001)
        assertEquals(-Math.toRadians(100.0), SuperCanvasCore.snapRotation(Math.toRadians(260.0)), 0.0001)
    }

    @Test
    fun `handleAt finds the rotate handle above a shape's top-center`() {
        // rect "r" top-center is (20,10); at zoom 1 the handle sits ROTATE_HANDLE_OFFSET_PX above it.
        val handleY = 10.0 - SuperCanvasCore.ROTATE_HANDLE_OFFSET_PX
        assertEquals(HandleTarget.RotateHandle, SuperCanvasCore.handleAt(resizeBase, "r", 20.0, handleY, 2.0))
    }

    @Test
    fun `resizeElement on a rotated shape keeps the opposite corner fixed in world space`() {
        val rotated = Element(id = "r", type = "rectangle", x = 0.0, y = 0.0, width = 100.0, height = 50.0, rotation = Math.PI / 6)
        val state = CanvasState(elements = listOf(rotated), viewportX = 0.0, viewportY = 0.0, zoom = 1.0)
        val topLeftBefore = SuperCanvasCore.cornerPoints(rotated)[0]
        val result = SuperCanvasCore.resizeElement(state, "r", Corner.BOTTOM_RIGHT, 150.0, 120.0).elements.first()
        val topLeftAfter = SuperCanvasCore.cornerPoints(result)[0]
        assertEquals(topLeftBefore.x, topLeftAfter.x, 0.0001)
        assertEquals(topLeftBefore.y, topLeftAfter.y, 0.0001)
        assertEquals(Math.PI / 6, result.rotation, 0.0001)
    }

    @Test
    fun `resizeElement on a rotated shape keeps the opposite corner fixed for the other three corners`() {
        val rotated = Element(id = "r", type = "rectangle", x = 0.0, y = 0.0, width = 100.0, height = 50.0, rotation = Math.PI / 6)
        val state = CanvasState(elements = listOf(rotated), viewportX = 0.0, viewportY = 0.0, zoom = 1.0)
        val before = SuperCanvasCore.cornerPoints(rotated) // TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
        // Each dragged corner moves outward along its diagonal; the diagonally-opposite corner must not move.
        val cases = listOf(Corner.TOP_LEFT to 3, Corner.TOP_RIGHT to 2, Corner.BOTTOM_LEFT to 1)
        for ((corner, oppositeIndex) in cases) {
            val dragged = before[3 - oppositeIndex]
            val fixed = before[oppositeIndex]
            val pointer = Point(dragged.x + (dragged.x - fixed.x) * 0.2, dragged.y + (dragged.y - fixed.y) * 0.2)
            val result = SuperCanvasCore.resizeElement(state, "r", corner, pointer.x, pointer.y).elements.first()
            val after = SuperCanvasCore.cornerPoints(result)[oppositeIndex]
            assertEquals(fixed.x, after.x, 0.0001)
            assertEquals(fixed.y, after.y, 0.0001)
        }
    }

    @Test
    fun `resolveArrowEndpoints projects onto a rotated shape's rotated edge`() {
        // The bar's local top edge now faces left; approaching from straight above hits
        // its rotated end at y = 10 - 50 = -40, not the unrotated top edge at y = 0.
        val arrow = Element(id = "a", type = "arrow", startX = 50.0, startY = -200.0, endX = 0.0, endY = 0.0, endElementId = "bar")
        val (_, end) = SuperCanvasCore.resolveArrowEndpoints(arrow, listOf(barRotated90, arrow))
        assertEquals(50.0, end.x, 0.001)
        assertEquals(-40.0, end.y, 0.001)
    }

    @Test
    fun `contentBounds uses a rotated shape's rotated corners`() {
        val bounds = SuperCanvasCore.contentBounds(listOf(barRotated90))!!
        assertEquals(40.0, bounds.left, 0.0001)
        assertEquals(-40.0, bounds.top, 0.0001)
        assertEquals(60.0, bounds.right, 0.0001)
        assertEquals(60.0, bounds.bottom, 0.0001)
    }

    @Test
    fun `serialization round-trips rotation and loads pre-rotation files unrotated`() {
        val restored = SuperCanvasCore.deserializeElements(SuperCanvasCore.serializeElements(listOf(barRotated90)))
        assertEquals(listOf(barRotated90), restored)
        val legacy = """{"version":1,"elements":[{"id":"old","type":"rectangle","x":0,"y":0,"width":5,"height":5}]}"""
        assertEquals(0.0, SuperCanvasCore.deserializeElements(legacy).first().rotation, 0.0)
    }

    // --- JSON codec edge cases: escapes, literals, malformed structure ------------------

    @Test
    fun `serialization round-trips ids containing carriage returns, tabs and other control characters`() {
        val tricky = Element(id = "a\rb\tcd", type = "rectangle")
        val json = SuperCanvasCore.serializeElements(listOf(tricky))
        assertEquals(true, json.contains("\\r") && json.contains("\\t") && json.contains("\\u0001"))
        assertEquals(listOf(tricky), SuperCanvasCore.deserializeElements(json))
    }

    @Test
    fun `deserializeElements reads slash and backspace escapes and ignores unknown boolean fields`() {
        val json = """{"version":1,"elements":[{"id":"a\/b\bc","type":"rectangle","locked":true,"hidden":false}]}"""
        assertEquals("a/b\bc", SuperCanvasCore.deserializeElements(json).single().id)
    }

    @Test
    fun `deserializeElements skips an empty element object and reads nested or empty unknown arrays`() {
        val json = """{"version":1,"elements":[{},{"id":"ok","type":"ellipse","tags":[[],[1,2]]}],"extra":[]}"""
        assertEquals(listOf("ok"), SuperCanvasCore.deserializeElements(json).map { it.id })
    }

    @Test
    fun `deserializeElements returns an empty list for malformed structure or a non-object document`() {
        val malformed =
            listOf(
                """{"version":1 "elements":[]}""", // missing comma between object members
                """{"version":1,"elements":[1 2]}""", // missing comma between array items
                """{"version":1,"elements":[{"id":"x","type":"rectangle","x":-}]}""", // invalid number
                """{"version":1,"elements":[{"id":"x","type":"rectangle","flag":tru}]}""", // bad literal
                "42", // a bare number, read right to the end of input
            )
        for (json in malformed) {
            assertEquals(json, emptyList<Element>(), SuperCanvasCore.deserializeElements(json))
        }
    }
}
