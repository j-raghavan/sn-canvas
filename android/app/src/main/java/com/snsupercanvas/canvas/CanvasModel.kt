package com.snsupercanvas.canvas

import kotlin.math.cos
import kotlin.math.sin

/*
 * SuperCanvas's domain model: world-space value types (PRD §8). Pure Kotlin, no
 * Android types, so the model, SuperCanvasCore's operations on it, the fit
 * transforms in ViewTransforms and CanvasJson's persistence are all plain-JUnit
 * testable.
 *
 * An Element is either a bbox shape (rectangle/ellipse: x/y/width/height, with an
 * optional rotation about its center) or an endpoint shape (line/arrow). A
 * line/arrow endpoint may be bound to another element by id (FR7); bound
 * positions are resolved live by SuperCanvasCore.resolveArrowEndpoints and never
 * stored, which is what makes connectors re-route for free when a shape moves.
 */

/** A world-space point. */
data class Point(
    val x: Double,
    val y: Double,
)

/** Which corner of a bbox element (rectangle/ellipse) a resize handle is anchored to (FR9). */
enum class Corner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

/** Which end of a line/arrow element a handle drags (FR7/FR9). */
enum class Endpoint {
    START,
    END,
}

/**
 * A world->screen transform (`screen = (world - viewport) * zoom`), the shape
 * [SuperCanvasView]'s draw routines consume. Produced for the live view, the
 * note thumbnail ([ViewTransforms.computeThumbnailTransform]) and the minimap
 * ([ViewTransforms.computeMinimapTransform]).
 */
data class ViewTransform(
    val viewportX: Double,
    val viewportY: Double,
    val zoom: Double,
) {
    fun screenX(worldX: Double): Double = (worldX - viewportX) * zoom

    fun screenY(worldY: Double): Double = (worldY - viewportY) * zoom
}

/**
 * The toolbar's tools, as the ids the JS toolbar sends through the `toolMode`
 * prop. A drawing tool's id doubles as the [Element.type] of what it draws.
 */
object CanvasTools {
    const val SELECT = "select"
    const val RECTANGLE = "rectangle"
    const val ELLIPSE = "ellipse"
    const val LINE = "line"
    const val ARROW = "arrow"

    /** True for the endpoint-defined tools, whose drag draws a line/arrow. */
    fun isConnector(tool: String): Boolean = tool == LINE || tool == ARROW

    /** True for every tool whose drag draws a new element; false for select and for unknown ids. */
    fun drawsElement(tool: String): Boolean = tool == RECTANGLE || tool == ELLIPSE || isConnector(tool)
}

/** An axis-aligned world-space rectangle — content bounds, or the currently visible viewport (minimap). */
data class WorldRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    fun union(other: WorldRect): WorldRect =
        WorldRect(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
}

/** What a [SuperCanvasCore.handleAt] hit-test found under a touch point. */
sealed class HandleTarget {
    data class CornerHandle(
        val corner: Corner,
    ) : HandleTarget()

    data class EndpointHandle(
        val which: Endpoint,
    ) : HandleTarget()

    /** The round handle above a selected rectangle/ellipse that rotates it. */
    object RotateHandle : HandleTarget()
}

data class Element(
    val id: String,
    val type: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 0.0,
    val height: Double = 0.0,
    // Line/arrow geometry (null for rectangle/ellipse, which use x/y/width/height above).
    val startX: Double? = null,
    val startY: Double? = null,
    val endX: Double? = null,
    val endY: Double? = null,
    // Connector binding (FR7): non-null means "resolve this endpoint from the
    // named element's current position", not the stored startX/Y or endX/Y.
    val startElementId: String? = null,
    val endElementId: String? = null,
    // Radians about the bbox center, clockwise on screen (y-down). Rectangle/ellipse
    // only — lines/arrows already point any direction via their endpoints. x/y/width/
    // height always describe the *unrotated* box; see [toLocal]/[toWorld].
    val rotation: Double = 0.0,
    // FR19. The default is the look elements had before styles existed.
    val style: ShapeStyle = ShapeStyle.LEGACY,
) {
    init {
        require(rotation.isFinite()) { "rotation must be finite" }
        require(width >= 0) { "width must be >= 0" }
        require(height >= 0) { "height must be >= 0" }
        val endpointFields = listOf(startX, startY, endX, endY)
        require(endpointFields.all { it == null } || endpointFields.none { it == null }) {
            "startX/startY/endX/endY must be all null (bbox element) or all non-null (line/arrow element)"
        }
    }

    /** True for line/arrow elements (endpoint-defined); false for rectangle/ellipse (bbox-defined). */
    fun hasEndpoints(): Boolean = startX != null

    fun containsPoint(
        worldX: Double,
        worldY: Double,
    ): Boolean {
        val local = toLocal(worldX, worldY)
        return local.x >= x && local.x <= x + width && local.y >= y && local.y <= y + height
    }

    /** A world point rotated by -[rotation] about the bbox center: into the unrotated frame x/y/width/height describe. */
    fun toLocal(
        worldX: Double,
        worldY: Double,
    ): Point = rotateAboutCenter(worldX, worldY, -rotation)

    /** The inverse of [toLocal]: an unrotated-frame point rotated by +[rotation] about the bbox center, into world space. */
    fun toWorld(
        localX: Double,
        localY: Double,
    ): Point = rotateAboutCenter(localX, localY, rotation)

    private fun rotateAboutCenter(
        px: Double,
        py: Double,
        angle: Double,
    ): Point {
        if (angle == 0.0) return Point(px, py)
        val cx = x + width / 2
        val cy = y + height / 2
        val c = cos(angle)
        val s = sin(angle)
        val dx = px - cx
        val dy = py - cy
        return Point(cx + dx * c - dy * s, cy + dx * s + dy * c)
    }
}

/** Canvas viewport + element list (PRD §8's `Canvas` shape, minus persistence metadata). */
data class CanvasState(
    val elements: List<Element>,
    val viewportX: Double,
    val viewportY: Double,
    val zoom: Double,
) {
    init {
        require(zoom > 0) { "zoom must be > 0" }
    }

    /** This state's pan/zoom as the [ViewTransform] the live view draws through. */
    val transform: ViewTransform get() = ViewTransform(viewportX, viewportY, zoom)
}
