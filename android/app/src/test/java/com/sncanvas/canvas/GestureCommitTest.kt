package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each finished gesture leaves on the canvas, and what a tap leaves that a drag does not. */
class GestureCommitTest {
    private class Ink : GestureCommit.StrokeInk {
        var wipes = 0
        var kept = 0

        override fun keepInk(commit: () -> Unit) {
            kept++
            commit()
        }

        override fun wipe() {
            wipes++
        }
    }

    private class Host(
        var tool: String = CanvasTools.RECTANGLE,
    ) : GestureCommit.Host {
        var ids = 0
        val followed = mutableListOf<ElementLink>()

        override fun newElementId(): String = "new-${++ids}"

        override fun toolMode(): String = tool

        override fun followLink(link: ElementLink) {
            followed += link
        }
    }

    private val ink = Ink()
    private val host = Host()
    private val controller = CanvasController(fakeMeasurer, { "id" }, silentListener)
    private val commits =
        GestureCommit(
            controller,
            SelectGestures({ controller.state }, { controller.selectedElements }, controller::fitted, fakeMeasurer),
            fakeMeasurer,
            ink,
            host,
        )
    private val origin = Point(0.0, 0.0)
    private val away = Point(40.0, 30.0)
    private val drag = 50.0
    private val minimap =
        MinimapLayout(
            left = 0.0,
            top = 0.0,
            width = 10.0,
            height = 10.0,
            visible = WorldRect(0.0, 0.0, 10.0, 10.0),
            fit = ViewTransform(0.0, 0.0, 1.0),
        )

    @Test
    fun `a drag with a shape tool leaves the shape it drew, and a tap leaves nothing`() {
        commits.commit(CanvasGesture.DrawShape, origin, away, drag)
        assertEquals(listOf("rectangle"), controller.state.elements.map { it.type })
        commits.commit(CanvasGesture.DrawShape, origin, Point(1.0, 1.0), GestureCommit.MIN_SHAPE_DRAG_PX)
        assertEquals(1, controller.state.elements.size)
    }

    @Test
    fun `a drag with the table tool leaves a table, and with a connector a connector`() {
        host.tool = CanvasTools.TABLE
        commits.commit(CanvasGesture.DrawShape, origin, Point(320.0, 96.0), drag)
        assertEquals(1, controller.state.elements.count { it.table != null })
        host.tool = CanvasTools.ARROW
        commits.commit(CanvasGesture.DrawShape, origin, away, drag)
        assertTrue(controller.state.elements.any { it.hasEndpoints() })
    }

    @Test
    fun `a tap selects what it landed on, and a drag selects nothing`() {
        controller.insert(Element(id = "box", type = "rectangle", width = 20.0, height = 20.0))
        commits.commit(CanvasGesture.Pan, origin, Point(10.0, 10.0), 0.0)
        assertEquals("box", controller.selected?.id)
        controller.select(null)
        commits.commit(CanvasGesture.Pan, origin, Point(10.0, 10.0), drag)
        assertNull(controller.selected)
    }

    @Test
    fun `a marquee selects everything it covered, and one that covered nothing clears the selection`() {
        controller.insert(Element(id = "a", type = "rectangle", width = 10.0, height = 10.0))
        controller.insert(Element(id = "b", type = "rectangle", x = 100.0, y = 100.0, width = 10.0, height = 10.0))
        commits.commit(CanvasGesture.Marquee, Point(-5.0, -5.0), Point(50.0, 50.0), drag)
        assertEquals(setOf("a"), controller.selectedElements.map { it.id }.toSet())
        commits.commit(CanvasGesture.Marquee, Point(500.0, 500.0), Point(600.0, 600.0), drag)
        assertEquals(emptySet<String>(), controller.selectedElements.map { it.id }.toSet())
    }

    @Test
    fun `a tap on a link glyph follows the link, and a drag off it changes nothing`() {
        val link = ElementLink(ElementLink.KIND_NOTE, "/n.note")
        controller.insert(Element(id = "box", type = "rectangle", width = 10.0, height = 10.0).copy(link = link))
        commits.commit(CanvasGesture.FollowLink("box"), origin, origin, 0.0)
        assertEquals(listOf(link), host.followed)
        commits.commit(CanvasGesture.FollowLink("box"), origin, away, drag)
        assertEquals(listOf(link), host.followed)
        // An element that has gone, or never linked anywhere, leads nowhere.
        commits.commit(CanvasGesture.FollowLink("gone"), origin, origin, 0.0)
        assertEquals(listOf(link), host.followed)
    }

    @Test
    fun `a pencil stroke is kept with its ink, and one that sampled nothing takes the ink off`() {
        val samples = (0..8).map { StrokePoint(it * 10.0, it * 10.0, 0.5) }
        commits.commit(CanvasGesture.Freehand(samples.toMutableList()), origin, away, drag)
        assertEquals(1, ink.kept)
        assertEquals(listOf(CanvasTools.DRAW), controller.state.elements.map { it.type })
        commits.commit(CanvasGesture.Freehand(mutableListOf()), origin, origin, 0.0)
        assertEquals(1, ink.wipes)
        assertEquals(1, controller.state.elements.size)
    }

