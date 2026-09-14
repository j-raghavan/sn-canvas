package com.sncanvas.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.abs

/**
 * Draws canvas content through a [ViewTransform]: the elements, each in its
 * own style via [ElementPainter], the selection frame and handles, and the
 * drag-to-draw preview for [CanvasView]'s live frame; the same elements
 * into a bitmap for the note thumbnail (FR12); and a snapshot of the canvas
 * for the pencil to draw over. One draw path serves them all, so a thumbnail
 * always looks like the canvas; the live view paints through the e-ink
 * palette and the thumbnail in true colour (FR19). This class owns only paints
 * and pixels; *what* to draw is the view's call.
 *
 * A renderer draws on one thread at a time; the thumbnail, which renders off
 * the UI thread, gets a renderer of its own. Images come from [images], which
 * both share (FR22).
 */
internal class CanvasRenderer(
    measurer: TextMeasurer = AndroidTextMeasurer(),
    images: ImageSource = ImageSource.NONE,
) {
    private val painter = ElementPainter(measurer, images)

    private val previewPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.DKGRAY
            isAntiAlias = true
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }

    private val labelPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LABEL_TEXT_PX
            color = Color.BLACK
        }

    // The note thumbnail's hand-drawn frame (FR12, [ThumbnailFrame]): dark, so it stays crisp on e-ink.
    private val thumbnailFramePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val thumbnailTabPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.BLACK
        }

    private val thumbnailTabTextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = THUMBNAIL_TAG_TEXT_PX
            color = Color.WHITE
            // Dancing Script on the Supernote: a hand-lettered label to match the sketched frame.
            typeface = Typeface.create("cursive", Typeface.BOLD)
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

    /** Draws [elements] in z-order, each in its own style through [palette], leaving out the text being [editing]. */
    fun drawElements(
        canvas: Canvas,
        elements: List<Element>,
        transform: ViewTransform,
        palette: StylePalette,
        editing: CanvasController.EditTarget? = null,
    ) {
        val context = ElementPainter.Context(transform, palette, editing)
        for (element in elements) painter.draw(canvas, element, elements, context)
    }

    /**
     * The selected element's frame and handles (FR9): two endpoint handles for a
     * line or arrow; for anything else, a frame around it, four corner handles
     * and the rotate handle.
     */
    fun drawSelectionHandles(
        canvas: Canvas,
        elements: List<Element>,
        selectedId: String,
        transform: ViewTransform,
    ) {
        val element = elements.find { it.id == selectedId } ?: return
        if (element.hasEndpoints()) {
            val (start, end) = CanvasCore.resolveArrowEndpoints(element, elements)
            drawHandle(canvas, screenPoint(start, transform))
            drawHandle(canvas, screenPoint(end, transform))
        } else {
            val bounds = screenBounds(element, transform)
            withRotation(canvas, element, bounds) { canvas.drawRect(bounds, selectionFramePaint) }
            for (corner in CanvasCore.cornerPoints(element)) drawHandle(canvas, screenPoint(corner, transform))
            drawRotateHandle(canvas, element, transform)
        }
    }

    /** The dashed outline of what [tool] would draw for a drag from [from] to [to] (screen space), at [zoom]. */
    fun drawDragPreview(
        canvas: Canvas,
        tool: String,
        from: PointF,
        to: PointF,
        zoom: Double,
    ) {
        val bounds = RectF(minOf(from.x, to.x), minOf(from.y, to.y), maxOf(from.x, to.x), maxOf(from.y, to.y))
        when {
            tool == CanvasTools.TABLE -> drawTablePreview(canvas, bounds, to, zoom)
            CanvasTools.isConnector(tool) -> {
                canvas.drawLine(from.x, from.y, to.x, to.y, previewPaint)
                if (tool == CanvasTools.ARROW) drawArrowhead(canvas, from, to, previewPaint, PREVIEW_ARROWHEAD_PX)
            }
            tool == CanvasTools.ELLIPSE -> canvas.drawOval(bounds, previewPaint)
            else -> canvas.drawRect(bounds, previewPaint)
        }
    }

    /**
     * Renders [elements] into a fresh [sizePx]-square bitmap in true colour,
     * fitted by [ViewTransforms.computeThumbnailTransform] inside a hand-drawn
     * frame with a "Canvas" tag: the image "Save to Note" embeds (FR12), which has to
     * stand out from the handwriting around it as something to open. No
     * selection and no preview, since a saved image has no live gesture to
     * show. It draws only into its own bitmap, never an attached surface, which
     * is what makes it safe off the UI thread.
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
        drawThumbnailFrame(canvas, sizePx.toFloat())
        return bitmap
    }

    /** The thumbnail's hand-drawn frame ([ThumbnailFrame]) and its "Canvas" tag, tilted over the top-left corner like a stamp. */
    private fun drawThumbnailFrame(
        canvas: Canvas,
        size: Float,
    ) {
        ThumbnailFrame.passes(size.toDouble()).forEachIndexed { pass, sides ->
            // A bold line, then a finer one just inside it, like a pencil box gone over twice.
            thumbnailFramePaint.strokeWidth = if (pass == 0) THUMBNAIL_FRAME_BOLD_PX else THUMBNAIL_FRAME_FINE_PX
            thumbnailFramePaint.color = if (pass == 0) Color.BLACK else Color.DKGRAY
            sides.forEach { side -> canvas.drawPath(polylinePath(side), thumbnailFramePaint) }
        }
        val tagWidth = thumbnailTabTextPaint.measureText(THUMBNAIL_TAG_LABEL) + 2 * THUMBNAIL_TAG_PADDING_PX
        val tagHeight = THUMBNAIL_TAG_TEXT_PX + 2 * THUMBNAIL_TAG_PADDING_PX
        canvas.save()
        canvas.translate(THUMBNAIL_TAG_X_PX, THUMBNAIL_TAG_Y_PX)
        canvas.rotate(THUMBNAIL_TAG_TILT_DEGREES)
        canvas.drawPath(polylinePath(ThumbnailFrame.tag(tagWidth.toDouble(), tagHeight.toDouble()), closed = true), thumbnailTabPaint)
        // Centred on the tag's middle: ascent is negative, descent positive.
        val baseline = tagHeight / 2 - (thumbnailTabTextPaint.ascent() + thumbnailTabTextPaint.descent()) / 2
        canvas.drawText(THUMBNAIL_TAG_LABEL, THUMBNAIL_TAG_PADDING_PX, baseline, thumbnailTabTextPaint)
        canvas.restore()
    }

    /**
     * The canvas as the view shows it, into [reuse] when that is the right size:
     * the backdrop the pencil's live stroke is drawn over, so a pen move redraws
     * one bitmap and the stroke rather than every element.
     */
    fun renderSnapshot(
        elements: List<Element>,
        transform: ViewTransform,
        width: Int,
        height: Int,
        reuse: Bitmap?,
    ): Bitmap {
        val bitmap =
            reuse?.takeIf { it.width == width && it.height == height } ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, width.toFloat(), height.toFloat())
        drawElements(canvas, elements, transform, StylePalette.EINK)
        return bitmap
    }

    /** The grid a table drag makes (FR24), in whole cells from the drag's corner, labelled with its size by the pen. */
    private fun drawTablePreview(
        canvas: Canvas,
        drag: RectF,
        pen: PointF,
        zoom: Double,
    ) {
        val (rows, cols) = TableElements.gridForDrag(abs(drag.width()) / zoom, abs(drag.height()) / zoom)
        val cellWidth = (TableElements.CELL_WIDTH * zoom).toFloat()
        val cellHeight = (TableElements.MIN_ROW_HEIGHT * zoom).toFloat()
        for (row in 0..rows) {
            canvas.drawLine(
                drag.left,
                drag.top + row * cellHeight,
                drag.left + cols * cellWidth,
                drag.top + row * cellHeight,
                previewPaint,
            )
        }
        for (col in 0..cols) {
            canvas.drawLine(
                drag.left + col * cellWidth,
                drag.top,
                drag.left + col * cellWidth,
                drag.top + rows * cellHeight,
                previewPaint,
            )
        }
        canvas.drawText("$rows × $cols", pen.x + LABEL_OFFSET_PX, pen.y - LABEL_OFFSET_PX, labelPaint)
    }

    private fun drawHandle(
        canvas: Canvas,
        center: PointF,
    ) {
        val half = HANDLE_DRAW_SIZE_PX / 2f
        canvas.drawRect(center.x - half, center.y - half, center.x + half, center.y + half, handlePaint)
    }

    /** A circular-arrow handle above the element's top-center (rotated with it), joined to it by a short stem. */
    private fun drawRotateHandle(
        canvas: Canvas,
        element: Element,
        transform: ViewTransform,
    ) {
        val stem = screenPoint(element.toWorld(element.x + element.width / 2, element.y), transform)
        val handle = screenPoint(CanvasCore.rotateHandlePoint(element, transform.zoom), transform)
        canvas.drawLine(stem.x, stem.y, handle.x, handle.y, rotateStemPaint)
        drawRotateHandleGlyph(canvas, handle.x, handle.y, ROTATE_HANDLE_RADIUS_PX)
    }

    private companion object {
        /** The note thumbnail's size (FR12): a square PNG, independent of the live view's size. */
        const val THUMBNAIL_SIZE_PX = 400

        // Clear of the frame and the tag, so the drawing never touches either.
        const val THUMBNAIL_PADDING_PX = 56.0
        const val THUMBNAIL_FRAME_BOLD_PX = 3.5f
        const val THUMBNAIL_FRAME_FINE_PX = 1.5f
        const val THUMBNAIL_TAG_TEXT_PX = 22f
        const val THUMBNAIL_TAG_PADDING_PX = 8f

        // The tag sits over the frame's top-left corner, tilted a little, like a stamp.
        const val THUMBNAIL_TAG_X_PX = 6f
        const val THUMBNAIL_TAG_Y_PX = 10f
        const val THUMBNAIL_TAG_TILT_DEGREES = -4f
        const val THUMBNAIL_TAG_LABEL = "Canvas"
        const val HANDLE_DRAW_SIZE_PX = 24f
        const val ROTATE_HANDLE_RADIUS_PX = 22f
        const val PREVIEW_ARROWHEAD_PX = 28f
        const val LABEL_TEXT_PX = 28f
        const val LABEL_OFFSET_PX = 16f
    }
}
