package com.snsupercanvas.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF

/**
 * Draws canvas content through a [ViewTransform]: the elements, each in its
 * own style via [ElementPainter], the selection frame and handles, and the
 * drag-to-draw preview for [SuperCanvasView]'s live frame, plus the same
 * elements into a bitmap for the note thumbnail (FR12). One draw path serves
 * both, so a thumbnail always looks like the canvas; the live view paints
 * through the e-ink palette and the thumbnail in true colour (FR19). This
 * class owns only paints and pixels; *what* to draw is the view's call.
 *
 * A renderer draws on one thread at a time; the thumbnail, which renders off
 * the UI thread, gets a renderer of its own.
 */
internal class CanvasRenderer {
    private val painter = ElementPainter()

    private val previewPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.DKGRAY
            isAntiAlias = true
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }

    private val backgroundPaint =
        Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

    private val handlePaint =
        Paint().apply {
            style = Paint.Style.FILL
            color = Color.BLACK
        }

    private val selectionFramePaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.BLACK
            isAntiAlias = true
        }

    private val rotateStemPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.BLACK
            isAntiAlias = true
        }

    fun drawBackground(
        canvas: Canvas,
        width: Float,
        height: Float,
    ) {
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)
    }

    /** Draws [elements] in z-order, each in its own style through [palette]. */
    fun drawElements(
        canvas: Canvas,
        elements: List<Element>,
        transform: ViewTransform,
        palette: StylePalette,
    ) {
        for (element in elements) painter.draw(canvas, element, elements, transform, palette)
    }

    /**
     * The selected element's frame and handles (FR9): two endpoint handles for a
     * line or arrow; for a shape, a frame around it, four corner handles and the
     * rotate handle.
     */
    fun drawSelectionHandles(
        canvas: Canvas,
        elements: List<Element>,
        selectedId: String,
        transform: ViewTransform,
    ) {
        val element = elements.find { it.id == selectedId } ?: return
        if (element.hasEndpoints()) {
            val (start, end) = SuperCanvasCore.resolveArrowEndpoints(element, elements)
            drawHandle(canvas, screenPoint(start, transform))
            drawHandle(canvas, screenPoint(end, transform))
        } else {
            val bounds = screenBounds(element, transform)
            withRotation(canvas, element, bounds) { canvas.drawRect(bounds, selectionFramePaint) }
            for (corner in SuperCanvasCore.cornerPoints(element)) drawHandle(canvas, screenPoint(corner, transform))
            drawRotateHandle(canvas, element, transform)
        }
    }

    /** The dashed outline of what [tool] would draw for a drag from [from] to [to] (screen space). */
    fun drawDragPreview(
        canvas: Canvas,
        tool: String,
        from: PointF,
        to: PointF,
    ) {
        if (CanvasTools.isConnector(tool)) {
            canvas.drawLine(from.x, from.y, to.x, to.y, previewPaint)
            if (tool == CanvasTools.ARROW) drawArrowhead(canvas, from, to, previewPaint, PREVIEW_ARROWHEAD_PX)
            return
        }
        val bounds = RectF(minOf(from.x, to.x), minOf(from.y, to.y), maxOf(from.x, to.x), maxOf(from.y, to.y))
        if (tool == CanvasTools.ELLIPSE) canvas.drawOval(bounds, previewPaint) else canvas.drawRect(bounds, previewPaint)
    }

    /**
     * Renders [elements] into a fresh [sizePx]-square bitmap in true colour,
     * fitted by [ViewTransforms.computeThumbnailTransform]: the image "Save to
     * Note" embeds (FR12). No selection and no preview, since a saved image has
     * no live gesture to show. It draws only into its own bitmap, never an
     * attached surface, which is what makes it safe off the UI thread.
     */
    fun renderThumbnail(
        elements: List<Element>,
        sizePx: Int = THUMBNAIL_SIZE_PX,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, sizePx.toFloat(), sizePx.toFloat())
        val fit = ViewTransforms.computeThumbnailTransform(elements, sizePx.toDouble(), THUMBNAIL_PADDING_PX)
        drawElements(canvas, elements, fit, StylePalette.TRUE_COLOR)
        return bitmap
    }

    private fun drawHandle(
        canvas: Canvas,
        center: PointF,
    ) {
        val half = HANDLE_DRAW_SIZE_PX / 2f
        canvas.drawRect(center.x - half, center.y - half, center.x + half, center.y + half, handlePaint)
    }

    /** A circular-arrow handle above the shape's top-center (rotated with it), joined to the shape by a short stem. */
    private fun drawRotateHandle(
        canvas: Canvas,
        element: Element,
        transform: ViewTransform,
    ) {
        val stem = screenPoint(element.toWorld(element.x + element.width / 2, element.y), transform)
        val handle = screenPoint(SuperCanvasCore.rotateHandlePoint(element, transform.zoom), transform)
        canvas.drawLine(stem.x, stem.y, handle.x, handle.y, rotateStemPaint)
        drawRotateHandleGlyph(canvas, handle.x, handle.y, ROTATE_HANDLE_RADIUS_PX)
    }

    private companion object {
        /** The note thumbnail's size (FR12): a square PNG, independent of the live view's size. */
        const val THUMBNAIL_SIZE_PX = 400
        const val THUMBNAIL_PADDING_PX = 24.0
        const val HANDLE_DRAW_SIZE_PX = 24f
        const val ROTATE_HANDLE_RADIUS_PX = 22f
        const val PREVIEW_ARROWHEAD_PX = 28f
    }
}
