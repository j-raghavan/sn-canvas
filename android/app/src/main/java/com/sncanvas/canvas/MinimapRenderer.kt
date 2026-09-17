package com.sncanvas.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Draws Canvas's minimap: every element as a thin outline plus a shaded box
 * for the currently visible area, inside the box [ViewTransforms.minimapLayout]
 * measures and through the transform it computes (content ∪ viewport, so the
 * box never falls outside the minimap however far the user has panned).
 *
 * [CanvasView] decides *when* the minimap shows (during pan/zoom, hidden
 * shortly after) and routes touches on it; this class owns only *what* it
 * looks like. Its paints are
 * thin on purpose — the live element paints and arrowheads are sized for
 * full-scale drawing, not a 300px overview.
 */
internal class MinimapRenderer {
    private val backgroundPaint =
        Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

    private val borderPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.BLACK
            isAntiAlias = true
        }

    private val elementPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.DKGRAY
            isAntiAlias = true
        }

    private val viewportFillPaint =
        Paint().apply {
            style = Paint.Style.FILL
            color = Color.LTGRAY
        }

    private val viewportPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.BLACK
        }

    /**
     * Draws [elements] and the [visible] rectangle into [layout]'s box. The
     * rectangle is passed in rather than read from [layout], so that while the
     * minimap is being dragged the box and its transform stay where they were
     * grabbed and only the rectangle moves.
     */
    fun draw(
        canvas: Canvas,
        elements: List<Element>,
        visible: WorldRect,
        layout: MinimapLayout,
    ) {
        val boxLeft = layout.left.toFloat()
        val boxTop = layout.top.toFloat()
        val boxWidth = layout.width.toFloat()
        val boxHeight = layout.height.toFloat()
        canvas.save()
        canvas.translate(boxLeft, boxTop)
        canvas.drawRect(0f, 0f, boxWidth, boxHeight, backgroundPaint)
        canvas.clipRect(0f, 0f, boxWidth, boxHeight)
        val viewportBounds = toScreen(visible, layout.fit)
        canvas.drawRect(viewportBounds, viewportFillPaint)
        for (element in elements) drawElement(canvas, element, elements, layout.fit)
        canvas.drawRect(viewportBounds, viewportPaint)
        canvas.restore()
        canvas.drawRect(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight, borderPaint)
    }

    private fun toScreen(
        rect: WorldRect,
        fit: ViewTransform,
    ): RectF =
        RectF(
            fit.screenX(rect.left).toFloat(),
            fit.screenY(rect.top).toFloat(),
            fit.screenX(rect.right).toFloat(),
            fit.screenY(rect.bottom).toFloat(),
        )

    private fun drawElement(
        canvas: Canvas,
        element: Element,
        elements: List<Element>,
        fit: ViewTransform,
    ) {
        if (element.hasEndpoints()) {
            val (start, end) = CanvasCore.resolveArrowEndpoints(element, elements)
            canvas.drawLine(
                fit.screenX(start.x).toFloat(),
                fit.screenY(start.y).toFloat(),
                fit.screenX(end.x).toFloat(),
                fit.screenY(end.y).toFloat(),
                elementPaint,
            )
            return
        }
        val worldBounds = WorldRect(element.x, element.y, element.x + element.width, element.y + element.height)
        drawRotatedBox(canvas, element, toScreen(worldBounds, fit), elementPaint)
    }
}
