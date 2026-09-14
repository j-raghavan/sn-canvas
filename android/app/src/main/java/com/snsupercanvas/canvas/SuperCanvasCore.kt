package com.snsupercanvas.canvas

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * Pure editing and geometry operations on the canvas model (CanvasModel.kt):
 * hit-testing, pan/zoom, insert/move/resize/rotate/delete, handles and connector
 * resolution. Every function takes a [CanvasState]/[Element] and returns a new
 * one, so [SuperCanvasView] owns only rendering and gestures (PRD NFR5).
 */
@Suppress("TooManyFunctions") // one pure-math home for the whole element/gesture model, per file doc above
object SuperCanvasCore {
    /** Minimum zoom to prevent the canvas from collapsing to a point. */
    const val MIN_ZOOM = 0.05

    /** Maximum zoom to prevent runaway magnification. */
    const val MAX_ZOOM = 20.0

    /** World-space tolerance for hit-testing a tap against a line/arrow's stroke, not just its endpoints. */
    private const val LINE_HIT_TOLERANCE_WORLD = 20.0

    private fun distance(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
    ): Double = hypot(x2 - x1, y2 - y1)

    private fun elementCenter(element: Element): Point = Point(element.x + element.width / 2, element.y + element.height / 2)

    /**
     * Projects [shape]'s center outward to where it crosses the shape's bbox
     * boundary, along the ray toward [towards]. This is what makes a bound
     * connector endpoint land on the edge of the shape it's pointing at
     * (fanning out based on approach direction) instead of every arrow bound
     * to the same shape converging on its exact center — standard
     * ray-vs-axis-aligned-box intersection from the center outward.
     */
    private fun projectToBoundary(
        shape: Element,
        towards: Point,
    ): Point {
        // Work in the shape's unrotated frame (its bbox is axis-aligned there), then rotate back.
        // The center is the rotation pivot, so it's the same point in both frames.
        val center = elementCenter(shape)
        val local = shape.toLocal(towards.x, towards.y)
        val halfWidth = shape.width / 2.0
        val halfHeight = shape.height / 2.0
        val dx = local.x - center.x
        val dy = local.y - center.y
        if (dx == 0.0 && dy == 0.0) return center
        val scaleX = if (dx != 0.0) halfWidth / abs(dx) else Double.MAX_VALUE
        val scaleY = if (dy != 0.0) halfHeight / abs(dy) else Double.MAX_VALUE
        val scale = minOf(scaleX, scaleY)
        return shape.toWorld(center.x + dx * scale, center.y + dy * scale)
    }

    /** The distance from [p] to the segment from [a] to [b]. */
    internal fun distanceToSegment(
        point: Point,
        segmentStart: Point,
        segmentEnd: Point,
    ): Double {
        val dx = segmentEnd.x - segmentStart.x
        val dy = segmentEnd.y - segmentStart.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return distance(point.x, point.y, segmentStart.x, segmentStart.y)
        val t =
            (((point.x - segmentStart.x) * dx + (point.y - segmentStart.y) * dy) / lengthSquared)
                .coerceIn(0.0, 1.0)
        return distance(point.x, point.y, segmentStart.x + t * dx, segmentStart.y + t * dy)
    }

    /**
     * Resolves a line/arrow [element]'s actual world-space endpoints right now:
     * a bound endpoint ([Element.startElementId]/[Element.endElementId] set)
     * resolves to that element's current center looked up in [allElements];
     * an unbound endpoint uses the element's own stored coordinate. Call this
     * every time you need to draw or hit-test a line/arrow — never read
     * startX/Y or endX/Y directly for a bound endpoint, they're stale by
     * design (FR7's "re-routes if either shape moves").
     */
    fun resolveArrowEndpoints(
        element: Element,
        allElements: List<Element>,
    ): Pair<Point, Point> {
        val startShape = element.startElementId?.let { id -> allElements.find { it.id == id } }
        val endShape = element.endElementId?.let { id -> allElements.find { it.id == id } }

        // First pass: where each end would land ignoring boundary projection —
        // the other end's naive position is what a bound end aims *toward*.
        // Plain if/else rather than `shape?.let(f) ?: fallback`: f never returns null, so the
        // elvis would compile to a "let returned null" branch no input can reach.
        val naiveStart = if (startShape != null) elementCenter(startShape) else Point(element.startX ?: 0.0, element.startY ?: 0.0)
        val naiveEnd = if (endShape != null) elementCenter(endShape) else Point(element.endX ?: 0.0, element.endY ?: 0.0)

        val start = if (startShape != null) projectToBoundary(startShape, towards = naiveEnd) else naiveStart
        val end = if (endShape != null) projectToBoundary(endShape, towards = naiveStart) else naiveEnd
        return start to end
    }

