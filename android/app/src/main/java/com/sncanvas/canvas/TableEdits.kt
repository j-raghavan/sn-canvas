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
                    // rest and stops the table looking like one table (#53).
                    rowMinHeights =
                        t.rowMinHeights + (t.rowMinHeights.lastOrNull() ?: TableElements.MIN_ROW_HEIGHT),
                )
            }
        }

    fun removeRow(
        state: CanvasState,
        id: String,
        measurer: TextMeasurer,
    ): CanvasState =
        update(state, id, measurer) { t ->
            if (t.rows <= 1) {
                t
            } else {
                t.copy(rows = t.rows - 1, cells = t.cells.dropLast(t.cols), rowMinHeights = t.rowMinHeights.dropLast(1))
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

    fun removeColumn(
        state: CanvasState,
        id: String,
        measurer: TextMeasurer,
    ): CanvasState =
        update(state, id, measurer) { t ->
            if (t.cols <= 1) t else t.copy(cols = t.cols - 1, cells = t.cells.chunked(t.cols).flatMap { it.dropLast(1) })
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
