package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Editing and geometry operations: hit-testing, pan/zoom, insert/move/delete, handles, connectors. */
class CanvasCoreTest {
    @Test
    fun `hitTest returns topmost overlapping element`() {
        val hit = CanvasCore.hitTest(worldX = 7.0, worldY = 7.0, elements = baseState.elements)
        assertEquals("b", hit?.id)
    }

    @Test
    fun `hitTest returns null when no element contains the point`() {
        val hit = CanvasCore.hitTest(worldX = 100.0, worldY = 100.0, elements = baseState.elements)
        assertNull(hit)
    }

    @Test
    fun `moveElements moves every selected element, a connector by its free endpoints`() {
        val arrow =
            Element(id = "arr", type = "arrow", startX = 0.0, startY = 0.0, endX = 50.0, endY = 50.0)
        val state = baseState.copy(elements = baseState.elements + arrow)
        val moved = CanvasCore.moveElements(state, setOf("a", "arr"), dx = 5.0, dy = -2.0)
        val elements = moved.elements.associateBy { it.id }
        assertEquals(5.0, elements.getValue("a").x, 1e-9)
        assertEquals(-2.0, elements.getValue("a").y, 1e-9)
        assertEquals(5.0, elements.getValue("arr").startX!!, 1e-9)
        assertEquals(55.0, elements.getValue("arr").endX!!, 1e-9)
        // Not selected, so untouched.
        assertEquals(5.0, elements.getValue("b").x, 1e-9)
    }

    @Test
    fun `moveElements leaves an endpoint bound to an element that is moving too, which carries it`() {
        val arrow =
            Element(id = "arr", type = "arrow", startX = 0.0, startY = 0.0, endX = 5.0, endY = 5.0, endElementId = "b")
        val state = baseState.copy(elements = baseState.elements + arrow)
        val moved = CanvasCore.moveElements(state, setOf("b", "arr"), dx = 10.0, dy = 0.0)
        val shifted = moved.elements.single { it.id == "arr" }
        // The free start shifts; the bound end does not, or it would travel twice with the shape it follows.
        assertEquals(10.0, shifted.startX!!, 1e-9)
        assertEquals(5.0, shifted.endX!!, 1e-9)
    }

    @Test
    fun `elementsIn takes everything the rectangle touches, whole or in part`() {
        val inside = WorldRect(left = -1.0, top = -1.0, right = 20.0, bottom = 20.0)
        assertEquals(listOf("a", "b"), CanvasCore.elementsIn(inside, baseState.elements).map { it.id })
        // Touching a corner is enough.
        val corner = WorldRect(left = 10.0, top = 10.0, right = 12.0, bottom = 12.0)
        assertEquals(listOf("a", "b"), CanvasCore.elementsIn(corner, baseState.elements).map { it.id })
        val away = WorldRect(left = 500.0, top = 500.0, right = 600.0, bottom = 600.0)
        assertEquals(emptyList<String>(), CanvasCore.elementsIn(away, baseState.elements).map { it.id })
    }

    @Test
    fun `elementsIn judges a bound connector where it is drawn, not where it was stored`() {
        val arrow =
            Element(id = "arr", type = "arrow", startX = 0.0, startY = 0.0, endX = 1.0, endY = 1.0, endElementId = "b")
        // Past the stored (1,1) end, but on the way to the shape the end is bound to.
        val pastTheStoredEnd = WorldRect(left = 3.0, top = 3.0, right = 6.0, bottom = 6.0)
        assertEquals(true, CanvasCore.elementsIn(pastTheStoredEnd, baseState.elements + arrow).any { it.id == "arr" })
        // The same arrow unbound reaches only as far as it was stored, so the rectangle misses it.
        val unbound = arrow.copy(endElementId = null)
        assertEquals(emptyList<String>(), CanvasCore.elementsIn(pastTheStoredEnd, listOf(unbound)).map { it.id })
    }

    @Test
    fun `linkGlyphAt answers for a linked element's glyph, and for nothing else`() {
        val link = ElementLink(ElementLink.KIND_NOTE, "/n.note")
        val linked = baseState.elements[0].copy(link = link)
        val elements = listOf(linked, baseState.elements[1])
        val glyph = CanvasCore.linkGlyphPoint(linked, elements, zoom = 1.0)
        assertEquals("a", CanvasCore.linkGlyphAt(glyph.x, glyph.y, elements, 1.0, 4.0)?.id)
        // Away from the glyph, and on an element that links nowhere.
        assertNull(CanvasCore.linkGlyphAt(glyph.x + 50, glyph.y, elements, 1.0, 4.0))
        assertNull(CanvasCore.linkGlyphAt(glyph.x, glyph.y, baseState.elements, 1.0, 4.0))
    }

