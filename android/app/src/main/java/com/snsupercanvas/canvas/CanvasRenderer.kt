package com.snsupercanvas.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF

/**
 * Draws canvas content through a [ViewTransform]: the elements, selection
 * handles and drag-to-draw preview of [SuperCanvasView]'s live frame, and the
 * same elements into a bitmap for the note thumbnail (FR12). One draw path
 * serves both, so a thumbnail always looks like the canvas. This class owns
 * only paints and pixels; *what* to draw (live previews, the selection) is the
 * view's call.
 *
 * [renderThumbnail] runs off the UI thread, possibly alongside a live frame;
 * that is safe because both only read the shared paints.
 */
internal class CanvasRenderer {
    private val elementPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.DKGRAY
            isAntiAlias = true
        }

    private val selectedPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.BLACK
            isAntiAlias = true
        }

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

    /** Draws [elements] in z-order, outlining [selectedId] (null for none, as in a thumbnail) more heavily. */
    fun drawElements(
        canvas: Canvas,
        elements: List<Element>,
        transform: ViewTransform,
        selectedId: String?,
    ) {
        for (element in elements) {
            val paint = if (element.id == selectedId) selectedPaint else elementPaint
            if (element.hasEndpoints()) {
                drawConnector(canvas, element, elements, transform, paint)
            } else {
                drawRotatedBox(canvas, element, screenBounds(element, transform), paint)
            }
        }
    }

    /** The selected element's handles (FR9): two endpoint handles for a line/arrow; four corners plus rotate for a shape. */
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
            if (tool == CanvasTools.ARROW) drawArrowhead(canvas, from, to, previewPaint)
            return
        }
        val bounds = RectF(minOf(from.x, to.x), minOf(from.y, to.y), maxOf(from.x, to.x), maxOf(from.y, to.y))
        if (tool == CanvasTools.ELLIPSE) canvas.drawOval(bounds, previewPaint) else canvas.drawRect(bounds, previewPaint)
    }

    /**
     * Renders [elements] into a fresh [sizePx]-square bitmap, fitted by
     * [ViewTransforms.computeThumbnailTransform]: the image "Save to Note"
     * embeds (FR12). No selection and no preview, since a saved image has no
     * live gesture to show. It draws only into its own bitmap, never an
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
        drawElements(canvas, elements, fit, selectedId = null)
        return bitmap
    }

    private fun screenPoint(
        world: Point,
        transform: ViewTransform,
    ): PointF = PointF(transform.screenX(world.x).toFloat(), transform.screenY(world.y).toFloat())

    private fun screenBounds(
        element: Element,
        transform: ViewTransform,
    ): RectF =
        RectF(
            transform.screenX(element.x).toFloat(),
            transform.screenY(element.y).toFloat(),
            transform.screenX(element.x + element.width).toFloat(),
            transform.screenY(element.y + element.height).toFloat(),
        )

    private fun drawConnector(
        canvas: Canvas,
        element: Element,
        elements: List<Element>,
        transform: ViewTransform,
        paint: Paint,
    ) {
        val (startWorld, endWorld) = SuperCanvasCore.resolveArrowEndpoints(element, elements)
        val start = screenPoint(startWorld, transform)
        val end = screenPoint(endWorld, transform)
        canvas.drawLine(start.x, start.y, end.x, end.y, paint)
        if (element.type == CanvasTools.ARROW) drawArrowhead(canvas, start, end, paint)
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

    companion object {
        /** The note thumbnail's size (FR12): a square PNG, independent of the live view's size. */
        private const val THUMBNAIL_SIZE_PX = 400
        private const val THUMBNAIL_PADDING_PX = 24.0
        private const val HANDLE_DRAW_SIZE_PX = 24f
        private const val ROTATE_HANDLE_RADIUS_PX = 22f
    }
}
