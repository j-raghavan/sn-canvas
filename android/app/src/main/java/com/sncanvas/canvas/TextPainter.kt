package com.sncanvas.canvas

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint

/**
 * Draws text boxes, sticky notes and table cells (FR6/FR21/FR24): text laid
 * out as [AndroidTextMeasurer] measured it, scaled to the zoom. The text being
 * edited is left out, since the keyboard editor shows it in its place.
 */
internal class TextPainter(
    private val measurer: TextMeasurer,
) {
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** A text box's text, or a note's tinted square and its text. */
    fun drawTextElement(
        canvas: Canvas,
        element: Element,
        context: ElementPainter.Context,
    ) {
        val zoom = context.transform.zoom
        val bounds = screenBounds(element, context.transform)
        val isNote = element.type == CanvasTools.NOTE
        withRotation(canvas, element, bounds) {
            if (isNote) {
                val tone = element.style.color
                val tint = if (element.style.fill == FillStyle.SOLID) context.palette.solidFill(tone) else context.palette.semiFill(tone)
                notePaint.color = withAlpha(tint, element.style.alpha)
                canvas.drawRect(bounds, notePaint)
            }
            if (context.editing?.elementId != element.id) {
                // A note is written in black on its tint; a text box in its own colour.
                val color = context.palette.stroke(if (isNote) StyleColor.BLACK else element.style.color)
                drawText(
                    canvas,
                    element.text.orEmpty(),
                    inset(bounds, zoom),
                    TextElements.fontSize(element) * zoom,
                    withAlpha(color, element.style.alpha),
                )
            }
        }
    }

    /** A table's inner grid lines and cell text; its frame and fill are drawn as a rectangle by [ElementPainter]. */
    fun drawTableCells(
        canvas: Canvas,
        element: Element,
        context: ElementPainter.Context,
    ) {
        val table = element.table ?: return
        val zoom = context.transform.zoom
        val bounds = screenBounds(element, context.transform)
        val color = withAlpha(context.palette.stroke(element.style.color), element.style.alpha)
        val columnWidth = bounds.width() / table.cols
        // Each row's top, in screen pixels below the table's top edge.
        val rowTops = TableElements.rowHeights(element, measurer).runningFold(0.0, Double::plus).map { (it * zoom).toFloat() }
        gridPaint.color = color
        gridPaint.strokeWidth = (element.style.strokeWidthPx(zoom) / 2).coerceAtLeast(1f)
        withRotation(canvas, element, bounds) {
            for (col in 1 until table.cols) {
                canvas.drawLine(bounds.left + col * columnWidth, bounds.top, bounds.left + col * columnWidth, bounds.bottom, gridPaint)
            }
            for (row in 1 until table.rows) {
                canvas.drawLine(
                    bounds.left,
                    bounds.top + rowTops[row],
                    bounds.right,
                    bounds.top + rowTops[row],
                    gridPaint,
                )
            }
            for (index in table.cells.indices) {
                if (context.editing == ElementPainter.HiddenText(element.id, index)) continue
                val row = index / table.cols
                val left = bounds.left + index % table.cols * columnWidth
                val cell = RectF(left, bounds.top + rowTops[row], left + columnWidth, bounds.top + rowTops[row + 1])
                drawText(canvas, table.cells[index], inset(cell, zoom), TableElements.fontSize(element) * zoom, color)
            }
        }
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        box: RectF,
        fontSizePx: Double,
        color: Int,
    ) {
        if (text.isEmpty()) return
        textPaint.textSize = fontSizePx.toFloat()
        textPaint.color = color
        val layout = textLayout(text, textPaint, box.width())
        canvas.save()
        canvas.translate(box.left, box.top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun inset(
        box: RectF,
        zoom: Double,
    ): RectF {
        val pad = (TextElements.PADDING * zoom).toFloat()
        return RectF(box.left + pad, box.top + pad, box.right - pad, box.bottom - pad)
    }

    private fun withAlpha(
        color: Int,
        alpha: Int,
    ): Int = (color and 0xFFFFFF) or (alpha shl 24)
}
