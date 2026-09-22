package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Tables (FR24): the drag-to-size grid, rows fitted to their text, cells and row/column changes. */
class TableElementsTest {
    private val table2x2 = TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 96.0))

    // Cell 0 holds text that needs four lines: 64 characters at 9 wide across an inner width of 144.
    private val tallFirstCell =
        TextElements.fit(table2x2.copy(table = TableData(2, 2, listOf("x".repeat(64), "", "", "d"))), fakeMeasurer)

    private fun stateOf(vararg elements: Element) = CanvasState(elements.toList(), 0.0, 0.0, 1.0)

    @Test
    fun `a drag makes a grid of whole cells, at least 1 by 1 and at most 12 by 8`() {
        assertEquals(1 to 1, TableElements.gridForDrag(0.0, 0.0))
        assertEquals(2 to 1, TableElements.gridForDrag(170.0, 100.0))
        assertEquals(12 to 8, TableElements.gridForDrag(10_000.0, 10_000.0))
    }

    @Test
    fun `a new table spans the drag in either direction, with empty cells and standard columns`() {
        val table = TableElements.create("t", Point(400.0, 200.0), Point(80.0, 100.0))
        assertEquals(listOf(80.0, 100.0, 320.0), listOf(table.x, table.y, table.width))
        assertEquals(TableData.empty(2, 2), table.table)
    }

    @Test
    fun `each row is as tall as its tallest cell, and never shorter than the minimum`() {
        assertEquals(listOf(4 * 18.0 * 1.2 + 16, 48.0), TableElements.rowHeights(tallFirstCell, fakeMeasurer))
        assertEquals(4 * 18.0 * 1.2 + 16 + 48.0, tallFirstCell.height, 1e-9)
        assertEquals(emptyList<Double>(), TableElements.rowHeights(Element(id = "r", type = "rectangle"), fakeMeasurer))
    }

    @Test
    fun `a cell's rect follows the rows above it`() {
        val top = 4 * 18.0 * 1.2 + 16
        assertEquals(WorldRect(160.0, top, 320.0, top + 48.0), TableElements.cellRect(tallFirstCell, 3, fakeMeasurer))
        val rect = Element(id = "r", type = "rectangle", x = 1.0, y = 2.0, width = 3.0, height = 4.0)
        assertEquals(WorldRect(1.0, 2.0, 4.0, 6.0), TableElements.cellRect(rect, 0, fakeMeasurer))
    }

    // The view draws and hit-tests against a live frame, so the index it holds can be an edit out of
    // date. A cell the table has not got falls back to the whole element, the way a non-table does,
    // rather than throwing on the row lookup part way through the draw (#53).
    @Test
    fun `a cell the table has not got gives the whole element back, and does not throw`() {
        val whole = WorldRect(0.0, 0.0, 320.0, tallFirstCell.height)
        assertEquals(whole, TableElements.cellRect(tallFirstCell, 4, fakeMeasurer))
        assertEquals(whole, TableElements.cellRect(tallFirstCell, 99, fakeMeasurer))
        assertEquals(whole, TableElements.cellRect(tallFirstCell, -1, fakeMeasurer))
    }

    @Test
    fun `cellAt finds the cell under a point, and nothing outside the table`() {
        assertEquals(3, TableElements.cellAt(tallFirstCell, Point(170.0, 110.0), fakeMeasurer))
        assertEquals(0, TableElements.cellAt(tallFirstCell, Point(10.0, 10.0), fakeMeasurer))
        assertNull(TableElements.cellAt(tallFirstCell, Point(-1.0, 10.0), fakeMeasurer))
        assertNull(TableElements.cellAt(tallFirstCell, Point(10.0, -1.0), fakeMeasurer))
        assertNull(TableElements.cellAt(tallFirstCell, Point(330.0, 10.0), fakeMeasurer))
        assertNull(TableElements.cellAt(tallFirstCell, Point(10.0, 500.0), fakeMeasurer))
        assertNull(TableElements.cellAt(Element(id = "r", type = "rectangle", width = 9.0, height = 9.0), Point(1.0, 1.0), fakeMeasurer))
    }

    @Test
    fun `cellText reads a cell, and is empty for a bad index or a non-table`() {
        assertEquals("d", TableElements.cellText(tallFirstCell, 3))
        assertEquals("", TableElements.cellText(tallFirstCell, 9))
        assertEquals("", TableElements.cellText(tallFirstCell, -1))
        assertEquals("", TableElements.cellText(Element(id = "r", type = "rectangle"), 0))
    }

    @Test
    fun `setCell sets one cell and refits the rows`() {
        val updated = TableEdits.setCell(stateOf(table2x2), "tb", 2, "x".repeat(64), fakeMeasurer).elements.single()
        assertEquals(listOf("", "", "x".repeat(64), ""), updated.table?.cells)
        assertEquals(48.0 + 4 * 18.0 * 1.2 + 16, updated.height, 1e-9)
    }

    @Test
    fun `adding a row or a column keeps the cells' size, up to the maximum`() {
        val withRow = TableEdits.addRow(stateOf(table2x2), "tb", fakeMeasurer).elements.single()
        assertEquals(listOf(3, 2), listOf(withRow.table?.rows, withRow.table?.cols))
        assertEquals(144.0, withRow.height, 1e-9)
        val withColumn = TableEdits.addColumn(stateOf(tallFirstCell), "tb", fakeMeasurer).elements.single()
        assertEquals(listOf("x".repeat(64), "", "", "", "d", ""), withColumn.table?.cells)
        assertEquals(480.0, withColumn.width, 1e-9)
        val full = table2x2.copy(table = TableData.empty(TableElements.MAX_ROWS, TableElements.MAX_COLS))
        val stillFull = TableEdits.addColumn(TableEdits.addRow(stateOf(full), "tb", fakeMeasurer), "tb", fakeMeasurer)
        assertEquals(full.table, stillFull.elements.single().table)
    }

    // #53: the row or column the current cell is in goes, not always the last one, so a row in the
    // middle can be taken out without retyping everything after it.
    @Test
    fun `removing takes out the row or column it is given, cells and heights together`() {
        // Three rows of two, named by where they are, and a middle row made taller than the rest.
        val grid = TableData(3, 2, listOf("a1", "a2", "b1", "b2", "c1", "c2"), listOf(48.0, 90.0, 48.0))
        val three = stateOf(table2x2.copy(table = grid))
        // The cell "b2" is index 3, which is row 1.
        val withoutMiddle =
            TableEdits
                .removeRow(three, "tb", 1, fakeMeasurer)
                .elements
                .single()
                .table
        assertEquals(listOf("a1", "a2", "c1", "c2"), withoutMiddle?.cells)
        assertEquals(listOf(48.0, 48.0), withoutMiddle?.rowMinHeights)
        // The first row goes as readily as one in the middle.
        val withoutFirst =
            TableEdits
                .removeRow(three, "tb", 0, fakeMeasurer)
                .elements
                .single()
                .table
        assertEquals(listOf("b1", "b2", "c1", "c2"), withoutFirst?.cells)
        assertEquals(listOf(90.0, 48.0), withoutFirst?.rowMinHeights)
        // The cell "b1" is index 2, which is column 0.
        val withoutFirstColumn =
            TableEdits
                .removeColumn(three, "tb", 0, fakeMeasurer)
                .elements
                .single()
                .table
        assertEquals(listOf("a2", "b2", "c2"), withoutFirstColumn?.cells)
        assertEquals(listOf(48.0, 90.0, 48.0), withoutFirstColumn?.rowMinHeights)
    }

    // A destructive edit given a row the table has not got leaves it alone rather than taking out a
    // different one, which is the convention this file states for input it cannot honour.
    @Test
    fun `a row or column the table has not got is left alone`() {
        val grid = TableData(2, 2, listOf("a1", "a2", "b1", "b2"), listOf(48.0, 48.0))
        val two = stateOf(table2x2.copy(table = grid))
        // Both ways out of range, for rows and for columns: past the end, and before the start.
        for (row in listOf(9, -1)) {
            assertEquals(
                grid,
                TableEdits
                    .removeRow(two, "tb", row, fakeMeasurer)
                    .elements
                    .single()
                    .table,
            )
        }
        for (col in listOf(-1, 9)) {
            assertEquals(
                grid,
                TableEdits
                    .removeColumn(two, "tb", col, fakeMeasurer)
                    .elements
                    .single()
                    .table,
            )
        }
    }

    @Test
    fun `removing rows and columns always leaves one of each`() {
        val withoutRow = TableEdits.removeRow(stateOf(tallFirstCell), "tb", 1, fakeMeasurer)
        val fewer = TableEdits.removeColumn(withoutRow, "tb", 1, fakeMeasurer)
        assertEquals(TableData(1, 1, listOf("x".repeat(64))), fewer.elements.single().table)
        assertEquals(160.0, fewer.elements.single().width, 1e-9)
        val single = TableEdits.removeColumn(TableEdits.removeRow(fewer, "tb", 0, fakeMeasurer), "tb", 0, fakeMeasurer)
        assertEquals(TableData(1, 1, listOf("x".repeat(64))), single.elements.single().table)
    }

    @Test
    fun `row and column changes to an unknown id or a non-table change nothing`() {
        val state = stateOf(Element(id = "r", type = "rectangle", width = 9.0, height = 9.0))
        assertSame(state, TableEdits.addRow(state, "r", fakeMeasurer))
        assertSame(state, TableEdits.setCell(state, "missing", 0, "x", fakeMeasurer))
    }

    @Test
    fun `table type is set smaller than a text box's`() {
        assertEquals(18.0, TableElements.fontSize(table2x2.copy(style = ShapeStyle.DEFAULT)), 1e-9)
    }

    @Test
    fun `a grid change touches only its own table`() {
        val rect = Element(id = "r", type = "rectangle", width = 9.0, height = 9.0)
        val updated = TableEdits.setCell(stateOf(rect, table2x2), "tb", 0, "a", fakeMeasurer)
        assertEquals(rect, updated.elements.first())
        assertEquals(
            "a",
            updated.elements
                .last()
                .table
                ?.cells
                ?.first(),
        )
    }

    @Test
    fun `fit leaves an element without a grid as it is`() {
        val gridless = Element(id = "t", type = "table", width = 9.0, height = 9.0)
        assertSame(gridless, TableElements.fit(gridless, fakeMeasurer))
    }
}