    @Test
    fun `a link glyph keeps its distance from the shape on screen, however far the canvas is zoomed`() {
        val linked = baseState.elements[0].copy(link = ElementLink(ElementLink.KIND_NOTE, "/n.note"))
        val near = CanvasCore.linkGlyphPoint(linked, listOf(linked), zoom = 1.0)
        val zoomedOut = CanvasCore.linkGlyphPoint(linked, listOf(linked), zoom = 0.25)
        // Four times as far in world units at a quarter of the zoom is the same gap in pixels.
        val corner = linked.x + linked.width
        assertEquals(4 * (near.x - corner), zoomedOut.x - corner, 1e-9)
    }

    @Test
    fun `an ellipse carries its glyph off its own curve, not off the corner of the box around it`() {
        val ellipse =
            Element(id = "ell", type = CanvasTools.ELLIPSE, x = 0.0, y = 0.0, width = 200.0, height = 200.0)
                .copy(link = ElementLink(ElementLink.KIND_NOTE, "/n.note"))
        val glyph = CanvasCore.linkGlyphPoint(ellipse, listOf(ellipse), zoom = 1.0)
        // The curve's top-right diagonal point, then the same gap out from it that a rectangle's corner gets.
        val reach = 100.0 * Math.sqrt(2.0) / 2
        assertEquals(100.0 + reach + CanvasCore.LINK_GLYPH_OFFSET_PX, glyph.x, 1e-9)
        assertEquals(100.0 - reach - CanvasCore.LINK_GLYPH_OFFSET_PX, glyph.y, 1e-9)
        // Which is well inside the box's corner, where it used to sit adrift of the shape.
        assertEquals(true, glyph.x < ellipse.x + ellipse.width + CanvasCore.LINK_GLYPH_OFFSET_PX)
    }

    @Test
    fun `a rectangle still carries its glyph off the corner of the box around it`() {
        val rect =
            Element(id = "rec", type = CanvasTools.RECTANGLE, x = 0.0, y = 0.0, width = 200.0, height = 200.0)
                .copy(link = ElementLink(ElementLink.KIND_NOTE, "/n.note"))
        val glyph = CanvasCore.linkGlyphPoint(rect, listOf(rect), zoom = 1.0)
        assertEquals(200.0 + CanvasCore.LINK_GLYPH_OFFSET_PX, glyph.x, 1e-9)
        assertEquals(-CanvasCore.LINK_GLYPH_OFFSET_PX, glyph.y, 1e-9)
    }

    @Test
    fun `a connector carries its glyph at the end it points to`() {
        val arrow =
            Element(id = "arr", type = "arrow", startX = 0.0, startY = 0.0, endX = 40.0, endY = 60.0)
                .copy(link = ElementLink(ElementLink.KIND_NOTE, "/n.note"))
        assertEquals(Point(40.0, 60.0), CanvasCore.linkGlyphPoint(arrow, listOf(arrow), zoom = 1.0))
    }

    @Test
    fun `a connector bound to a shape carries its glyph where it ends now, not where it once did`() {
        val arrow =
            Element(id = "arr", type = "arrow", startX = 0.0, startY = 0.0, endX = 1.0, endY = 1.0, endElementId = "b")
                .copy(link = ElementLink(ElementLink.KIND_NOTE, "/n.note"))
        val elements = baseState.elements + arrow
        // Where the arrow actually ends: on the bound shape's edge, as everything else draws it.
        val (_, end) = CanvasCore.resolveArrowEndpoints(arrow, elements)
        assertEquals(end, CanvasCore.linkGlyphPoint(arrow, elements, zoom = 1.0))
        // And the glyph is found there, not at the endpoint the element still carries.
        assertEquals("arr", CanvasCore.linkGlyphAt(end.x, end.y, elements, 1.0, 4.0)?.id)
    }

    @Test
    fun `panBy shifts viewport opposite the pan delta`() {
        val panned = CanvasCore.panBy(baseState, dx = 5.0, dy = -3.0)
        assertEquals(-5.0, panned.viewportX, 0.0001)
        assertEquals(3.0, panned.viewportY, 0.0001)
    }

    @Test
    fun `zoomTo doubles zoom and keeps the focal point stationary`() {
        val zoomed = CanvasCore.zoomTo(baseState, scaleFactor = 2.0, focalWorldX = 10.0, focalWorldY = 10.0)
        assertEquals(2.0, zoomed.zoom, 0.0001)
        // Focal point (10,10) should map to the same screen position before/after:
        // (focal - viewport_old) * zoom_old == (focal - viewport_new) * zoom_new
        val before = (10.0 - baseState.viewportX) * baseState.zoom
        val after = (10.0 - zoomed.viewportX) * zoomed.zoom
        assertEquals(before, after, 0.0001)
    }

