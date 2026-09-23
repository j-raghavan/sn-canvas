package com.sncanvas.canvas

/**
 * Changing a table (FR24): a cell's text, rows and columns added or removed
 * at the end, and row heights. Columns keep their width, so the table widens
 * or narrows; rows keep the height they were given, and grow when their text
 * needs more. An unknown id or a non-table is left as it is. [TableElements]
 * is the layout this works within.
 */
object TableEdits {
    fun setCell(
        state: CanvasState,
        id: String,
        index: Int,
        text: String,
        measurer: TextMeasurer,
    ): CanvasState = update(state, id, measurer) { t -> t.copy(cells = t.cells.mapIndexed { i, cell -> if (i == index) text else cell }) }

    fun addRow(
        state: CanvasState,
        id: String,
        measurer: TextMeasurer,
    ): CanvasState =
        update(state, id, measurer) { t ->
            if (t.rows >= TableElements.MAX_ROWS) {
                t
            } else {
                t.copy(
                    rows = t.rows + 1,
                    cells = t.cells + List(t.cols) { "" },
                    // As tall as the row it follows, not as short as a row may be: rows get taller by being
                    // dragged and by the table being resized, and one added at the minimum sits short of the
                    // rest and stops the table looking like one table (#53). There is always a row to follow:
                    // a table has at least one, and one height per row.
                    rowMinHeights = t.rowMinHeights + t.rowMinHeights[t.rows - 1],
                )
            }
        }

    /** Table [id] without row [row], the row the current cell is in (#53). A row it has not got is left alone. */
    fun removeRow(
        state: CanvasState,
        id: String,
        row: Int,
        measurer: TextMeasurer,
    ): CanvasState =
        update(state, id, measurer) { t ->
            val at = row
            if (t.rows <= 1 || at !in 0 until t.rows) {
                t
            } else {
                t.copy(
                    rows = t.rows - 1,
                    cells = t.cells.filterIndexed { i, _ -> i / t.cols != at },
                    rowMinHeights = t.rowMinHeights.filterIndexed { i, _ -> i != at },
                )
            }
        }

    fun addColumn(
        state: CanvasState,
        id: String,
        measurer: TextMeasurer,
    ): CanvasState =
        update(state, id, measurer) { t ->
            if (t.cols >= TableElements.MAX_COLS) t else t.copy(cols = t.cols + 1, cells = t.cells.chunked(t.cols).flatMap { it + "" })
        }

    /** Table [id] without column [col], the column the current cell is in (#53). A column it has not got is left alone. */
    fun removeColumn(
        state: CanvasState,
        id: String,
        col: Int,
        measurer: TextMeasurer,
    ): CanvasState =
        update(state, id, measurer) { t ->
            val at = col
            if (t.cols <= 1 || at !in 0 until t.cols) {
                t
            } else {
                t.copy(cols = t.cols - 1, cells = t.cells.chunked(t.cols).flatMap { row -> row.filterIndexed { i, _ -> i != at } })
            }
        }

    /** Row [row] of table [id] made [height] world units tall, within the row limits; its text may still need more. */
    fun resizeRow(
        state: CanvasState,
        id: String,
        row: Int,
        height: Double,
        measurer: TextMeasurer,
    ): CanvasState =
        update(state, id, measurer) { t ->
            val clamped = height.coerceIn(TableElements.MIN_ROW_HEIGHT, TableElements.MAX_ROW_HEIGHT)
            t.copy(rowMinHeights = t.rowMinHeights.mapIndexed { i, old -> if (i == row) clamped else old })
        }

    /** Row [row] of table [id] with its bottom edge dragged to [pointer], a world point; the drag follows the table's rotation. */
    fun dragRowEdge(
        state: CanvasState,
        id: String,
        row: Int,
        pointer: Point,
        measurer: TextMeasurer,
    ): CanvasState {
        val element = state.elements.find { it.id == id } ?: return state
        val top = element.y + TableElements.rowHeights(element, measurer).take(row).sum()
        return resizeRow(state, id, row, element.toLocal(pointer.x, pointer.y).y - top, measurer)
    }

    /** Table [id] resized to a new height: every row stretched or shrunk in proportion, none below its text. */
    fun stretch(
        state: CanvasState,
        id: String,
        measurer: TextMeasurer,
    ): CanvasState {
        val element = state.elements.find { it.id == id } ?: return state
        val heights = TableElements.rowHeights(element, measurer)
        val scale = element.height / heights.sum()
        return update(state, id, measurer) { t ->
            t.copy(rowMinHeights = heights.map { (it * scale).coerceIn(TableElements.MIN_ROW_HEIGHT, TableElements.MAX_ROW_HEIGHT) })
        }
    }

    /** Table [id] changed by [change], its width following its columns and its height its rows. */
    private fun update(
        state: CanvasState,
        id: String,
        measurer: TextMeasurer,
        change: (TableData) -> TableData,
    ): CanvasState {
        val (element, table) = state.elements.find { it.id == id }?.let { e -> e.table?.let { e to it } } ?: return state
        val changed = change(table)
        val updated = TableElements.fit(element.copy(table = changed, width = element.width * changed.cols / table.cols), measurer)
        return state.copy(elements = state.elements.map { if (it.id == id) updated else it })
    }
}
