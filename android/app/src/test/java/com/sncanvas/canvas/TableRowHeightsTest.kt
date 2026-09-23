package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Row heights (FR24): each row keeps the height it was given, grows with its text, and is saved with the canvas. */
class TableRowHeightsTest {
    // Two 48-tall rows of two 160-wide columns, at the origin.
    private val table = TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 96.0))
    private val grid = table.table!!

    private fun stateOf(vararg elements: Element) = CanvasState(elements.toList(), 0.0, 0.0, 1.0)

    private fun CanvasState.table() = elements.single { it.id == "tb" }

    private fun withHeights(vararg heights: Double) = table.copy(table = grid.copy(rowMinHeights = heights.toList()))

    @Test
    fun `every row starts at the minimum height, and a grid needs one height per row`() {
        assertEquals(listOf(48.0, 48.0), TableData.empty(2, 2).rowMinHeights)
        assertThrows(IllegalArgumentException::class.java) { TableData(1, 1, listOf(""), listOf(48.0, 48.0)) }
    }

    @Test
    fun `a row is as tall as it was made, or as its text needs`() {
        val tall = withHeights(100.0, 48.0)
        assertEquals(listOf(100.0, 48.0), TableElements.rowHeights(tall, fakeMeasurer))
        // 128 characters at 9 wide across an inner width of 144 need eight lines.
        val crowded = tall.copy(table = tall.table!!.copy(cells = listOf("x".repeat(128), "", "", "")))
        assertEquals(8 * 18.0 * 1.2 + 16, TableElements.rowHeights(crowded, fakeMeasurer)[0], 1e-9)
    }

    @Test
    fun `resizing a row sets its height within the limits, and the table follows`() {
        val taller = TableEdits.resizeRow(stateOf(table), "tb", 0, 100.0, fakeMeasurer).table()
        assertEquals(listOf(100.0, 48.0), taller.table?.rowMinHeights)
        assertEquals(148.0, taller.height, 1e-9)
        assertEquals(
            listOf(48.0, 48.0),
            TableEdits
                .resizeRow(stateOf(table), "tb", 0, 3.0, fakeMeasurer)
                .table()
                .table
                ?.rowMinHeights,
        )
        val huge = TableEdits.resizeRow(stateOf(table), "tb", 1, 1e9, fakeMeasurer).table()
        assertEquals(listOf(48.0, TableElements.MAX_ROW_HEIGHT), huge.table?.rowMinHeights)
    }

    @Test
    fun `dragging a row's bottom edge makes the row reach the pointer, following the table's rotation`() {
        assertEquals(
            listOf(48.0, 102.0),
            TableEdits
                .dragRowEdge(stateOf(table), "tb", 1, Point(10.0, 150.0), fakeMeasurer)
                .table()
                .table
                ?.rowMinHeights,
        )
        // Upside down, the second row's bottom edge is at the top: dragging it up to y = -54 makes that row 102 tall.
        val upsideDown = TableEdits.dragRowEdge(stateOf(table.copy(rotation = Math.PI)), "tb", 1, Point(10.0, -54.0), fakeMeasurer)
        assertEquals(102.0, upsideDown.table().table!!.rowMinHeights[1], 1e-9)
        val state = stateOf(table)
        assertSame(state, TableEdits.dragRowEdge(state, "missing", 0, Point(0.0, 0.0), fakeMeasurer))
    }

    @Test
    fun `a resized table shares its new height among its rows, none below its text`() {
        val stretched = TableEdits.stretch(stateOf(table.copy(height = 192.0)), "tb", fakeMeasurer).table()
        assertEquals(listOf(96.0, 96.0), stretched.table?.rowMinHeights)
        assertEquals(192.0, stretched.height, 1e-9)
        // The first row needs four lines (102.4); squeezed to 60, both rows drop to the minimum and the first still fits its text.
        val crowded = table.copy(table = grid.copy(cells = listOf("x".repeat(64), "", "", "")), height = 60.0)
        val squeezed = TableEdits.stretch(stateOf(crowded), "tb", fakeMeasurer).table()
        assertEquals(listOf(48.0, 48.0), squeezed.table?.rowMinHeights)
        assertEquals(4 * 18.0 * 1.2 + 16 + 48.0, squeezed.height, 1e-9)
        val state = stateOf(Element(id = "r", type = "rectangle", width = 9.0, height = 9.0))
        assertSame(state, TableEdits.stretch(state, "missing", fakeMeasurer))
        assertSame(state, TableEdits.stretch(state, "r", fakeMeasurer))
    }

    @Test
    fun `adding and removing rows or columns keeps the other rows' heights`() {
        val tall = stateOf(withHeights(100.0, 60.0))
        val added = TableEdits.addRow(tall, "tb", fakeMeasurer)
        // #53: the row added takes the height the table is already using, not the smallest allowed,
        // or it comes in short and the table stops looking like one table.
        assertEquals(listOf(100.0, 60.0, 60.0), added.table().table?.rowMinHeights)
        assertEquals(
            listOf(100.0, 60.0),
            TableEdits
                .removeRow(added, "tb", 2, fakeMeasurer)
                .table()
                .table
                ?.rowMinHeights,
        )
        assertEquals(
            listOf(100.0, 60.0),
            TableEdits
                .addColumn(tall, "tb", fakeMeasurer)
                .table()
                .table
                ?.rowMinHeights,
        )
    }

    // #53, reported after 1.0.2: a row added to a table whose rows had been made taller came in at
    // the minimum height and sat short of the others.
    @Test
    fun `a row added matches the rows already there, however tall they were made`() {
        val stretched = stateOf(withHeights(120.0, 120.0))
        val added = TableEdits.addRow(stretched, "tb", fakeMeasurer).table()
        assertEquals(listOf(120.0, 120.0, 120.0), added.table?.rowMinHeights)
        assertEquals(listOf(120.0, 120.0, 120.0), TableElements.rowHeights(added, fakeMeasurer))
        // And the element grows by that row rather than by the minimum.
        assertEquals(360.0, added.height, 1e-9)
    }

    @Test
    fun `a row added to a table still at the minimum is still at the minimum`() {
        val added = TableEdits.addRow(stateOf(table), "tb", fakeMeasurer).table()
        assertEquals(listOf(48.0, 48.0, 48.0), added.table?.rowMinHeights)
    }

    @Test
    fun `a row edge answers near its line, across the table's width only`() {
        val tall = withHeights(100.0, 48.0)
        assertEquals(0, TableElements.rowEdgeAt(tall, Point(50.0, 103.0), 5.0, fakeMeasurer))
        assertEquals(1, TableElements.rowEdgeAt(tall, Point(50.0, 146.0), 5.0, fakeMeasurer))
        assertNull(TableElements.rowEdgeAt(tall, Point(50.0, 120.0), 5.0, fakeMeasurer))
        assertNull(TableElements.rowEdgeAt(tall, Point(-1.0, 100.0), 5.0, fakeMeasurer))
        assertNull(TableElements.rowEdgeAt(tall, Point(321.0, 100.0), 5.0, fakeMeasurer))
        val rect = Element(id = "r", type = "rectangle", width = 9.0, height = 9.0)
        assertNull(TableElements.rowEdgeAt(rect, Point(1.0, 9.0), 5.0, fakeMeasurer))
    }

    @Test
    fun `the controller stretches a resized table's rows, and leaves an unknown id as it is`() {
        val controller = CanvasController(fakeMeasurer, { "new" }, silentListener)
        assertEquals(stateOf(table), controller.fitted(stateOf(table), "missing"))
        assertEquals(
            listOf(96.0, 96.0),
            controller
                .fitted(stateOf(table.copy(height = 192.0)), "tb")
                .table()
                .table
                ?.rowMinHeights,
        )
    }

    @Test
    fun `row heights survive a save and load`() {
        val loaded = CanvasJson.deserializeElements(CanvasJson.serializeElements(listOf(withHeights(100.04, 48.0)))).single()
        assertEquals(listOf(100.0, 48.0), loaded.table?.rowMinHeights)
    }

    @Test
    fun `row heights that are missing, miscounted, not numbers or out of range load within the limits`() {
        fun load(heights: String) =
            CanvasJson
                .deserializeElements(
                    """{"version":1,"elements":[{"id":"tb","type":"table","table":{"rows":2,"cols":1,"cells":["",""]$heights}}]}""",
                ).single()
                .table
                ?.rowMinHeights
        assertEquals(listOf(48.0, 48.0), load(""))
        assertEquals(listOf(48.0, 48.0), load(""","rowMinHeights":[100]"""))
        assertEquals(listOf(48.0, 90.0), load(""","rowMinHeights":["tall",90]"""))
        assertEquals(listOf(48.0, TableElements.MAX_ROW_HEIGHT), load(""","rowMinHeights":[1,1e9]"""))
    }
}