    /** The four bbox corners of a rectangle/ellipse [element] in world space (rotated with it), in [Corner] enum order. */
    fun cornerPoints(element: Element): List<Point> =
        listOf(
            element.toWorld(element.x, element.y),
            element.toWorld(element.x + element.width, element.y),
            element.toWorld(element.x, element.y + element.height),
            element.toWorld(element.x + element.width, element.y + element.height),
        )

    private val CORNER_ORDER = listOf(Corner.TOP_LEFT, Corner.TOP_RIGHT, Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT)

    /** Screen-space gap between a shape's top edge and its rotate handle; divided by zoom to get world units. */
    const val ROTATE_HANDLE_OFFSET_PX = 56.0

    /** Where a bbox [element]'s rotate handle sits: [ROTATE_HANDLE_OFFSET_PX] screen px above its top-center, rotated with it. */
    fun rotateHandlePoint(
        element: Element,
        zoom: Double,
    ): Point = element.toWorld(element.x + element.width / 2, element.y - ROTATE_HANDLE_OFFSET_PX / zoom)

    /**
     * Hit-tests a world-space point against the selected element's resize/endpoint
     * handles (FR9). [toleranceWorld] is the tap radius already converted from a
     * fixed screen-space pixel budget to world-space units by the caller
     * (screenPx / zoom), so handles stay equally tappable at any zoom level
     * without this function needing to know about pixels or zoom itself.
     */
    fun handleAt(
        state: CanvasState,
        elementId: String,
        worldX: Double,
        worldY: Double,
        toleranceWorld: Double,
    ): HandleTarget? {
        val element = state.elements.find { it.id == elementId } ?: return null
        return if (element.hasEndpoints()) {
            val (start, end) = resolveArrowEndpoints(element, state.elements)
            when {
                distance(worldX, worldY, start.x, start.y) <= toleranceWorld -> HandleTarget.EndpointHandle(Endpoint.START)
                distance(worldX, worldY, end.x, end.y) <= toleranceWorld -> HandleTarget.EndpointHandle(Endpoint.END)
                else -> null
            }
        } else {
            val rotateHandle = rotateHandlePoint(element, state.zoom)
            if (distance(worldX, worldY, rotateHandle.x, rotateHandle.y) <= toleranceWorld) {
                HandleTarget.RotateHandle
            } else {
                CORNER_ORDER
                    .zip(cornerPoints(element))
                    .firstOrNull { (_, point) -> distance(worldX, worldY, point.x, point.y) <= toleranceWorld }
                    ?.let { (corner, _) -> HandleTarget.CornerHandle(corner) }
            }
        }
    }

    /**
     * Returns the topmost element (last in list, matching typical z-order-by-insertion)
     * under the given world-space point: bbox containment for bbox elements,
     * near-the-line distance for lines, arrows and freehand strokes (a thin or
     * zero-width shape can't use bbox containment). Returns null if none is.
     */
    fun hitTest(
        worldX: Double,
        worldY: Double,
        elements: List<Element>,
    ): Element? = elements.lastOrNull { isHit(it, worldX, worldY, elements) }

    /** Every element under the world point, bottom to top: what the eraser takes (FR20). */
    fun hitsAt(
        worldX: Double,
        worldY: Double,
        elements: List<Element>,
    ): List<Element> = elements.filter { isHit(it, worldX, worldY, elements) }

    private fun isHit(
        element: Element,
        worldX: Double,
        worldY: Double,
        elements: List<Element>,
    ): Boolean =
        when {
            element.hasEndpoints() -> {
                val (start, end) = resolveArrowEndpoints(element, elements)
                distanceToSegment(Point(worldX, worldY), start, end) <= LINE_HIT_TOLERANCE_WORLD
            }
            element.points != null -> StrokeElements.isNear(element, worldX, worldY, LINE_HIT_TOLERANCE_WORLD)
            else -> element.containsPoint(worldX, worldY)
        }

    /**
     * Pans the viewport by a screen-space delta already converted to world-space
     * units by the caller (division by current zoom happens at the call site in
     * [SuperCanvasView], since that's where the screen<->world scale factor is
     * known from the live gesture).
     */
    fun panBy(
        state: CanvasState,
        dx: Double,
        dy: Double,
    ): CanvasState = state.copy(viewportX = state.viewportX - dx, viewportY = state.viewportY - dy)