    @Test
    fun `zoomTo clamps to MIN_ZOOM and MAX_ZOOM`() {
        val zoomedOut = CanvasCore.zoomTo(baseState, scaleFactor = 0.0001, focalWorldX = 0.0, focalWorldY = 0.0)
        assertEquals(CanvasCore.MIN_ZOOM, zoomedOut.zoom, 0.0001)

        val zoomedIn = CanvasCore.zoomTo(baseState, scaleFactor = 1000.0, focalWorldX = 0.0, focalWorldY = 0.0)
        assertEquals(CanvasCore.MAX_ZOOM, zoomedIn.zoom, 0.0001)
    }

    @Test
    fun `hitTest on an empty element list returns null`() {
        assertNull(CanvasCore.hitTest(worldX = 0.0, worldY = 0.0, elements = emptyList()))
    }

    @Test
    fun `zoomTo rejects a non-positive scaleFactor`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanvasCore.zoomTo(baseState, scaleFactor = 0.0, focalWorldX = 0.0, focalWorldY = 0.0)
        }
    }

    @Test
    fun `insertElement appends to the end of the list`() {
        val newElement = Element(id = "c", type = "ellipse", x = 1.0, y = 1.0, width = 2.0, height = 2.0)
        val result = CanvasCore.insertElement(baseState, newElement)
        assertEquals(3, result.elements.size)
        assertEquals("c", result.elements.last().id)
    }

    @Test
    fun `insertElement into an empty-element state yields a single-element list`() {
        val empty = baseState.copy(elements = emptyList())
        val newElement = Element(id = "solo", type = "rect", x = 0.0, y = 0.0, width = 1.0, height = 1.0)
        val result = CanvasCore.insertElement(empty, newElement)
        assertEquals(listOf("solo"), result.elements.map { it.id })
    }

    @Test
    fun `deleteElement removes the matching element by id`() {
        val result = CanvasCore.deleteElement(baseState, id = "a")
        assertEquals(listOf("b"), result.elements.map { it.id })
    }

    @Test
    fun `deleteElement is a no-op when the id does not exist`() {
        val result = CanvasCore.deleteElement(baseState, id = "nonexistent")
        assertEquals(baseState.elements, result.elements)
    }

    @Test
    fun `deleteElement on an empty element list is a no-op`() {
        val empty = baseState.copy(elements = emptyList())
        val result = CanvasCore.deleteElement(empty, id = "anything")
        assertEquals(emptyList<Element>(), result.elements)
    }

    @Test
    fun `moveElement translates only the matching element by dx dy`() {
        val result = CanvasCore.moveElement(baseState, id = "a", dx = 3.0, dy = -2.0)
        val moved = result.elements.first { it.id == "a" }
        val untouched = result.elements.first { it.id == "b" }
        assertEquals(3.0, moved.x, 0.0001)
        assertEquals(-2.0, moved.y, 0.0001)
        assertEquals(baseState.elements.first { it.id == "b" }.x, untouched.x, 0.0001)
        assertEquals(baseState.elements.first { it.id == "b" }.y, untouched.y, 0.0001)
    }

    @Test
    fun `moveElement is a no-op when the id does not exist`() {
        val result = CanvasCore.moveElement(baseState, id = "nonexistent", dx = 5.0, dy = 5.0)
        assertEquals(baseState.elements, result.elements)
    }

    @Test
    fun `moveElement preserves element size and type`() {
        val result = CanvasCore.moveElement(baseState, id = "a", dx = 1.0, dy = 1.0)
        val moved = result.elements.first { it.id == "a" }
        assertEquals(10.0, moved.width, 0.0001)
        assertEquals(10.0, moved.height, 0.0001)
        assertEquals("rect", moved.type)
    }

    // --- cornerPoints ----------------------------------------------------------

    @Test
    fun `cornerPoints returns the four bbox corners in TOP_LEFT,TOP_RIGHT,BOTTOM_LEFT,BOTTOM_RIGHT order`() {
        val el = Element(id = "x", type = "rect", x = 10.0, y = 20.0, width = 5.0, height = 8.0)
        val points = CanvasCore.cornerPoints(el)
        assertEquals(listOf(10.0, 15.0, 10.0, 15.0), points.map { it.x })
        assertEquals(listOf(20.0, 20.0, 28.0, 28.0), points.map { it.y })
    }

    // --- resizeElement (FR9) -----------------------------------------------------

    @Test
    fun `handleAt returns null when the selected element id does not exist`() {
        assertNull(CanvasCore.handleAt(resizeBase, "nonexistent", 0.0, 0.0, toleranceWorld = 5.0))
    }

    @Test
    fun `handleAt finds each bbox corner handle within tolerance`() {
        assertEquals(HandleTarget.CornerHandle(Corner.TOP_LEFT), CanvasCore.handleAt(resizeBase, "r", 10.0, 10.0, 2.0))
        assertEquals(HandleTarget.CornerHandle(Corner.TOP_RIGHT), CanvasCore.handleAt(resizeBase, "r", 30.0, 10.0, 2.0))
        assertEquals(HandleTarget.CornerHandle(Corner.BOTTOM_LEFT), CanvasCore.handleAt(resizeBase, "r", 10.0, 30.0, 2.0))
        assertEquals(HandleTarget.CornerHandle(Corner.BOTTOM_RIGHT), CanvasCore.handleAt(resizeBase, "r", 30.0, 30.0, 2.0))
    }

    @Test
    fun `handleAt returns null for a bbox element when the point is far from every corner`() {
        assertNull(CanvasCore.handleAt(resizeBase, "r", 20.0, 20.0, toleranceWorld = 2.0))
    }

    @Test
    fun `handleAt finds a line element's start and end endpoint handles`() {
        assertEquals(HandleTarget.EndpointHandle(Endpoint.START), CanvasCore.handleAt(endpointBase, "arr", 0.0, 0.0, 2.0))
        assertEquals(HandleTarget.EndpointHandle(Endpoint.END), CanvasCore.handleAt(endpointBase, "arr", 10.0, 10.0, 2.0))
    }

    @Test
    fun `handleAt returns null for a line element when the point is far from both endpoints`() {
        assertNull(CanvasCore.handleAt(endpointBase, "arr", 5.0, 5.0, toleranceWorld = 2.0))
    }

    @Test
    fun `handleAt uses a bound line's live resolved endpoint position, not its stale stored coordinate`() {
        val boundArrow =
            Element(id = "arr", type = "arrow", startX = 999.0, startY = 999.0, endX = 50.0, endY = 5.0, startElementId = "boxA")
        val state = CanvasState(elements = listOf(boxA, boundArrow), viewportX = 0.0, viewportY = 0.0, zoom = 1.0)
        // boxA (center 5,5, half-extents 5,5) projected toward the fixed end (50,5) —
        // a point due right of center — lands on boxA's right-edge midpoint (10,5),
        // not the stale stored (999,999) and not boxA's raw center either.
        assertEquals(HandleTarget.EndpointHandle(Endpoint.START), CanvasCore.handleAt(state, "arr", 10.0, 5.0, 2.0))
    }

    @Test
    fun `cornerPoints rotate with the element`() {
        val topLeft = CanvasCore.cornerPoints(barRotated90)[0]
        // Local (0,0) rotated 90° clockwise about (50,10) lands at (60,-40).
        assertEquals(60.0, topLeft.x, 0.0001)
        assertEquals(-40.0, topLeft.y, 0.0001)
    }

    @Test
    fun `handleAt finds the rotate handle above a shape's top-center`() {
        // rect "r" top-center is (20,10); at zoom 1 the handle sits ROTATE_HANDLE_OFFSET_PX above it.
        val handleY = 10.0 - CanvasCore.ROTATE_HANDLE_OFFSET_PX
        assertEquals(HandleTarget.RotateHandle, CanvasCore.handleAt(resizeBase, "r", 20.0, handleY, 2.0))
    }

    @Test
    fun `boxFromDrag spans the drag in whichever direction it went`() {
        val box = CanvasCore.boxFromDrag("n", "ellipse", from = Point(30.0, 5.0), to = Point(10.0, 25.0))
        assertEquals(Element(id = "n", type = "ellipse", x = 10.0, y = 5.0, width = 20.0, height = 20.0), box)
    }

    // --- the eraser's hit test, and freehand strokes -----------------------------------

    @Test
    fun `hitsAt returns every element under the point, bottom to top`() {
        assertEquals(listOf("a", "b"), CanvasCore.hitsAt(7.0, 7.0, baseState.elements).map { it.id })
        assertEquals(emptyList<Element>(), CanvasCore.hitsAt(100.0, 100.0, baseState.elements))
    }

    @Test
    fun `a freehand stroke is hit near its line, not anywhere in its bounds`() {
        val stroke =
            StrokeElements.fromSamples("s", listOf(StrokePoint(0.0, 0.0, 1.0), StrokePoint(100.0, 100.0, 1.0))) ?: error("no stroke")
        assertEquals("s", CanvasCore.hitTest(50.0, 50.0, listOf(stroke))?.id)
        assertNull(CanvasCore.hitTest(90.0, 10.0, listOf(stroke)))
    }
}
