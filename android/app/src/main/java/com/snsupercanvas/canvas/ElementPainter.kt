package com.snsupercanvas.canvas

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Paints one element in its style (FR19): colour through a [StylePalette],
 * opacity, fill (none, semi, solid or hatched pattern), dash (hand-drawn,
 * dashed, dotted or solid) and size. [CanvasRenderer] decides what to draw;
 * this decides how an element looks. Its paints are reconfigured for every
 * element, so a painter stays on one thread: the note thumbnail renders with
 * a renderer, and painter, of its own.
 */
internal class ElementPainter {
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

    fun draw(
        canvas: Canvas,
        element: Element,
        elements: List<Element>,
        transform: ViewTransform,
        palette: StylePalette,
    ) {
        if (element.hasEndpoints()) {
            drawConnector(canvas, element, elements, transform, palette)
        } else {
            drawShape(canvas, element, transform, palette)
        }
    }

    private fun drawShape(
        canvas: Canvas,
        element: Element,
        transform: ViewTransform,
        palette: StylePalette,
    ) {
        val bounds = screenBounds(element, transform)
        val outline = shapePath(element, bounds, transform)
        withRotation(canvas, element, bounds) {
            drawFill(canvas, outline, bounds, element.style, palette)
            configureStroke(element.style, palette, element.style.strokeWidthPx(transform.zoom))
            canvas.drawPath(outline, strokePaint)
        }
    }

    private fun shapePath(
        element: Element,
        bounds: RectF,
        transform: ViewTransform,
    ): Path {
        val path = Path()
        when {
            element.style.dash == DashStyle.DRAW -> {
                addPolyline(path, wobbly(ShapeOutline.of(element), closed = true, element), transform)
                path.close()
            }
            element.type == CanvasTools.ELLIPSE -> path.addOval(bounds, Path.Direction.CW)
            else -> path.addRect(bounds, Path.Direction.CW)
        }
        return path
    }

    private fun drawConnector(
        canvas: Canvas,
        element: Element,
        elements: List<Element>,
        transform: ViewTransform,
        palette: StylePalette,
    ) {
        val (start, end) = SuperCanvasCore.resolveArrowEndpoints(element, elements)
        val style = element.style
        val width = style.strokeWidthPx(transform.zoom)
        val points = if (style.dash == DashStyle.DRAW) wobbly(listOf(start, end), closed = false, element) else listOf(start, end)
        val path = Path()
        addPolyline(path, points, transform)
        configureStroke(style, palette, width)
        canvas.drawPath(path, strokePaint)
        if (element.type == CanvasTools.ARROW) {
            // The head is always solid, even on a dashed or dotted arrow.
            arrowheadPaint.set(strokePaint)
            arrowheadPaint.pathEffect = null
            val length = (width * ARROWHEAD_PER_STROKE).coerceIn(MIN_ARROWHEAD_PX, MAX_ARROWHEAD_PX)
            drawArrowhead(canvas, screenPoint(start, transform), screenPoint(points.last(), transform), arrowheadPaint, length)
        }
    }

    private fun drawFill(
        canvas: Canvas,
        outline: Path,
        bounds: RectF,
        style: ShapeStyle,
        palette: StylePalette,
    ) {
        when (style.fill) {
            FillStyle.NONE -> Unit
            FillStyle.SEMI -> fill(canvas, outline, palette.semiFill(style.color), style.alpha)
            FillStyle.SOLID -> fill(canvas, outline, palette.solidFill(style.color), style.alpha)
            FillStyle.PATTERN -> {
                fill(canvas, outline, palette.semiFill(style.color), style.alpha)
                hatch(canvas, outline, bounds, palette.patternLine(style.color), style.alpha)
            }
        }
    }

    private fun fill(
        canvas: Canvas,
        path: Path,
        color: Int,
        alpha: Int,
    ) {
        fillPaint.color = color
        fillPaint.alpha = alpha
        canvas.drawPath(path, fillPaint)
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
                DashStyle.DRAW, DashStyle.SOLID -> null
            }
    }

    private fun addPolyline(
        path: Path,
        points: List<Point>,
        transform: ViewTransform,
    ) {
        points.forEachIndexed { index, point ->
            val x = transform.screenX(point.x).toFloat()
            val y = transform.screenY(point.y).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    private fun wobbly(
        points: List<Point>,
        closed: Boolean,
        element: Element,
    ): List<Point> = ShapeOutline.handDrawn(points, closed, element.id.hashCode(), element.style.size.strokeWidth * WOBBLE_PER_STROKE)

    private companion object {
        // The hand-drawn wobble's reach, per unit of stroke width (world units).
        const val WOBBLE_PER_STROKE = 0.6
        const val ARROWHEAD_PER_STROKE = 8f
        const val MIN_ARROWHEAD_PX = 10f
        const val MAX_ARROWHEAD_PX = 60f
        const val DASH_LENGTH = 4f
        const val DASH_GAP = 3f
        const val DOT_GAP = 2.5f
        const val HATCH_SPACING_PX = 12f
        const val HATCH_WIDTH_PX = 2f
    }
}