    @Test
    fun `the eraser takes what it swept, and a minimap drag leaves the canvas as it was`() {
        controller.insert(Element(id = "box", type = "rectangle", width = 10.0, height = 10.0))
        commits.commit(CanvasGesture.Erase(mutableSetOf("box")), origin, away, drag)
        assertEquals(emptyList<Element>(), controller.state.elements)
        commits.commit(CanvasGesture.MinimapDrag(minimap, 0.0, 0.0), origin, away, drag)
        assertEquals(emptyList<Element>(), controller.state.elements)
        commits.commit(null, origin, away, drag)
        assertEquals(emptyList<Element>(), controller.state.elements)
    }

    @Test
    fun `a drag moves the selection, and a tap on selected text opens the editor instead`() {
        controller.insert(TextElements.createText("t", origin).copy(text = "hello"))
        controller.select("t")
        commits.commit(CanvasGesture.Move(5.0, 7.0), origin, away, drag)
        assertEquals(5.0, controller.selected!!.x, 1e-9)
        commits.commit(CanvasGesture.Move(0.0, 0.0), origin, Point(6.0, 1.0), 0.0)
        assertEquals("t", controller.editing?.elementId)
    }

    @Test
    fun `placing text needs a tap, and with nothing selected an edit gesture commits nothing`() {
        host.tool = CanvasTools.TEXT
        commits.commit(CanvasGesture.PlaceText, origin, origin, drag)
        assertEquals(emptyList<Element>(), controller.state.elements)
        commits.commit(CanvasGesture.PlaceText, origin, origin, 0.0)
        assertEquals(1, controller.state.elements.size)
        controller.select(null)
        commits.commit(CanvasGesture.Rotate, origin, away, drag)
        assertEquals(1, controller.state.elements.size)
    }

    @Test
    fun `a tap on empty canvas clears the selection, and a glyph with no link leads nowhere`() {
        controller.insert(Element(id = "box", type = "rectangle", width = 10.0, height = 10.0))
        controller.select("box")
        commits.commit(CanvasGesture.Pan, origin, Point(900.0, 900.0), 0.0)
        assertNull(controller.selected)
        commits.commit(CanvasGesture.FollowLink("box"), origin, origin, 0.0)
        assertEquals(emptyList<ElementLink>(), host.followed)
    }

    @Test
    fun `resize, rotate and endpoint drags each commit their edit`() {
        controller.insert(Element(id = "box", type = "rectangle", width = 10.0, height = 10.0))
        controller.select("box")
        commits.commit(CanvasGesture.Resize(Corner.BOTTOM_RIGHT), origin, Point(60.0, 40.0), drag)
        assertEquals(60.0, controller.selected!!.width, 1e-9)
        commits.commit(CanvasGesture.Rotate, origin, Point(100.0, 20.0), drag)
        assertTrue(controller.selected!!.rotation != 0.0)
        controller.insert(Element(id = "arr", type = "arrow", startX = 0.0, startY = 0.0, endX = 10.0, endY = 10.0))
        controller.select("arr")
        commits.commit(CanvasGesture.DragEndpoint(Endpoint.END), origin, Point(80.0, 90.0), drag)
        assertEquals(80.0, controller.selected!!.endX!!, 1e-9)
    }

    @Test
    fun `dragging a table row edge resizes the row, and the same drag on a rectangle commits nothing`() {
        controller.insert(TableElements.create("t", origin, Point(320.0, 96.0)))
        controller.select("t")
        val before = controller.selected!!.height
        commits.commit(CanvasGesture.ResizeRow(0), origin, Point(160.0, 160.0), drag)
        assertTrue(controller.selected!!.height > before)
        controller.insert(Element(id = "box", type = "rectangle", width = 10.0, height = 10.0))
        controller.select("box")
        commits.commit(CanvasGesture.ResizeRow(0), origin, Point(160.0, 160.0), drag)
        assertEquals(10.0, controller.selected!!.height, 1e-9)
    }

    @Test
    fun `a tap on a plain shape opens nothing, and a tap on a selected table opens its cell`() {
        controller.insert(Element(id = "box", type = "rectangle", width = 10.0, height = 10.0))
        controller.select("box")
        commits.commit(CanvasGesture.Move(), origin, Point(5.0, 5.0), 0.0)
        assertNull(controller.editing)
        controller.insert(TableElements.create("t", origin, Point(320.0, 96.0)))
        controller.select("t")
        commits.commit(CanvasGesture.Move(), origin, Point(40.0, 20.0), 0.0)
        assertEquals("t", controller.editing?.elementId)
        assertEquals(0, controller.editing?.cellIndex)
    }
}
