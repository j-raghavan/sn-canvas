package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The select tool's drags (FR7/FR9/FR24): what a touch on the selected element starts, and the edit each drag makes. */
class SelectGesturesTest {
    private val controller = CanvasController(fakeMeasurer, { "new" }, silentListener)
    private val gestures = SelectGestures({ controller.state }, { controller.selected }, controller::fitted, fakeMeasurer)
    private val box = Element(id = "box", type = "rectangle", width = 100.0, height = 100.0)
    private val line = Element(id = "line", type = "line", startX = 300.0, startY = 0.0, endX = 400.0, endY = 0.0)

    // Two 48-tall rows, from y = 500 to 596.
    private val table = TableElements.create("tb", Point(0.0, 500.0), Point(320.0, 596.0))

    private fun selecting(id: String?): SelectGestures {
        controller.load(listOf(box, line, table))
        controller.select(id)
        return gestures
    }

    private fun startAt(
        id: String?,
        x: Double,
        y: Double,
    ) = selecting(id).startAt(Point(x, y), 10.0)

    private fun edited(
        gesture: CanvasGesture?,
        id: String,
        pointer: Point,
    ) = selecting(id).edit(gesture, id, pointer)

    @Test
    fun `with nothing selected, every touch pans`() {
        assertEquals(CanvasGesture.Pan, startAt(null, 50.0, 50.0))
    }

    @Test
    fun `a corner handle resizes, the rotate handle rotates, and a connector's end re-points it`() {
        assertTrue(startAt("box", 0.0, 0.0) is CanvasGesture.Resize)
        val rotate = CanvasCore.rotateHandlePoint(box, 1.0)
        assertEquals(CanvasGesture.Rotate, startAt("box", rotate.x, rotate.y))
        assertEquals(CanvasGesture.DragEndpoint(Endpoint.END), startAt("line", 400.0, 0.0))
    }

    @Test
    fun `on a selected table, a row's edge resizes that row, and the rest of a cell moves the table`() {
        assertEquals(CanvasGesture.ResizeRow(0), startAt("tb", 100.0, 552.0))
        assertEquals(CanvasGesture.ResizeRow(1), startAt("tb", 100.0, 596.0))
        // Within the handles' reach but beyond half of it: the cell, not the edge.
        assertEquals(CanvasGesture.Move(), startAt("tb", 100.0, 556.0))
    }

    @Test
    fun `a touch off the selected element, or on a connector's body, pans`() {
        assertEquals(CanvasGesture.Pan, startAt("box", 200.0, 200.0))
        assertEquals(CanvasGesture.Pan, startAt("box", 350.0, 0.0))
        assertEquals(CanvasGesture.Pan, startAt("line", 350.0, 0.0))
        assertEquals(CanvasGesture.Move(), startAt("box", 50.0, 50.0))
    }

    @Test
    fun `each drag's edit is what its release commits`() {
        val moved = edited(CanvasGesture.Move(5.0, 6.0), "box", Point(0.0, 0.0))!!.elements.first { it.id == "box" }
        assertEquals(listOf(5.0, 6.0), listOf(moved.x, moved.y))
        val state = controller.state
        assertEquals(
            controller.fitted(CanvasCore.resizeElement(state, "box", Corner.BOTTOM_RIGHT, 150.0, 150.0), "box"),
            edited(CanvasGesture.Resize(Corner.BOTTOM_RIGHT), "box", Point(150.0, 150.0)),
        )
        assertEquals(
            CanvasCore.rotateElement(state, "box", Point(150.0, 50.0)),
            edited(CanvasGesture.Rotate, "box", Point(150.0, 50.0)),
        )
        assertEquals(
            CanvasCore.moveEndpoint(state, "line", Endpoint.END, Point(420.0, 30.0), null),
            edited(CanvasGesture.DragEndpoint(Endpoint.END), "line", Point(420.0, 30.0)),
        )
        // Dropped on the box, the line's end binds to it.
        assertEquals(
            CanvasCore.moveEndpoint(state, "line", Endpoint.END, Point(50.0, 50.0), "box"),
            edited(CanvasGesture.DragEndpoint(Endpoint.END), "line", Point(50.0, 50.0)),
        )
        assertNull(edited(CanvasGesture.Pan, "box", Point(0.0, 0.0)))
        assertNull(edited(null, "box", Point(0.0, 0.0)))
    }

    @Test
    fun `dragging a row's edge resizes that row, and resizing a table stretches every row`() {
        val row = edited(CanvasGesture.ResizeRow(0), "tb", Point(100.0, 600.0))!!.elements.single { it.id == "tb" }
        assertEquals(listOf(100.0, 48.0), row.table?.rowMinHeights)
        val stretched = edited(CanvasGesture.Resize(Corner.BOTTOM_RIGHT), "tb", Point(320.0, 692.0))!!.elements.single { it.id == "tb" }
        val heights = stretched.table!!.rowMinHeights
        assertEquals(heights[0], heights[1], 1e-9)
        assertEquals(stretched.height, heights.sum(), 1e-9)
        assertTrue(stretched.height > 96.0)
    }
}
