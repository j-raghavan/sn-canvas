package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        val table = TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 96.0)).copy(table = grid)
        controller.load(listOf(table))
        // The last cell of the table: the far corner, so either side shrinking is enough to lose it.
        controller.beginEdit(CanvasController.EditTarget("tb", 3))
        controller.finishEdit("b2")
        assertEquals(1, controller.currentCell?.row)
        assertEquals(1, controller.currentCell?.column)

        // Shrunk by a commit rather than by Remove this row, which lets the cell go itself: any finished
        // gesture can leave a table smaller, so the cell is checked against the table as it is now.
        // A column short, with its row still there, so the column alone has to be enough.
        controller.commit(listOf(table.copy(table = TableData(2, 1, listOf("a1", "b1"), listOf(48.0, 48.0)))))
        assertNull(controller.currentCell)

        // And a row short, with its column still there.
        controller.commit(listOf(table.copy(table = TableData(1, 2, listOf("a1", "a2"), listOf(48.0)))))
        assertNull(controller.currentCell)
    }

    // Selecting something lets the cell go on the way in, but a command that takes the selection for
    // itself does not, so which table the cell was in has to be checked and not assumed.
    @Test
    fun `the tapped cell is forgotten when an inserted image takes the selection`() {
        controller.load(listOf(table))
        controller.beginEdit(CanvasController.EditTarget("tb", 1))
        assertTrue(ui.canRemoveTableRow)
        controller.insertImage(ImageData("img-1.png", 100, 50), WorldRect(0.0, 0.0, 1000.0, 800.0))
        assertNull(controller.currentCell)
        assertFalse(ui.canRemoveTableRow)
    }

    // Nothing is selected at all once the table has gone, so there is no table to check the cell against.
    @Test
    fun `the tapped cell is forgotten when the table it was in is deleted`() {
        controller.load(listOf(table))
        controller.beginEdit(CanvasController.EditTarget("tb", 1))
        assertTrue(ui.canRemoveTableRow)
        controller.deleteSelected()
        assertNull(controller.currentCell)
        assertFalse(ui.canRemoveTableRow)
    }

    // The one wire the feature ships on: without this the menu items stay greyed out on the device
    // and every test still passes (#53).
    @Test
    fun `the action bar is told when a cell has been tapped, and when it is not`() {
        controller.load(listOf(table))
        controller.select("tb")
        assertFalse(ui.canRemoveTableRow)
        controller.beginEdit(CanvasController.EditTarget("tb", 1))
        assertTrue(ui.canRemoveTableRow)
        controller.select(null)
        assertFalse(ui.canRemoveTableRow)
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

    // The column twin of the test below: removing left the cell pointing at whatever slid left into
    // its place, so a second press took out a column the user had never put a cursor in (#53).
    @Test
    fun `the tapped cell is forgotten once its column has been removed`() {
        val grid = TableData(2, 3, listOf("a1", "a2", "a3", "b1", "b2", "b3"), listOf(48.0, 48.0))
        controller.load(listOf(TableElements.create("tb", Point(0.0, 0.0), Point(480.0, 96.0)).copy(table = grid)))
        controller.beginEdit(CanvasController.EditTarget("tb", 0))
        controller.finishEdit("a1")
        controller.removeTableColumn()
        assertNull(controller.currentCell?.column)
        // A second press takes nothing, rather than the column that moved left into the gap.
        controller.removeTableColumn()
        assertEquals(
            listOf("a2", "a3", "b2", "b3"),
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

    // The menu items are enabled off the UI state, and a removal published before it let the cell go,
    // so Remove this row stayed black on a cell that had just gone and then did nothing when tapped (#53).
    @Test
    fun `the action bar is told the cell has gone as soon as its row or column is removed`() {
        controller.load(listOf(TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 144.0))))
        // The first cell of three rows, so a row is left after it that the stale cell would still fit in.
        controller.beginEdit(CanvasController.EditTarget("tb", 0))
        assertTrue(ui.canRemoveTableRow)
        controller.removeTableRow()
        assertFalse(ui.canRemoveTableRow)

        controller.beginEdit(CanvasController.EditTarget("tb", 0))
        assertTrue(ui.canRemoveTableRow)
        controller.removeTableColumn()
        assertFalse(ui.canRemoveTableRow)
    }

    // A table down to its last row cannot lose another, so the item is greyed out rather than taking a
    // press and doing nothing. Rows and columns are counted apart, so the column can still go (#53).
    @Test
    fun `a table down to its last row still offers to remove a column, but not a row`() {
        val oneRow =
            TableElements
                .create("tb", Point(0.0, 0.0), Point(320.0, 48.0))
                .copy(table = TableData(1, 2, listOf("a1", "a2"), listOf(48.0)))
        controller.load(listOf(oneRow))
        controller.beginEdit(CanvasController.EditTarget("tb", 0))
        assertFalse(ui.canRemoveTableRow)
        assertTrue(ui.canRemoveTableColumn)

        // Pressed anyway it changes nothing, and leaves no step to undo for a change that never happened.
        controller.removeTableRow()
        assertEquals(
            1,
            controller.state.elements
                .single()
                .table
                ?.rows,
        )
        assertFalse(ui.canUndo)
        // The cell is still marked, so the column it is in can still go.
        assertTrue(ui.canRemoveTableColumn)

        controller.removeTableColumn()
        assertEquals(
            1,
            controller.state.elements
                .single()
                .table
                ?.cols,
        )
        assertTrue(ui.canUndo)
        assertFalse(ui.canRemoveTableColumn)
    }

    // Duplicating a table leaves the copy selected, and the copy is a table of the same size, so
    // without checking which table the cell was tapped in the marker and the commands would move
    // onto the copy on their own and take a row out of it that nobody pointed at (#53).
    @Test
    fun `the tapped cell does not follow a duplicate of its table`() {
        controller.load(listOf(table))
        controller.beginEdit(CanvasController.EditTarget("tb", 1))
        assertTrue(ui.canRemoveTableRow)
        controller.duplicateSelected(8.0)
        // The copy is the selection now, and it is a table the same shape as the one the cell was in.
        assertNotEquals("tb", controller.selected?.id)
        assertEquals(2, controller.selected?.table?.rows)
        assertNull(controller.currentCell)
        assertFalse(ui.canRemoveTableRow)
    }

    // A selection dragged out over the table alone still leaves it as the one selected, so the cell is
    // let go of because selectAll says to and not because the table stopped being selected (#53).
    @Test
    fun `the tapped cell is forgotten when a selection is dragged out over the table alone`() {
        controller.load(listOf(table))
        controller.beginEdit(CanvasController.EditTarget("tb", 3))
        assertEquals(1, controller.currentCell?.row)
        controller.selectAll(setOf("tb"))
        assertNull(controller.currentCell)
        assertFalse(ui.canRemoveTableRow)
    }

    // Undo replaces every element and selects nothing, so the cell goes with it. The table is the same
    // shape either side of the undo, so this is the selection being emptied doing the work and not the
    // row and column guard, which would let a cell of that shape through (#53).
    @Test
    fun `the tapped cell is forgotten by an undo that leaves the table the same shape`() {
        val grid = TableData(3, 2, listOf("a1", "a2", "b1", "b2", "c1", "c2"), listOf(48.0, 48.0, 48.0))
        controller.load(listOf(TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 144.0)).copy(table = grid)))
        controller.beginEdit(CanvasController.EditTarget("tb", 2))
        controller.finishEdit("typed")
        assertEquals(1, controller.currentCell?.row)
        controller.undo()
        // Still a 3 by 2 table, so (1, 0) is a cell it has; the cell is forgotten all the same.
        assertEquals(
            listOf(3, 2),
            listOf(
                controller.state.elements
                    .single()
                    .table
                    ?.rows,
                controller.state.elements
                    .single()
                    .table
                    ?.cols,
            ),
        )
        assertNull(controller.currentCell)
        assertFalse(ui.canRemoveTableRow)
    }
}
