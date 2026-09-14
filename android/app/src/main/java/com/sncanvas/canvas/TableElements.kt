package com.sncanvas.canvas

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tables (FR24): a grid of text cells that moves, resizes and rotates as one
 * element. Columns share the width equally. Each row is as tall as the user
 * made it, or taller when its tallest cell's text needs more, so the table's
 * height follows both. [TableEdits] changes the grid.
 */
object TableElements {
    /** A new column's width in world units. */
    const val CELL_WIDTH = 160.0
    const val MIN_ROW_HEIGHT = 48.0

    /** The tallest a row can be made: a sanity bound for drags and loaded files alike. */
    const val MAX_ROW_HEIGHT = 4000.0
    const val MAX_ROWS = 12
    const val MAX_COLS = 8

    // Table text is set smaller than a text box's, so cells stay compact.
    private const val FONT_SCALE = 0.75

    fun fontSize(element: Element): Double = element.style.size.fontSize * FONT_SCALE

    /** The rows and columns a drag of [width] × [height] world units makes, like sn-tables' grid picker. */
    fun gridForDrag(
        width: Double,
        height: Double,
    ): Pair<Int, Int> =
        (height / MIN_ROW_HEIGHT).roundToInt().coerceIn(1, MAX_ROWS) to (width / CELL_WIDTH).roundToInt().coerceIn(1, MAX_COLS)

    /** A new, empty table dragged from [from] to [to], in whichever direction; each column [CELL_WIDTH] wide. */
    fun create(
        id: String,
        from: Point,
        to: Point,
    ): Element {
        val (rows, cols) = gridForDrag(abs(to.x - from.x), abs(to.y - from.y))
        return Element(
            id = id,
            type = CanvasTools.TABLE,
            x = minOf(from.x, to.x),
            y = minOf(from.y, to.y),
            width = cols * CELL_WIDTH,
            height = rows * MIN_ROW_HEIGHT,
            table = TableData.empty(rows, cols),
        )
    }

    /** Each row's height: as tall as the user made it, or as its tallest cell's text plus padding needs. */
    fun rowHeights(
        element: Element,
        measurer: TextMeasurer,
    ): List<Double> {
        val table = element.table ?: return emptyList()
        val textWidth = (element.width / table.cols - 2 * TextElements.PADDING).coerceAtLeast(1.0)
        return (0 until table.rows).map { row ->
            val cellHeights = (0 until table.cols).map { col -> measurer.height(table.cell(row, col), textWidth, fontSize(element)) }
            maxOf(table.rowMinHeights[row], cellHeights.max() + 2 * TextElements.PADDING)
        }
    }

    /** [element] with its height fitted to its rows. */
    fun fit(
        element: Element,
        measurer: TextMeasurer,
    ): Element = if (element.table == null) element else element.copy(height = rowHeights(element, measurer).sum())

    /** Cell [index]'s rect in the table's unrotated frame; the whole element if it is not a table. */
    fun cellRect(
        element: Element,
        index: Int,
        measurer: TextMeasurer,
    ): WorldRect {
        val table = element.table ?: return WorldRect(element.x, element.y, element.x + element.width, element.y + element.height)
        val heights = rowHeights(element, measurer)
        val row = index / table.cols
        val columnWidth = element.width / table.cols
        val left = element.x + index % table.cols * columnWidth
        val top = element.y + heights.take(row).sum()
        return WorldRect(left, top, left + columnWidth, top + heights[row])
    }

    /** The index of the cell under [local], a point in the table's unrotated frame; null outside the table. */
    fun cellAt(
        element: Element,
        local: Point,
        measurer: TextMeasurer,
    ): Int? {
        val table = element.table ?: return null
        val rowBottoms = rowHeights(element, measurer).runningReduce(Double::plus).map { element.y + it }
        val row = rowBottoms.indexOfFirst { local.y <= it }
        val col = ((local.x - element.x) / (element.width / table.cols)).toInt()
        val inside = local.x >= element.x && local.y >= element.y && row >= 0 && col < table.cols
        return if (inside) row * table.cols + col else null
    }

    /**
     * The row whose bottom edge lies within [tolerance] of [local], a point in
     * the table's unrotated frame: dragging that edge makes the row taller or
     * shorter. Null away from every edge, beside the table, or for a non-table.
     */
    fun rowEdgeAt(
        element: Element,
        local: Point,
        tolerance: Double,
        measurer: TextMeasurer,
    ): Int? {
        if (local.x < element.x || local.x > element.x + element.width) return null
        val bottoms = rowHeights(element, measurer).runningReduce(Double::plus)
        return bottoms.indexOfFirst { abs(element.y + it - local.y) <= tolerance }.takeIf { it >= 0 }
    }

    /** Cell [index]'s text; empty for a bad index or a non-table. */
    fun cellText(
        element: Element,
        index: Int,
    ): String {
        val table = element.table ?: return ""
        return table.cells.getOrElse(index) { "" }
    }
}
