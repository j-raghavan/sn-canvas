package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command layer's table commands (FR24): typing in a cell, rows and columns added, and the cell
 * last tapped, which is the row and column a removal acts on (#53).
 */
class CanvasControllerTableTest {
    /** Only the UI state is read here; redraws and editor openings are [CanvasControllerTest]'s. */
    private class Recorder : CanvasController.Listener {
        val uiStates = mutableListOf<CanvasUiState>()

        override fun onChanged() = Unit

        override fun onUiState(uiState: CanvasUiState) {
            uiStates += uiState
        }

        override fun onEditText(target: CanvasController.EditTarget) = Unit
    }

    private val recorder = Recorder()
    private var lastId = 0
    private val controller = CanvasController(fakeMeasurer, { "id-${++lastId}" }, recorder)
    private val box = Element(id = "box", type = "rectangle", width = 10.0, height = 10.0)
    private val table = TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 96.0))
    private val ui get() = recorder.uiStates.last()

    @Test
    fun `editing a table cell sets that cell`() {
        controller.load(listOf(table))
        controller.beginEdit(CanvasController.EditTarget("tb", 1))
        controller.finishEdit("B")
        assertEquals(
            listOf("", "B", "", ""),
            controller.state.elements
                .single()
                .table
                ?.cells,
        )
        assertTrue(ui.canUndo)
    }

    @Test
    fun `row and column commands act on the selected table`() {
        controller.load(listOf(table))
        controller.select("tb")
        controller.addTableRow()
        controller.addTableColumn()
        val grown =
            controller.state.elements
                .single()
                .table
        assertEquals(listOf(3, 3), listOf(grown?.rows, grown?.cols))
        // Removing waits until a cell says which row or column is meant (#53), so with none tapped
        // the table is left as it is.
        controller.removeTableRow()
        controller.removeTableColumn()
        assertEquals(listOf(3, 3), listOf(grown?.rows, grown?.cols))

        controller.beginEdit(CanvasController.EditTarget("tb", 0))
        controller.finishEdit("")
        controller.removeTableRow()
        // The cell went with its row, so the next removal needs a cell of its own to point at.
        controller.beginEdit(CanvasController.EditTarget("tb", 0))
        controller.finishEdit("")
        controller.removeTableColumn()
        assertEquals(
            TableData.empty(2, 2),
            controller.state.elements
                .single()
                .table,
        )
    }

    // #53: the row taken out is the one the tapped cell is in, not always the last.
    @Test
    fun `removing a row or column takes out the one the tapped cell is in`() {
        val grid = TableData(3, 2, listOf("a1", "a2", "b1", "b2", "c1", "c2"), listOf(48.0, 48.0, 48.0))
        controller.load(listOf(TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 144.0)).copy(table = grid)))
        controller.select("tb")
        // Nothing tapped yet, so nothing says which row and nothing is taken out.
        assertNull(controller.currentCell?.row)

        // Tap the cell "b1", index 2, which is row 1 and column 0.
        controller.beginEdit(CanvasController.EditTarget("tb", 2))
        controller.finishEdit("b1")
        assertEquals(1, controller.currentCell?.row)
        controller.removeTableRow()
        assertEquals(
            listOf("a1", "a2", "c1", "c2"),
            controller.state.elements
                .single()
                .table
                ?.cells,
        )
    }

    @Test
    fun `the tapped cell is forgotten once the table has shrunk past it`() {
        val grid = TableData(2, 2, listOf("a1", "a2", "b1", "b2"), listOf(48.0, 48.0))
        controller.load(listOf(TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 96.0)).copy(table = grid)))
        // The last cell, which the row it is in is about to take with it.
        controller.beginEdit(CanvasController.EditTarget("tb", 3))
        controller.finishEdit("b2")
        assertEquals(1, controller.currentCell?.row)
        controller.removeTableRow()
        assertNull(controller.currentCell?.row)
    }

    // The one wire the feature ships on: without this the menu items stay greyed out on the device
    // and every test still passes (#53).
    @Test
    fun `the action bar is told when a cell has been tapped, and when it is not`() {
        controller.load(listOf(table))
        controller.select("tb")
        assertFalse(ui.hasTableCell)
        controller.beginEdit(CanvasController.EditTarget("tb", 1))
        assertTrue(ui.hasTableCell)
        controller.select(null)
        assertFalse(ui.hasTableCell)
    }

    @Test
    fun `a table edit with nothing selected changes nothing`() {
        val grid = TableData(2, 2, listOf("a1", "a2", "b1", "b2"), listOf(48.0, 48.0))
        controller.load(listOf(TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 96.0)).copy(table = grid)))
        controller.select(null)
        controller.addTableRow()
        controller.addTableColumn()
        assertEquals(
            grid,
            controller.state.elements
                .single()
                .table,
        )
    }

    // A column added between the tap and the removal used to turn the cell's place in a flat list
    // into a different row, so the wrong row went (#53).
    @Test
    fun `a column added after the tap does not move which row the cell is in`() {
        val grid = TableData(3, 2, listOf("a1", "a2", "b1", "b2", "c1", "c2"), listOf(48.0, 48.0, 48.0))
        controller.load(listOf(TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 144.0)).copy(table = grid)))
        controller.select("tb")
        // "c1" is index 4 of 2 columns, so row 2.
        controller.beginEdit(CanvasController.EditTarget("tb", 4))
        controller.finishEdit("c1")
        controller.addTableColumn()
        assertEquals(2, controller.currentCell?.row)
        controller.removeTableRow()
        // Row c went, and rows a and b are still there, each with the empty cell the new column added.
        assertEquals(
            listOf("a1", "a2", "", "b1", "b2", ""),
            controller.state.elements
                .single()
                .table
                ?.cells,
        )
    }

    // Removing left the cell pointing at whatever slid into its place, so pressing again took out a
    // row the user had never put a cursor in (#53).
    @Test
    fun `the tapped cell is forgotten once its row has been removed`() {
        val grid = TableData(3, 2, listOf("a1", "a2", "b1", "b2", "c1", "c2"), listOf(48.0, 48.0, 48.0))
        controller.load(listOf(TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 144.0)).copy(table = grid)))
        controller.select("tb")
        controller.beginEdit(CanvasController.EditTarget("tb", 0))
        controller.finishEdit("a1")
        controller.removeTableRow()
        assertNull(controller.currentCell?.row)
        // A second press takes nothing, rather than the row that moved up into the gap.
        controller.removeTableRow()
        assertEquals(
            listOf("b1", "b2", "c1", "c2"),
            controller.state.elements
                .single()
                .table
                ?.cells,
        )
    }

    @Test
    fun `the tapped cell is forgotten when a selection is dragged out over things`() {
        controller.load(listOf(TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 96.0)), box))
        controller.beginEdit(CanvasController.EditTarget("tb", 1))
        assertEquals(0, controller.currentCell?.row)
        controller.selectAll(setOf("tb", "box"))
        assertNull(controller.currentCell?.row)
    }

    // Undo replaces every element, so a cell tapped against the old shape must not come back with it.
    @Test
    fun `the tapped cell is forgotten when undo puts a different table back`() {
        val grid = TableData(2, 4, List(8) { "" }, listOf(48.0, 48.0))
        controller.load(listOf(TableElements.create("tb", Point(0.0, 0.0), Point(640.0, 96.0)).copy(table = grid)))
        controller.select("tb")
        controller.addTableRow()
        controller.beginEdit(CanvasController.EditTarget("tb", 9))
        controller.finishEdit("")
        assertEquals(2, controller.currentCell?.row)
        controller.undo()
        controller.select("tb")
        assertNull(controller.currentCell?.row)
    }

    @Test
    fun `the tapped cell is forgotten when something else is selected`() {
        controller.load(listOf(TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 96.0)), box))
        controller.beginEdit(CanvasController.EditTarget("tb", 1))
        assertEquals(0, controller.currentCell?.row)
        controller.select("box")
        assertNull(controller.currentCell?.row)
    }
}
