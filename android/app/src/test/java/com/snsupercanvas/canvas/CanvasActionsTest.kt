package com.snsupercanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Z-order, duplicate and restyle (FR18/FR19). */
class CanvasActionsTest {
    private val a = Element(id = "a", type = "rectangle", x = 0.0, y = 0.0, width = 10.0, height = 10.0)
    private val b = a.copy(id = "b", x = 50.0)
    private val c = a.copy(id = "c", x = 100.0)
    private val state = CanvasState(elements = listOf(a, b, c), viewportX = 0.0, viewportY = 0.0, zoom = 1.0)

    @Test
    fun `bringToFront and sendToBack reorder one element`() {
        assertEquals(listOf("b", "c", "a"), CanvasActions.bringToFront(state, "a").elements.map { it.id })
        assertEquals(listOf("c", "a", "b"), CanvasActions.sendToBack(state, "c").elements.map { it.id })
    }

    @Test
    fun `an unknown id changes nothing`() {
        assertSame(state, CanvasActions.bringToFront(state, "x"))
        assertSame(state, CanvasActions.sendToBack(state, "x"))
        assertSame(state, CanvasActions.duplicate(state, "x", "copy", 16.0))
        assertEquals(state.elements, CanvasActions.restyle(state, "x", ShapeStyle.DEFAULT).elements)
    }

    @Test
    fun `duplicate places an offset copy on top, with the original's style`() {
        val styled = state.copy(elements = listOf(a.copy(style = ShapeStyle.DEFAULT.copy(color = StyleColor.RED)), b))
        val result = CanvasActions.duplicate(styled, "a", "a2", 16.0)
        assertEquals(listOf("a", "b", "a2"), result.elements.map { it.id })
        val copy = result.elements.last()
        assertEquals(Point(16.0, 16.0), Point(copy.x, copy.y))
        assertEquals(StyleColor.RED, copy.style.color)
    }

    @Test
    fun `a duplicated connector keeps its route but not its bindings`() {
        val arrow = Element(id = "arr", type = "arrow", startX = 5.0, startY = 5.0, endX = 55.0, endY = 5.0, endElementId = "b")
        val withArrow = state.copy(elements = listOf(a, b, arrow))
        val (_, resolvedEnd) = SuperCanvasCore.resolveArrowEndpoints(arrow, withArrow.elements)
        val copy = CanvasActions.duplicate(withArrow, "arr", "arr2", 10.0).elements.last()
        assertNull(copy.endElementId)
        assertEquals(listOf(15.0, 15.0, resolvedEnd.x + 10.0, resolvedEnd.y + 10.0), listOf(copy.startX, copy.startY, copy.endX, copy.endY))
    }

    @Test
    fun `restyle changes only the named element`() {
        val red = ShapeStyle.DEFAULT.copy(color = StyleColor.RED)
        val result = CanvasActions.restyle(state, "b", red)
        assertEquals(listOf(ShapeStyle.LEGACY, red, ShapeStyle.LEGACY), result.elements.map { it.style })
    }
}
