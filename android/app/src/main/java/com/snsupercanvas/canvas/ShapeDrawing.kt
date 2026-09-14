package com.snsupercanvas.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Drawing primitives shared by the live view, the note thumbnail and the minimap.

/** [world] on screen through [transform]. */
internal fun screenPoint(
    world: Point,
    transform: ViewTransform,
): PointF = PointF(transform.screenX(world.x).toFloat(), transform.screenY(world.y).toFloat())

/** A bbox element's unrotated screen bounds through [transform]. */
internal fun screenBounds(
    element: Element,
    transform: ViewTransform,
): RectF =
    RectF(
        transform.screenX(element.x).toFloat(),
        transform.screenY(element.y).toFloat(),
        transform.screenX(element.x + element.width).toFloat(),
        transform.screenY(element.y + element.height).toFloat(),
    )

/** A path through [points], already in pixels, closed back to the first when [closed]. */
internal fun polylinePath(
    points: List<Point>,
    closed: Boolean = false,
): Path =
    Path().apply {
        points.forEachIndexed { index, point ->
            if (index == 0) moveTo(point.x.toFloat(), point.y.toFloat()) else lineTo(point.x.toFloat(), point.y.toFloat())
        }
        if (closed) close()
    }

// The hand-drawn wobble's reach, per unit of stroke width (world units).
private const val WOBBLE_PER_STROKE = 0.6

/** World [points] in [element]'s hand-drawn wobble (the draw dash), seeded by its id so it wobbles alike on every redraw. */
internal fun wobbly(
    points: List<Point>,
    closed: Boolean,
    element: Element,
): List<Point> = ShapeOutline.handDrawn(points, closed, element.id.hashCode(), element.style.size.strokeWidth * WOBBLE_PER_STROKE)

/** A path through world [points], on screen through [transform], closed back to the first when [closed]. */
internal fun screenPath(
    points: List<Point>,
    transform: ViewTransform,
    closed: Boolean = false,
): Path = polylinePath(points.map { Point(transform.screenX(it.x), transform.screenY(it.y)) }, closed)

/** A bbox element's outline in its screen [bounds]: hand-drawn for the draw dash, otherwise its oval or rectangle. */
internal fun shapePath(
    element: Element,
    bounds: RectF,
    transform: ViewTransform,
): Path =
    when {
        element.style.dash == DashStyle.DRAW ->
            screenPath(
                wobbly(ShapeOutline.of(element), closed = true, element),
                transform,
                closed = true,
            )
        element.type == CanvasTools.ELLIPSE -> Path().apply { addOval(bounds, Path.Direction.CW) }
        else -> Path().apply { addRect(bounds, Path.Direction.CW) }
    }

/**
 * Runs [draw] with [canvas] rotated by [element]'s rotation about [bounds]'
 * center. Every world->screen transform in this plugin is a uniform scale +
 * translate, so the screen center is the world rotation pivot.
 */
internal inline fun withRotation(
    canvas: Canvas,
    element: Element,
    bounds: RectF,
    draw: () -> Unit,
) {
    if (element.rotation == 0.0) {
        draw()
        return
    }
    canvas.save()
    canvas.rotate(Math.toDegrees(element.rotation).toFloat(), bounds.centerX(), bounds.centerY())
    draw()
    canvas.restore()
}

/** [element]'s rect or oval in screen-space [bounds], rotated: the minimap's outline. */
internal fun drawRotatedBox(
    canvas: Canvas,
    element: Element,
    bounds: RectF,
    paint: Paint,
) = withRotation(canvas, element, bounds) {
    if (element.type == CanvasTools.ELLIPSE) canvas.drawOval(bounds, paint) else canvas.drawRect(bounds, paint)
}

private const val ARROWHEAD_SPREAD_RAD = Math.PI / 7

/** An open arrowhead of [length] pixels at [end] of the screen-space segment from [start]. */
internal fun drawArrowhead(
    canvas: Canvas,
    start: PointF,
    end: PointF,
    paint: Paint,
    length: Float,
) {
    val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    for (side in listOf(angle - ARROWHEAD_SPREAD_RAD, angle + ARROWHEAD_SPREAD_RAD)) {
        val x = end.x - (length * cos(side)).toFloat()
        val y = end.y - (length * sin(side)).toFloat()
        canvas.drawLine(end.x, end.y, x, y, paint)
    }
}

private val rotateGlyphFillPaint =
    Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        isAntiAlias = true
    }

private val rotateGlyphStrokePaint =
    Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
        isAntiAlias = true
    }

private val rotateGlyphArrowPaint =
    Paint().apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        isAntiAlias = true
    }

// Reused across frames (only ever drawn from onDraw, on the UI thread) to avoid per-frame allocation.
private val rotateGlyphArcBounds = RectF()
private val rotateGlyphArrowPath = Path()

// Android arc angles: 0° = 3 o'clock, increasing clockwise. The gap sits at the top.
private const val ROTATE_GLYPH_ARC_START_DEG = -60f
private const val ROTATE_GLYPH_ARC_SWEEP_DEG = 270f

/**
 * The rotate handle: a white disc with a black ring and a clockwise circular
 * arrow inside, so it reads as "rotate" rather than a plain dot. [radius] is
 * the disc's screen-space radius; the arrow scales with it.
 */
internal fun drawRotateHandleGlyph(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    radius: Float,
) {
    canvas.drawCircle(cx, cy, radius, rotateGlyphFillPaint)
    canvas.drawCircle(cx, cy, radius, rotateGlyphStrokePaint)

    val arcRadius = radius * 0.52f
    rotateGlyphArcBounds.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
    canvas.drawArc(rotateGlyphArcBounds, ROTATE_GLYPH_ARC_START_DEG, ROTATE_GLYPH_ARC_SWEEP_DEG, false, rotateGlyphStrokePaint)

    // Arrowhead at the arc's end, pointing along the clockwise tangent (-sin, cos) into the gap.
    val endRad = Math.toRadians((ROTATE_GLYPH_ARC_START_DEG + ROTATE_GLYPH_ARC_SWEEP_DEG).toDouble())
    val radialX = cos(endRad).toFloat()
    val radialY = sin(endRad).toFloat()
    val tangentX = -radialY
    val tangentY = radialX
    val endX = cx + arcRadius * radialX
    val endY = cy + arcRadius * radialY
    // Longer than it is wide, so it reads as an arrowhead at ~22px rather than a blob.
    val headLength = radius * 0.4f
    val headHalfWidth = radius * 0.24f
    rotateGlyphArrowPath.reset()
    rotateGlyphArrowPath.moveTo(endX + tangentX * headLength, endY + tangentY * headLength)
    rotateGlyphArrowPath.lineTo(endX + radialX * headHalfWidth, endY + radialY * headHalfWidth)
    rotateGlyphArrowPath.lineTo(endX - radialX * headHalfWidth, endY - radialY * headHalfWidth)
    rotateGlyphArrowPath.close()
    canvas.drawPath(rotateGlyphArrowPath, rotateGlyphArrowPaint)
}
