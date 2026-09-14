package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the keyboard text editor opens (FR6/FR24), in layout units. */
class TextEditRequestTest {
    @Test
    fun `a text box's request is its rect and type size, in dp`() {
        val box = Element(id = "t", type = "text", x = 100.0, y = 50.0, width = 320.0, height = 44.8, text = "hi")
        val request = TextEditRequest.of(box, CanvasController.EditTarget("t"), ViewTransform(0.0, 0.0, 2.0), fakeMeasurer, 2.0)
        assertEquals(TextEditRequest(CanvasController.EditTarget("t"), "hi", ScreenRect(100.0, 50.0, 320.0, 44.8), 24.0, false), request)
    }

    @Test
    fun `a table cell's request covers just that cell, with the table's smaller type`() {
        val table =
            TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 96.0)).copy(
                table = TableData(2, 2, listOf("", "", "", "d")),
                style = ShapeStyle.DEFAULT,
            )
        val request = TextEditRequest.of(table, CanvasController.EditTarget("tb", 3), ViewTransform(0.0, 0.0, 1.0), fakeMeasurer, 1.0)
        assertEquals(ScreenRect(160.0, 48.0, 160.0, 48.0), request.rect)
        assertEquals(listOf<Any>("d", 18.0), listOf(request.text, request.fontSize))
    }

    @Test
    fun `a note's request says so, and a box with no text starts empty`() {
        val note = Element(id = "n", type = "note", width = 200.0, height = 200.0)
        val request = TextEditRequest.of(note, CanvasController.EditTarget("n"), ViewTransform(0.0, 0.0, 1.0), fakeMeasurer, 1.0)
        assertEquals(listOf<Any>("", true), listOf(request.text, request.isNote))
    }

    @Test
    fun `the payload carries every field, with -1 for no cell`() {
        val request = TextEditRequest(CanvasController.EditTarget("t"), "hi", ScreenRect(1.0, 2.0, 3.0, 4.0), 24.0, false)
        assertEquals(
            mapOf(
                "elementId" to "t",
                "cellIndex" to -1,
                "text" to "hi",
                "left" to 1.0,
                "top" to 2.0,
                "width" to 3.0,
                "height" to 4.0,
                "fontSize" to 24.0,
                "isNote" to false,
            ),
            request.toPayload(),
        )
        assertEquals(2, request.copy(target = CanvasController.EditTarget("tb", 2)).toPayload()["cellIndex"])
    }
}
