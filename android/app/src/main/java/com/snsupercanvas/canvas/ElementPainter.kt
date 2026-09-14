package com.snsupercanvas.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Paints one element in its style (FR19): shapes and connectors with colour
 * through a [StylePalette], opacity, fill (none, semi, solid or hatched
 * pattern), dash (hand-drawn, dashed, dotted or solid) and size; freehand
 * strokes as lines as wide as the pen pressed (FR5); images from [images],
 * framed by their style's outline, or by none (FR22); and text, notes and table
 * cells through [TextPainter]. [CanvasRenderer] decides what to draw; this
 * decides how an element looks, with the outlines ShapeDrawing.kt builds. Its
 * paints are reconfigured for every element, so a painter stays on one
 * thread: the note thumbnail renders with a renderer of its own.
 */
internal class ElementPainter(
    measurer: TextMeasurer,
    private val images: ImageSource,
) {
    /** How to paint: through which transform and palette, leaving out the text being edited. */
    data class Context(
        val transform: ViewTransform,
        val palette: StylePalette,
        val editing: CanvasController.EditTarget? = null,
    )

    private val textPainter = TextPainter(measurer)

    private val strokePath = Path()
    private val strokePaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

    private val fillPaint =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

    private val hatchPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = HATCH_WIDTH_PX
            isAntiAlias = true
        }

    private val arrowheadPaint = Paint()

    // Filtered, so a scaled-down photo stays smooth rather than blocky.
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun draw(
        canvas: Canvas,
        element: Element,
        elements: List<Element>,
        context: Context,
    ) {
        val image = element.image
        when {
            element.hasEndpoints() -> drawConnector(canvas, element, elements, context)
            element.points != null -> drawStroke(canvas, element, context)
            image != null -> drawImage(canvas, element, image, context)
            TextElements.isEditable(element) -> textPainter.drawTextElement(canvas, element, context)
            else -> {
                drawShape(canvas, element, context)
                if (element.table != null) textPainter.drawTableCells(canvas, element, context)
            }
        }
    }

    private fun drawShape(
        canvas: Canvas,
        element: Element,
        context: Context,
    ) {
        val bounds = screenBounds(element, context.transform)
        val outline = shapePath(element, bounds, context.transform)
        withRotation(canvas, element, bounds) {
            drawFill(canvas, outline, bounds, element.style, context.palette)
            drawOutline(canvas, outline, element.style, context)
        }
    }

    /** An image (FR22), stretched to its box and faded by its opacity, framed by its style's outline; a missing file shows crossed out. */
    private fun drawImage(
        canvas: Canvas,
        element: Element,
        image: ImageData,
        context: Context,
    ) {
        val bounds = screenBounds(element, context.transform)
        withRotation(canvas, element, bounds) {
            val bitmap = images.bitmap(image)
            if (bitmap == null) {
                drawMissingImage(canvas, bounds)
            } else {
                imagePaint.alpha = element.style.alpha
                canvas.drawBitmap(bitmap, null, bounds, imagePaint)
            }
            drawOutline(canvas, shapePath(element, bounds, context.transform), element.style, context)
        }
    }

    /** A light box with a cross through it: where an image's file can't be read. */
    private fun drawMissingImage(
        canvas: Canvas,
        bounds: RectF,
    ) {
        fillPaint.color = MISSING_IMAGE_FILL
        canvas.drawRect(bounds, fillPaint)
        hatchPaint.color = Color.GRAY
        canvas.drawLine(bounds.left, bounds.top, bounds.right, bounds.bottom, hatchPaint)
        canvas.drawLine(bounds.left, bounds.bottom, bounds.right, bounds.top, hatchPaint)
    }

    /** Strokes [outline] in [style]; a dash of none leaves it undrawn. */
    private fun drawOutline(
        canvas: Canvas,
        outline: Path,
        style: ShapeStyle,
        context: Context,
    ) {
        if (style.dash == DashStyle.NONE) return
        configureStroke(style, context.palette, style.strokeWidthPx(context.transform.zoom))
        canvas.drawPath(outline, strokePaint)
    }

    private fun drawConnector(
        canvas: Canvas,
        element: Element,
        elements: List<Element>,
        context: Context,
    ) {
        val (start, end) = SuperCanvasCore.resolveArrowEndpoints(element, elements)
        val style = element.style
        val width = style.strokeWidthPx(context.transform.zoom)
        val points = if (style.dash == DashStyle.DRAW) wobbly(listOf(start, end), closed = false, element) else listOf(start, end)
        configureStroke(style, context.palette, width)
        canvas.drawPath(screenPath(points, context.transform), strokePaint)
        if (element.type == CanvasTools.ARROW) {
            // The head is always solid, even on a dashed or dotted arrow.
            arrowheadPaint.set(strokePaint)
            arrowheadPaint.pathEffect = null
            val length = (width * ARROWHEAD_PER_STROKE).coerceIn(MIN_ARROWHEAD_PX, MAX_ARROWHEAD_PX)
            drawArrowhead(
                canvas,
                screenPoint(start, context.transform),
                screenPoint(points.last(), context.transform),
                arrowheadPaint,
                length,
            )
        }
    }

    /** A freehand stroke (FR5): always solid, each segment as wide as the pen pressed there; a single sample is a dot. */
    private fun drawStroke(
        canvas: Canvas,
        element: Element,
        context: Context,
    ) {
        val transform = context.transform
        // One even width, like the firmware's needle nib that inks the stroke live (StrokeElements.PEN_WIDTH_PX).
        val width =
            element.strokeWidth?.let { (it * transform.zoom).toFloat().coerceAtLeast(ShapeStyle.MIN_STROKE_PX) }
                ?: element.style.strokeWidthPx(transform.zoom)
        val points = StrokeElements.worldPoints(element)
        strokePath.rewind()
        for ((index, point) in points.withIndex()) {
            val x = transform.screenX(point.x).toFloat()
            val y = transform.screenY(point.y).toFloat()
            if (index == 0) strokePath.moveTo(x, y) else strokePath.lineTo(x, y)
        }
        configureStroke(element.style.copy(dash = DashStyle.SOLID), context.palette, width)
        withRotation(canvas, element, screenBounds(element, transform)) {
            if (points.size == 1) {
                canvas.drawPoint(transform.screenX(points[0].x).toFloat(), transform.screenY(points[0].y).toFloat(), strokePaint)
            } else {
                canvas.drawPath(strokePath, strokePaint)
            }
        }
    }

    private fun drawFill(
        canvas: Canvas,
        outline: Path,
        bounds: RectF,
        style: ShapeStyle,
        palette: StylePalette,
    ) {
        fillPaint.color =
            when (style.fill) {
                FillStyle.NONE -> return
                FillStyle.SEMI, FillStyle.PATTERN -> palette.semiFill(style.color)
                FillStyle.SOLID -> palette.solidFill(style.color)
            }
        fillPaint.alpha = style.alpha
        canvas.drawPath(outline, fillPaint)
        if (style.fill == FillStyle.PATTERN) hatch(canvas, outline, bounds, palette.patternLine(style.color), style.alpha)
    }

    /** 45° hatching clipped to [outline]. */
    private fun hatch(
        canvas: Canvas,
        outline: Path,
        bounds: RectF,
        color: Int,
        alpha: Int,
    ) {
        hatchPaint.color = color
        hatchPaint.alpha = alpha
        canvas.save()
        canvas.clipPath(outline)
        var offset = -bounds.height()
        while (offset < bounds.width()) {
            canvas.drawLine(bounds.left + offset, bounds.bottom, bounds.left + offset + bounds.height(), bounds.top, hatchPaint)
            offset += HATCH_SPACING_PX
        }
        canvas.restore()
    }

    private fun configureStroke(
        style: ShapeStyle,
        palette: StylePalette,
        width: Float,
    ) {
        // Colour first: setting it resets the alpha.
        strokePaint.color = palette.stroke(style.color)
        strokePaint.alpha = style.alpha
        strokePaint.strokeWidth = width
        strokePaint.pathEffect =
            when (style.dash) {
                DashStyle.DASHED -> DashPathEffect(floatArrayOf(width * DASH_LENGTH, width * DASH_GAP), 0f)
                // Zero-length dashes with round caps draw as dots.
                DashStyle.DOTTED -> DashPathEffect(floatArrayOf(0f, width * DOT_GAP), 0f)
                // A connector never goes without its line, so none draws it solid.
                DashStyle.DRAW, DashStyle.SOLID, DashStyle.NONE -> null
            }
    }

    private companion object {
        const val ARROWHEAD_PER_STROKE = 8f
        const val MIN_ARROWHEAD_PX = 10f
        const val MAX_ARROWHEAD_PX = 60f
        const val DASH_LENGTH = 4f
        const val DASH_GAP = 3f
        const val DOT_GAP = 2.5f
        const val HATCH_SPACING_PX = 12f
        const val HATCH_WIDTH_PX = 2f
        const val MISSING_IMAGE_FILL = 0xFFE6E6E6.toInt()
    }
}
