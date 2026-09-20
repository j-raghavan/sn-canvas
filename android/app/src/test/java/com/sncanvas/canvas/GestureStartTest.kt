package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a touch starts: the controls over the canvas first, then the tool in hand. */
class GestureStartTest {
    private class Host(
        var tool: String = CanvasTools.SELECT,
        var map: MinimapLayout? = null,
    ) : GestureStart.Host {
        var shown = 0
        var strokes = 0
        var centered: Point? = null
        val erased = mutableListOf<Point>()

        override fun toolMode(): String = tool

        override fun minimapLayout(): MinimapLayout? = map

        override fun showMinimap() {
            shown++
        }

        override fun centerViewOn(world: Point) {
            centered = world
        }

        override fun beginStroke() {
            strokes++
        }

        override fun eraseAt(
            erase: CanvasGesture.Erase,
            world: Point,
        ) {
            erased += world
        }
    }

    private val controller = CanvasController(fakeMeasurer, { "id" }, silentListener)
    private val host = Host()
    private val starts =
        GestureStart(
            { controller.state },
            SelectGestures({ controller.state }, { controller.selectedElements }, controller::fitted, fakeMeasurer),
            host,
        )
    private val finger = Contact(id = 0, isPen = false, x = 10f, y = 10f)
    private val pen = finger.copy(id = 1, isPen = true, pressure = 0.5f)
    private val eraser = pen.copy(id = 2, isEraser = true)

    private fun startAt(
        contact: Contact,
        world: Point = Point(contact.x.toDouble(), contact.y.toDouble()),
    ) = starts.at(contact, world)

    @Test
    fun `the select tool pans on empty canvas, and the pen drags a selection rectangle out instead`() {
        assertEquals(CanvasGesture.Pan, startAt(finger))
        assertEquals(CanvasGesture.Marquee, startAt(pen))
    }

    @Test
    fun `a pen tool draws only with the pen, and the pencil begins a stroke where it landed`() {
        host.tool = CanvasTools.RECTANGLE
        assertNull(startAt(finger))
        assertEquals(CanvasGesture.DrawShape, startAt(pen))
        host.tool = CanvasTools.TEXT
        assertEquals(CanvasGesture.PlaceText, startAt(pen))
        host.tool = CanvasTools.DRAW
        val freehand = startAt(pen) as CanvasGesture.Freehand
        assertEquals(1, host.strokes)
        assertEquals(listOf(StrokePoint(10.0, 10.0, 0.5)), freehand.samples)
    }

    @Test
    fun `the eraser end erases with any tool, as does the eraser tool with the pen`() {
        host.tool = CanvasTools.DRAW
        assertTrue(startAt(eraser) is CanvasGesture.Erase)
        host.tool = CanvasTools.ERASER
        assertTrue(startAt(pen) is CanvasGesture.Erase)
        assertEquals(listOf(Point(10.0, 10.0), Point(10.0, 10.0)), host.erased)
        // The eraser is a pen tool, so a finger (or a palm) beside it starts nothing.
        assertNull(startAt(finger))
    }

    @Test
    fun `a tap on a link glyph follows it, and only with a tool that is not held by the pen`() {
        val linked =
            Element(id = "box", type = "rectangle", width = 40.0, height = 40.0)
                .copy(link = ElementLink(ElementLink.KIND_NOTE, "/n.note"))
        controller.insert(linked)
        // Where the glyph sits for a 40x40 box at the origin, at zoom 1: off its top-right corner.
        val glyph = Point(40.0 + CanvasCore.LINK_GLYPH_OFFSET_PX, -CanvasCore.LINK_GLYPH_OFFSET_PX)
        assertEquals(CanvasGesture.FollowLink("box"), startAt(finger, glyph))
        // Away from the glyph it is an ordinary touch, and a drawing tool never gives its stroke up to one.
        assertEquals(CanvasGesture.Pan, startAt(finger, Point(500.0, 500.0)))
        host.tool = CanvasTools.DRAW
        assertTrue(startAt(pen, glyph) is CanvasGesture.Freehand)
    }

    @Test
    fun `a touch inside the minimap's viewport rectangle drags it from where it was grabbed`() {
        host.map = layout(visible = WorldRect(0.0, 0.0, 100.0, 100.0))
        val drag = startAt(finger.copy(x = 50f, y = 50f)) as CanvasGesture.MinimapDrag
        assertEquals(1, host.shown)
        assertNull(host.centered)
        assertEquals(0.0, drag.grabX, 1e-9)
        assertEquals(0.0, drag.grabY, 1e-9)
    }

    @Test
    fun `a touch elsewhere in the minimap takes the view there at once`() {
        host.map = layout(visible = WorldRect(0.0, 0.0, 10.0, 10.0))
        val drag = startAt(finger.copy(x = 80f, y = 80f)) as CanvasGesture.MinimapDrag
        assertEquals(Point(80.0, 80.0), host.centered)
        assertEquals(0.0, drag.grabX, 1e-9)
    }

    @Test
    fun `the pen goes on drawing over the minimap, but its eraser end still erases`() {
        host.map = layout(visible = WorldRect(0.0, 0.0, 100.0, 100.0))
        host.tool = CanvasTools.DRAW
        assertTrue(startAt(pen.copy(x = 50f, y = 50f)) is CanvasGesture.Freehand)
        assertTrue(startAt(eraser.copy(x = 50f, y = 50f)) is CanvasGesture.Erase)
        // A finger navigates by it, and outside the box it is left to the tool, which draws with the pen alone.
        assertTrue(startAt(finger.copy(x = 50f, y = 50f)) is CanvasGesture.MinimapDrag)
        assertNull(startAt(finger.copy(x = 500f, y = 500f)))
    }

    @Test
    fun `with the minimap away, a touch where it would have been is the tool's`() {
        host.map = null
        assertEquals(CanvasGesture.Pan, startAt(finger.copy(x = 50f, y = 50f)))
    }

    private fun layout(visible: WorldRect) =
        MinimapLayout(
            left = 0.0,
            top = 0.0,
            width = 100.0,
            height = 100.0,
            visible = visible,
            fit = ViewTransform(0.0, 0.0, 1.0),
        )
}
