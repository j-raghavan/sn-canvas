package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the canvas shows while a gesture runs, as opposed to what it holds. */
class CanvasFrameTest {
    private val controller = CanvasController(fakeMeasurer, { "id" }, silentListener)
    private val frame =
        CanvasFrame(
            controller,
            SelectGestures({ controller.state }, { controller.selectedElements }, controller::fitted, fakeMeasurer),
        )
    private val pointer = Point(60.0, 40.0)

    private fun box(id: String) = Element(id = id, type = "rectangle", width = 10.0, height = 10.0)

    @Test
    fun `with nothing going on it shows what the canvas holds`() {
        controller.insert(box("a"))
        assertEquals(controller.state.elements, frame.elements(null, pointer))
        assertEquals(controller.state.elements, frame.elements(CanvasGesture.Pan, pointer))
    }

    @Test
    fun `what the eraser is about to take is faded, and nothing else is`() {
        controller.insert(box("a"))
        controller.insert(box("b"))
        val shown = frame.elements(CanvasGesture.Erase(mutableSetOf("a")), pointer)
        assertEquals(0.2, shown.first { it.id == "a" }.style.opacity, 1e-9)
        assertEquals(1.0, shown.first { it.id == "b" }.style.opacity, 1e-9)
    }

    @Test
    fun `a move previews the whole selection, and a resize the one element being dragged`() {
        controller.insert(box("a"))
        controller.insert(box("b"))
        controller.selectAll(setOf("a", "b"))
        val moved = frame.elements(CanvasGesture.Move(5.0, 5.0), pointer)
        assertEquals(listOf(5.0, 5.0), moved.map { it.x })
        // The canvas itself is untouched until the gesture commits.
        assertEquals(listOf(0.0, 0.0), controller.state.elements.map { it.x })
        controller.select("a")
        val resized = frame.elements(CanvasGesture.Resize(Corner.BOTTOM_RIGHT), pointer)
        assertEquals(60.0, resized.first { it.id == "a" }.width, 1e-9)
    }

    @Test
    fun `text under an open editor is named as hidden, and nothing is while none is open`() {
        assertNull(frame.hiddenText())
        controller.insert(TextElements.createText("t", Point(0.0, 0.0)))
        controller.beginEdit(CanvasController.EditTarget("t", cellIndex = 2))
        assertEquals(ElementPainter.HiddenText("t", 2), frame.hiddenText())
    }

    @Test
    fun `the pencil's live stroke comes in the style new elements get, and an empty one is no stroke`() {
        controller.setStyle("opacity", "0.4")
        val stroke = frame.liveStroke(CanvasGesture.Freehand(mutableListOf(StrokePoint(0.0, 0.0, 0.5), StrokePoint(9.0, 9.0, 0.5))))
        assertNotNull(stroke)
        assertEquals(controller.currentStyle, stroke!!.style)
        assertEquals(0.4, stroke.style.opacity, 1e-9)
        assertTrue(stroke.points.orEmpty().isNotEmpty())
        assertNull(frame.liveStroke(CanvasGesture.Freehand(mutableListOf())))
    }
}
