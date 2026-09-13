package com.snsupercanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Value-type invariants and frame conversions of the canvas model (CanvasModel.kt). */
class CanvasModelTest {
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

    // --- Element endpoint-field consistency -----------------------------------

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

    // --- Point structural equality (exercises its generated equals/hashCode) ----

    @Test
    fun `Point implements structural equality`() {
        assertEquals(Point(1.0, 2.0), Point(1.0, 2.0))
        assertEquals(false, Point(1.0, 2.0) == Point(2.0, 1.0))
        @Suppress("EqualsBetweenInconvertibleTypes")
        assertEquals(false, Point(1.0, 2.0).equals("not a point"))
    }

    // --- WorldRect / contentBounds / fitTransform / computeMinimapTransform ----

    @Test
    fun `WorldRect union covers both rects`() {
        val u = WorldRect(0.0, 0.0, 10.0, 10.0).union(WorldRect(-5.0, 20.0, 5.0, 30.0))
        assertEquals(WorldRect(-5.0, 0.0, 10.0, 30.0), u)
        assertEquals(15.0, u.width, 0.0001)
        assertEquals(30.0, u.height, 0.0001)
    }

    @Test
    fun `ViewTransform maps world to screen as (world - viewport) times zoom`() {
        val transform = ViewTransform(viewportX = 10.0, viewportY = -5.0, zoom = 2.0)
        assertEquals(10.0, transform.screenX(15.0), 0.0001)
        assertEquals(20.0, transform.screenY(5.0), 0.0001)
    }

    @Test
    fun `CanvasState exposes its pan and zoom as a ViewTransform`() {
        val state = baseState.copy(viewportX = 3.0, viewportY = 4.0, zoom = 1.5)
        assertEquals(ViewTransform(3.0, 4.0, 1.5), state.transform)
    }

    // --- tools -------------------------------------------------------------------------

    @Test
    fun `CanvasTools tells drawing tools from select and unknown ids`() {
        val tools = listOf(CanvasTools.SELECT, CanvasTools.RECTANGLE, CanvasTools.ELLIPSE, CanvasTools.LINE, CanvasTools.ARROW, "?")
        assertEquals(listOf(false, true, true, true, true, false), tools.map(CanvasTools::drawsElement))
    }

    @Test
    fun `CanvasTools tells connector tools from shape tools`() {
        val tools = listOf(CanvasTools.RECTANGLE, CanvasTools.ELLIPSE, CanvasTools.LINE, CanvasTools.ARROW)
        assertEquals(listOf(false, false, true, true), tools.map(CanvasTools::isConnector))
    }

    // --- rotation ----------------------------------------------------------------------

    @Test
    fun `Element rejects a non-finite rotation`() {
        for (rotation in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                Element(id = "x", type = "rectangle", width = 1.0, height = 1.0, rotation = rotation)
            }
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
}