    /**
     * Zooms around a focal point (screen-space anchor that should stay visually
     * fixed during the gesture, already converted to world-space by the caller)
     * by a multiplicative [scaleFactor]. Clamped to [MIN_ZOOM, MAX_ZOOM].
     */
    fun zoomTo(
        state: CanvasState,
        scaleFactor: Double,
        focalWorldX: Double,
        focalWorldY: Double,
    ): CanvasState {
        require(scaleFactor > 0) { "scaleFactor must be > 0" }
        val newZoom = (state.zoom * scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val appliedFactor = newZoom / state.zoom
        // Keep the focal point stationary: adjust viewport origin by the same
        // proportion the focal point moved away from it under the new zoom.
        val newViewportX = focalWorldX - (focalWorldX - state.viewportX) / appliedFactor
        val newViewportY = focalWorldY - (focalWorldY - state.viewportY) / appliedFactor
        return state.copy(viewportX = newViewportX, viewportY = newViewportY, zoom = newZoom)
    }

    /** Appends [element] to the canvas (FR4: shape insertion). */
    fun insertElement(
        state: CanvasState,
        element: Element,
    ): CanvasState = state.copy(elements = state.elements + element)

    /** A new rectangle/ellipse spanning a drag from [from] to [to], in whichever direction it went (FR4). */
    fun boxFromDrag(
        id: String,
        type: String,
        from: Point,
        to: Point,
    ): Element =
        Element(
            id = id,
            type = type,
            x = minOf(from.x, to.x),
            y = minOf(from.y, to.y),
            width = maxOf(from.x, to.x) - minOf(from.x, to.x),
            height = maxOf(from.y, to.y) - minOf(from.y, to.y),
        )

    /** A new line/arrow from [from] to [to], each end bound to the shape it was drawn from/to, if any (FR7). */
    fun connectorFromDrag(
        id: String,
        type: String,
        from: Point,
        to: Point,
        elements: List<Element>,
    ): Element =
        Element(
            id = id,
            type = type,
            startX = from.x,
            startY = from.y,
            endX = to.x,
            endY = to.y,
            startElementId = bindingTargetAt(from, elements)?.id,
            endElementId = bindingTargetAt(to, elements)?.id,
        )

    /** The shape a connector end at [point] binds to (FR7): the topmost rectangle/ellipse there, never another line/arrow. */
    fun bindingTargetAt(
        point: Point,
        elements: List<Element>,
    ): Element? = hitTest(point.x, point.y, elements.filterNot { it.hasEndpoints() })

    /**
     * Removes the element with the given [id], if present. A no-op (returns
     * [state] unchanged) if no element has that id — deleting something that
     * no longer exists (e.g. a stale/racy command) must never throw (FR10).
     *
     * Also unbinds (FR7) any other line/arrow that was anchored to the
     * deleted element, freezing that endpoint at the deleted element's last
     * known center rather than leaving a dangling id or snapping to (0,0).
     */
    fun deleteElement(
        state: CanvasState,
        id: String,
    ): CanvasState {
        val deleted = state.elements.find { it.id == id } ?: return state
        val remaining = state.elements.filterNot { it.id == id }.map { unbindIfPointingTo(it, deleted) }
        return state.copy(elements = remaining)
    }

    private fun unbindIfPointingTo(
        element: Element,
        deleted: Element,
    ): Element {
        if (!element.hasEndpoints()) return element
        var result = element
        if (result.startElementId == deleted.id) {
            val center = elementCenter(deleted)
            result = result.copy(startElementId = null, startX = center.x, startY = center.y)
        }
        if (result.endElementId == deleted.id) {
            val center = elementCenter(deleted)
            result = result.copy(endElementId = null, endX = center.x, endY = center.y)
        }
        return result
    }

    /**
     * Translates the element with the given [id] by ([dx], [dy]) world-space
     * units. A no-op if no element has that id, for the same reason as
     * [deleteElement] (FR9/FR10). Only meaningful for bbox elements (rectangle/
     * ellipse) — [SuperCanvasView] never routes a whole-element move for a
     * line/arrow (see its class doc); moving one is done by dragging an
     * endpoint handle via [moveEndpoint] instead.
     */
    fun moveElement(
        state: CanvasState,
        id: String,
        dx: Double,
        dy: Double,
    ): CanvasState =
        state.copy(
            elements =
                state.elements.map { element ->
                    if (element.id == id) element.copy(x = element.x + dx, y = element.y + dy) else element
                },
        )

    /**
     * Resizes the bbox element with the given [id] by dragging [corner] to a new
     * world-space position, keeping the opposite corner fixed; an image keeps
     * its proportions too ([ImageElements.resize]). A no-op if no element has that id.
     */
    fun resizeElement(
        state: CanvasState,
        id: String,
        corner: Corner,
        newWorldX: Double,
        newWorldY: Double,
    ): CanvasState =
        state.copy(
            elements =
                state.elements.map { element ->
                    when {
                        element.id != id -> element
                        element.image != null -> ImageElements.resize(element, element.image, corner, Point(newWorldX, newWorldY))
                        else -> resizeCorner(element, corner, newWorldX, newWorldY)
                    }
                },
        )

    private fun resizeCorner(
        element: Element,
        corner: Corner,
        newX: Double,
        newY: Double,
    ): Element {
        if (element.rotation != 0.0) return resizeRotatedCorner(element, corner, Point(newX, newY))
        val (fixedX, fixedY) =
            when (corner) {
                Corner.TOP_LEFT -> (element.x + element.width) to (element.y + element.height)
                Corner.TOP_RIGHT -> element.x to (element.y + element.height)
                Corner.BOTTOM_LEFT -> (element.x + element.width) to element.y
                Corner.BOTTOM_RIGHT -> element.x to element.y
            }
        val left = minOf(fixedX, newX)
        val top = minOf(fixedY, newY)
        val right = maxOf(fixedX, newX)
        val bottom = maxOf(fixedY, newY)
        return element.copy(x = left, y = top, width = right - left, height = bottom - top)
    }

    /**
     * Resize for a rotated shape: the opposite corner stays fixed *in world
     * space*. The fixed-corner-to-pointer diagonal, expressed in the shape's own
     * rotated frame, gives the new width/height; its midpoint is the new center.
     */
    private fun resizeRotatedCorner(
        element: Element,
        corner: Corner,
        pointer: Point,
    ): Element {
        val opposite =
            when (corner) {
                Corner.TOP_LEFT -> Corner.BOTTOM_RIGHT
                Corner.TOP_RIGHT -> Corner.BOTTOM_LEFT
                Corner.BOTTOM_LEFT -> Corner.TOP_RIGHT
                Corner.BOTTOM_RIGHT -> Corner.TOP_LEFT
            }
        val fixed = cornerPoints(element)[CORNER_ORDER.indexOf(opposite)]
        val dx = pointer.x - fixed.x
        val dy = pointer.y - fixed.y
        val c = cos(-element.rotation)
        val s = sin(-element.rotation)
        val newWidth = abs(dx * c - dy * s)
        val newHeight = abs(dx * s + dy * c)
        val centerX = (fixed.x + pointer.x) / 2
        val centerY = (fixed.y + pointer.y) / 2
        return element.copy(x = centerX - newWidth / 2, y = centerY - newHeight / 2, width = newWidth, height = newHeight)
    }

    /** Rotation snaps to the nearest multiple of [ROTATION_SNAP_STEP_RAD] when within this (5°) of it. */
    private const val ROTATION_SNAP_RAD = PI / 36
    private const val ROTATION_SNAP_STEP_RAD = PI / 4

    /**
     * Rotates the bbox element with the given [id] so its rotate handle (straight
     * above the top-center at rotation 0) points at [pointer]: the angle from the
     * element's center to [pointer], plus 90°. Snapped via [snapRotation]. A no-op
     * for a missing id or a line/arrow (those already point any direction).
     */
    fun rotateElement(
        state: CanvasState,
        id: String,
        pointer: Point,
    ): CanvasState =
        state.copy(
            elements =
                state.elements.map { element ->
                    if (element.id != id || element.hasEndpoints()) {
                        element
                    } else {
                        val center = elementCenter(element)
                        val raw = atan2(pointer.y - center.y, pointer.x - center.x) + PI / 2
                        element.copy(rotation = snapRotation(raw))
                    }
                },
        )

    /**
     * Normalizes [angle] into (-π, π] and snaps it to the nearest 45° multiple
     * when within [ROTATION_SNAP_RAD], so squaring a shape back up is easy with a pen.
     */
    fun snapRotation(angle: Double): Double {
        val normalized = atan2(sin(angle), cos(angle))
        val nearest = round(normalized / ROTATION_SNAP_STEP_RAD) * ROTATION_SNAP_STEP_RAD
        val snapped = if (abs(normalized - nearest) <= ROTATION_SNAP_RAD) nearest else normalized
        return atan2(sin(snapped), cos(snapped))
    }

    /**
     * Moves one endpoint of a line/arrow element with the given [id] to a new
     * world-space position, and sets or clears its connector binding (FR7):
     * pass [targetElementId] to bind that endpoint to another element (e.g.
     * because the drag landed inside a shape), or null to make it a fixed,
     * unbound point. A no-op if no element has that id.
     */
    fun moveEndpoint(
        state: CanvasState,
        id: String,
        which: Endpoint,
        newPosition: Point,
        targetElementId: String?,
    ): CanvasState =
        state.copy(
            elements =
                state.elements.map { element ->
                    if (element.id != id) {
                        element
                    } else {
                        when (which) {
                            Endpoint.START ->
                                element.copy(startX = newPosition.x, startY = newPosition.y, startElementId = targetElementId)
                            Endpoint.END ->
                                element.copy(endX = newPosition.x, endY = newPosition.y, endElementId = targetElementId)
                        }
                    }
                },
        )
}
