package com.snsupercanvas.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Draws SuperCanvas's top-right minimap: every element as a thin outline plus
 * a shaded box for the currently visible area, both fitted via
 * [SuperCanvasCore.computeMinimapTransform] (content ∪ viewport, so the box
 * never falls outside the minimap however far the user has panned). The box
 * matches the view's aspect ratio so the viewport rectangle reads true.
 *
 * [SuperCanvasView] decides *when* the minimap shows (during pan/zoom, hidden
 * shortly after); this class owns only *what* it looks like. Its paints are
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

    fun draw(
        canvas: Canvas,
        state: CanvasState,
        viewWidth: Int,
        viewHeight: Int,
    ) {
        if (viewWidth == 0 || viewHeight == 0) return
        val boxHeight = (WIDTH_PX * viewHeight / viewWidth).coerceIn(MIN_HEIGHT_PX, MAX_HEIGHT_PX)
        val boxLeft = viewWidth - MARGIN_PX - WIDTH_PX
        val visible =
            WorldRect(
                left = state.viewportX,
                top = state.viewportY,
                right = state.viewportX + viewWidth / state.zoom,
                bottom = state.viewportY + viewHeight / state.zoom,
            )
        val fit =
            SuperCanvasCore.computeMinimapTransform(
                state.elements,
                visible,
                WIDTH_PX.toDouble(),
                boxHeight.toDouble(),
                PADDING_PX,
            )

        canvas.save()
        canvas.translate(boxLeft, MARGIN_PX)
        canvas.drawRect(0f, 0f, WIDTH_PX, boxHeight, backgroundPaint)
        canvas.clipRect(0f, 0f, WIDTH_PX, boxHeight)
        val viewportBounds = toScreen(visible, fit)
        canvas.drawRect(viewportBounds, viewportFillPaint)
        for (element in state.elements) drawElement(canvas, element, state.elements, fit)
        canvas.drawRect(viewportBounds, viewportPaint)
        canvas.restore()
        canvas.drawRect(boxLeft, MARGIN_PX, boxLeft + WIDTH_PX, MARGIN_PX + boxHeight, borderPaint)
    }

    private fun toScreen(
        rect: WorldRect,
        fit: ThumbnailTransform,
    ): RectF =
        RectF(
            ((rect.left - fit.viewportX) * fit.zoom).toFloat(),
            ((rect.top - fit.viewportY) * fit.zoom).toFloat(),
            ((rect.right - fit.viewportX) * fit.zoom).toFloat(),
            ((rect.bottom - fit.viewportY) * fit.zoom).toFloat(),
        )

    private fun drawElement(
        canvas: Canvas,
        element: Element,
        elements: List<Element>,
        fit: ThumbnailTransform,
    ) {
        if (element.hasEndpoints()) {
            val (start, end) = SuperCanvasCore.resolveArrowEndpoints(element, elements)
            canvas.drawLine(
                ((start.x - fit.viewportX) * fit.zoom).toFloat(),
                ((start.y - fit.viewportY) * fit.zoom).toFloat(),
                ((end.x - fit.viewportX) * fit.zoom).toFloat(),
                ((end.y - fit.viewportY) * fit.zoom).toFloat(),
                elementPaint,
            )
            return
        }
        val worldBounds = WorldRect(element.x, element.y, element.x + element.width, element.y + element.height)
        drawRotatedBox(canvas, element, toScreen(worldBounds, fit), elementPaint)
    }

    private companion object {
        const val WIDTH_PX = 300f
        const val MIN_HEIGHT_PX = 160f
        const val MAX_HEIGHT_PX = 420f
        const val MARGIN_PX = 24f
        const val PADDING_PX = 12.0
    }
}
