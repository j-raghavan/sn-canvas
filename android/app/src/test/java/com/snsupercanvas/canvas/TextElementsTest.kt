package com.snsupercanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Text boxes and sticky notes (FR6/FR21): creation, text, and height fitted to it. */
class TextElementsTest {
    private val origin = Point(0.0, 0.0)
    private val oneLineAtM = 24.0 * TextElements.LINE_SPACING + 2 * TextElements.PADDING

    @Test
    fun `a new text box sits at the tap, empty, at the standard width`() {
        val box = TextElements.createText("t", Point(10.0, 20.0))
        assertEquals(listOf<Any?>("text", 10.0, 20.0, TextElements.TEXT_WIDTH, ""), listOf(box.type, box.x, box.y, box.width, box.text))
    }

    @Test
    fun `a new note is an empty square centered on the tap`() {
        val note = TextElements.createNote("n", Point(100.0, 100.0))
        assertEquals(WorldRect(0.0, 0.0, 200.0, 200.0), WorldRect(note.x, note.y, note.x + note.width, note.y + note.height))
        assertEquals(listOf("note", ""), listOf(note.type, note.text))
    }

    @Test
    fun `an empty text box is one line tall, and long text wraps onto more lines`() {
        val box = TextElements.createText("t", origin)
        assertEquals(oneLineAtM, TextElements.fit(box, fakeMeasurer).height, 1e-9)
        // 40 characters at 12 wide need 480 across an inner width of 304: two lines.
        assertEquals(2 * 24.0 * 1.2 + 16, TextElements.fit(box.copy(text = "x".repeat(40)), fakeMeasurer).height, 1e-9)
    }

    @Test
    fun `a note stays at least square, and grows with long text`() {
        val note = TextElements.createNote("n", Point(100.0, 100.0))
        assertEquals(200.0, TextElements.fit(note.copy(text = "hi"), fakeMeasurer).height, 1e-9)
        // 500 characters over an inner width of 184: 33 lines.
        assertEquals(33 * 28.8 + 16, TextElements.fit(note.copy(text = "x".repeat(500)), fakeMeasurer).height, 1e-9)
    }

    @Test
    fun `fit leaves shapes as they are and sizes tables by their rows`() {
        val rect = Element(id = "r", type = "rectangle", width = 10.0, height = 7.0)
        assertSame(rect, TextElements.fit(rect, fakeMeasurer))
        val table = TableElements.create("tb", origin, Point(320.0, 96.0))
        assertEquals(96.0, TextElements.fit(table, fakeMeasurer).height, 1e-9)
    }

    @Test
    fun `setText stores the text and refits the height`() {
        val state = CanvasState(listOf(TextElements.createText("t", origin)), 0.0, 0.0, 1.0)
        val updated = TextElements.setText(state, "t", "x".repeat(40), fakeMeasurer).elements.single()
        assertEquals("x".repeat(40), updated.text)
        assertEquals(2 * 24.0 * 1.2 + 16, updated.height, 1e-9)
    }

    @Test
    fun `a text box left blank is removed, a blank note stays`() {
        val state = CanvasState(listOf(TextElements.createText("t", origin), TextElements.createNote("n", origin)), 0.0, 0.0, 1.0)
        val blanked = TextElements.setText(TextElements.setText(state, "t", "  ", fakeMeasurer), "n", "", fakeMeasurer)
        assertEquals(listOf("n"), blanked.elements.map { it.id })
    }

    @Test
    fun `setText on an unknown id changes nothing`() {
        val state = CanvasState(emptyList(), 0.0, 0.0, 1.0)
        assertSame(state, TextElements.setText(state, "missing", "x", fakeMeasurer))
    }

    @Test
    fun `refit fits one element, found by id`() {
        val box = TextElements.createText("t", origin).copy(text = "x".repeat(40))
        val other = Element(id = "r", type = "rectangle", width = 10.0, height = 7.0)
        val refitted = TextElements.refit(CanvasState(listOf(box, other), 0.0, 0.0, 1.0), "t", fakeMeasurer)
        assertEquals(listOf(2 * 24.0 * 1.2 + 16, 7.0), refitted.elements.map { it.height })
    }

    @Test
    fun `text boxes and notes are editable, shapes are not, and type size follows the size style`() {
        assertEquals(
            listOf(true, true, false),
            listOf("text", "note", "rectangle").map { TextElements.isEditable(Element(id = "e", type = it)) },
        )
        assertEquals(44.0, TextElements.fontSize(Element(id = "e", type = "text", style = ShapeStyle(size = SizeStyle.XL))), 0.0)
    }

    @Test
    fun `setText finds its element among others, and a text box with no text yet measures as empty`() {
        val other = Element(id = "r", type = "rectangle", width = 10.0, height = 7.0)
        val state = CanvasState(listOf(other, TextElements.createText("t", origin)), 0.0, 0.0, 1.0)
        assertEquals(
            "hi",
            TextElements
                .setText(state, "t", "hi", fakeMeasurer)
                .elements
                .last()
                .text,
        )
        assertEquals(oneLineAtM, TextElements.fit(Element(id = "x", type = "text", width = 320.0), fakeMeasurer).height, 1e-9)
    }
}
