package com.snsupercanvas.canvas

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * Pure canvas math — element model, hit-testing, and pan/zoom transforms
 * (spec/SuperCanvas-PRD.md §8-9). Deliberately framework-free (no Android
 * types) so it is plain-JUnit testable without an Android runtime.
 * [SuperCanvasView] is the only caller and owns nothing but rendering and
 * the RN bridge plumbing (Single Responsibility), mirroring the
 * TableGrid.kt split from the sn-tables plugin this scaffold was copied
 * from.
 *
 * Scaffold scope (v0 spike): a straightforward element list, bbox hit-test,
 * and pan/zoom-around-a-point. Tile/spatial-index partitioning is
 * explicitly deferred per PRD NFR6 (low hundreds of elements, not
 * thousands) until real usage proves it necessary.
 *
 * [Element] is PRD §8's world-space (not screen-space) element shape.
 *
 * v1b adds line/arrow elements (endpoint-defined, not bbox-defined) and
 * connector binding (FR7): a line/arrow's endpoint can either be a fixed
 * world-space point ([Element.startX]/[Element.startY] etc.) or bound to
 * another element by id ([Element.startElementId]/[Element.endElementId]).
 * Binding is resolved to a *live* position at draw/hit-test time via
 * [resolveArrowEndpoints] rather than stored as a coordinate snapshot —
 * that's what makes "re-routes if either shape moves" (FR7) free: moving
 * the bound shape needs no extra code, because nothing about a bound
 * arrow's own record ever changes when the shape it points to moves.
 */
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
 * A (viewportX, viewportY, zoom) transform in the same shape [SuperCanvasView]'s
 * draw routines already consume — see [SuperCanvasCore.computeThumbnailTransform]
 * (v1d, FR12 thumbnail rendering).
 */
data class ThumbnailTransform(
    val viewportX: Double,
    val viewportY: Double,
    val zoom: Double,
)

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
}

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

    private fun distanceToSegment(
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
     * containing the given world-space point: bbox containment for rectangle/ellipse,
     * near-the-stroke distance for line/arrow (a zero-width shape can't use bbox
     * containment). Returns null if none does.
     */
    fun hitTest(
        worldX: Double,
        worldY: Double,
        elements: List<Element>,
    ): Element? =
        elements.lastOrNull { element ->
            if (element.hasEndpoints()) {
                val (start, end) = resolveArrowEndpoints(element, elements)
                distanceToSegment(Point(worldX, worldY), start, end) <= LINE_HIT_TOLERANCE_WORLD
            } else {
                element.containsPoint(worldX, worldY)
            }
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
     * world-space position, keeping the opposite corner fixed. A no-op if no
     * element has that id.
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
                    if (element.id == id) resizeCorner(element, corner, newWorldX, newWorldY) else element
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

    /**
     * Fallback world-space span used when content has zero width/height
     * (e.g. a single point), to avoid a divide-by-zero/infinite scale.
     */
    private const val MIN_CONTENT_SPAN = 100.0

    /**
     * The (viewportX, viewportY, zoom) transform that fits [elements]' combined
     * bounding box into a [thumbnailSizePx] square, centered, with [paddingPx]
     * breathing room on every side — in exactly the shape [SuperCanvasView]'s
     * existing draw routines already expect (`screenX = (worldX - viewportX) *
     * zoom`), so rendering a thumbnail is "call the same draw functions with a
     * different transform," not a second renderer (v1d, FR12).
     *
     * An empty [elements] list returns an arbitrary identity-ish transform —
     * nothing will be drawn, so its exact values don't matter; it exists so a
     * "Save to Note" on a blank canvas still produces a valid (blank)
     * thumbnail rather than failing.
     */
    fun computeThumbnailTransform(
        elements: List<Element>,
        thumbnailSizePx: Double,
        paddingPx: Double,
    ): ThumbnailTransform {
        val bounds = contentBounds(elements) ?: return ThumbnailTransform(viewportX = 0.0, viewportY = 0.0, zoom = 1.0)
        return fitTransform(bounds, thumbnailSizePx, thumbnailSizePx, paddingPx)
    }

    /**
     * The minimap's transform: fits the union of all content *and* the currently
     * [visible] world rect into the minimap box, so the "you are here" rectangle
     * stays inside the minimap however far the user has panned away from every
     * element. With no content it fits the visible rect alone.
     */
    fun computeMinimapTransform(
        elements: List<Element>,
        visible: WorldRect,
        boxWidthPx: Double,
        boxHeightPx: Double,
        paddingPx: Double,
    ): ThumbnailTransform {
        val world = contentBounds(elements)?.union(visible) ?: visible
        return fitTransform(world, boxWidthPx, boxHeightPx, paddingPx)
    }

    /**
     * Fits [world] into a [boxWidthPx] x [boxHeightPx] box, centered, with
     * [paddingPx] on every side, using the more constraining axis. Shared by
     * the note thumbnail (a square box) and the minimap (a box shaped like the
     * view). A zero-size [world] axis falls back to [MIN_CONTENT_SPAN].
     */
    fun fitTransform(
        world: WorldRect,
        boxWidthPx: Double,
        boxHeightPx: Double,
        paddingPx: Double,
    ): ThumbnailTransform {
        val contentWidth = world.width.let { if (it > 0.0) it else MIN_CONTENT_SPAN }
        val contentHeight = world.height.let { if (it > 0.0) it else MIN_CONTENT_SPAN }
        val availableWidth = (boxWidthPx - 2 * paddingPx).coerceAtLeast(1.0)
        val availableHeight = (boxHeightPx - 2 * paddingPx).coerceAtLeast(1.0)
        val zoom = minOf(availableWidth / contentWidth, availableHeight / contentHeight)
        val extraX = (boxWidthPx - contentWidth * zoom) / 2.0
        val extraY = (boxHeightPx - contentHeight * zoom) / 2.0
        return ThumbnailTransform(viewportX = world.left - extraX / zoom, viewportY = world.top - extraY / zoom, zoom = zoom)
    }

    /** The combined world-space bounding box of [elements] (bound line endpoints resolved live), or null if empty. */
    fun contentBounds(elements: List<Element>): WorldRect? {
        if (elements.isEmpty()) return null

        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (element in elements) {
            if (element.hasEndpoints()) {
                val (start, end) = resolveArrowEndpoints(element, elements)
                for (p in listOf(start, end)) {
                    minX = minOf(minX, p.x)
                    minY = minOf(minY, p.y)
                    maxX = maxOf(maxX, p.x)
                    maxY = maxOf(maxY, p.y)
                }
            } else {
                // Corners, not x/y/width/height: a rotated shape's extent is its rotated corners'.
                for (p in cornerPoints(element)) {
                    minX = minOf(minX, p.x)
                    minY = minOf(minY, p.y)
                    maxX = maxOf(maxX, p.x)
                    maxY = maxOf(maxY, p.y)
                }
            }
        }
        return WorldRect(minX, minY, maxX, maxY)
    }

    private const val PERSISTENCE_SCHEMA_VERSION = 1

    /**
     * Serializes [elements] to a JSON string (versioned envelope:
     * `{"version":1,"elements":[...]}`, mirroring the versioned-envelope
     * pattern already used elsewhere in this developer's plugin portfolio,
     * e.g. sn-shapes' favoritesStorage.ts) for `SuperCanvasModule.saveCanvas`.
     *
     * Deliberately hand-rolled rather than `org.json` (the obvious Android
     * platform choice): under this project's plain-JUnit `testDebugUnitTest`
     * setup (no Robolectric, no `returnDefaultValues`, no `org.json` test-jar
     * override — checked before writing this), `org.json.JSONObject` is the
     * *stubbed* platform class and throws `RuntimeException: not mocked` at
     * test time, not the real implementation. Using it here would silently
     * break this file's own stated design contract (see the class doc: pure,
     * "no Android types", testable without an Android runtime) rather than
     * fail loudly — worse than just writing the ~60 lines of JSON handling
     * this fixed, simple, fully-controlled schema actually needs.
     */
    fun serializeElements(elements: List<Element>): String {
        val sb = StringBuilder()
        sb.append("{\"version\":").append(PERSISTENCE_SCHEMA_VERSION).append(",\"elements\":[")
        elements.forEachIndexed { index, element ->
            if (index > 0) sb.append(',')
            sb.append(serializeElement(element))
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun serializeElement(element: Element): String {
        val sb = StringBuilder()
        sb.append('{')
        appendStringField(sb, "id", element.id, first = true)
        appendStringField(sb, "type", element.type)
        appendNumberField(sb, "x", element.x)
        appendNumberField(sb, "y", element.y)
        appendNumberField(sb, "width", element.width)
        appendNumberField(sb, "height", element.height)
        appendNullableNumberField(sb, "startX", element.startX)
        appendNullableNumberField(sb, "startY", element.startY)
        appendNullableNumberField(sb, "endX", element.endX)
        appendNullableNumberField(sb, "endY", element.endY)
        appendNullableStringField(sb, "startElementId", element.startElementId)
        appendNullableStringField(sb, "endElementId", element.endElementId)
        appendNumberField(sb, "rotation", element.rotation)
        sb.append('}')
        return sb.toString()
    }

    private fun appendStringField(
        sb: StringBuilder,
        key: String,
        value: String,
        first: Boolean = false,
    ) {
        if (!first) sb.append(',')
        sb
            .append('"')
            .append(key)
            .append("\":")
            .append(jsonQuote(value))
    }

    private fun appendNullableStringField(
        sb: StringBuilder,
        key: String,
        value: String?,
    ) {
        sb
            .append(',')
            .append('"')
            .append(key)
            .append("\":")
            .append(if (value == null) "null" else jsonQuote(value))
    }

    private fun appendNumberField(
        sb: StringBuilder,
        key: String,
        value: Double,
    ) {
        sb
            .append(',')
            .append('"')
            .append(key)
            .append("\":")
            .append(value)
    }

    private fun appendNullableNumberField(
        sb: StringBuilder,
        key: String,
        value: Double?,
    ) {
        sb
            .append(',')
            .append('"')
            .append(key)
            .append("\":")
            .append(value ?: "null")
    }

    private fun jsonQuote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Deserializes a JSON string produced by [serializeElements] back into an
     * element list for `SuperCanvasModule.loadCanvas`. Never throws: malformed
     * JSON, an empty string, a wrong top-level shape, or a single malformed
     * element within an otherwise-valid array all degrade to an empty list
     * (or, for one bad element among many good ones, that element is simply
     * skipped) — matching the "never throw, default on any error" pattern
     * this developer's portfolio already establishes for on-device persistence
     * (e.g. sn-shapes' favoritesStorage.ts).
     */
    @Suppress("ReturnCount", "SwallowedException") // early-return guard clauses read clearer here than nesting; see doc above
    fun deserializeElements(json: String): List<Element> {
        val root =
            try {
                JsonParser(json).parseDocument()
            } catch (e: JsonParseException) {
                return emptyList()
            }
        val obj = root as? JsonValue.Obj ?: return emptyList()
        val elementsArray = obj.entries["elements"] as? JsonValue.Arr ?: return emptyList()
        return elementsArray.items.mapNotNull { item ->
            val itemObj = item as? JsonValue.Obj ?: return@mapNotNull null
            try {
                elementFromJson(itemObj)
            } catch (e: IllegalArgumentException) {
                // A single corrupt element (e.g. a negative width from a hand-edited
                // file) must not take the rest of a valid canvas down with it.
                null
            }
        }
    }

    private fun elementFromJson(obj: JsonValue.Obj): Element {
        fun str(key: String): String? = (obj.entries[key] as? JsonValue.Str)?.value

        fun num(key: String): Double? = (obj.entries[key] as? JsonValue.Num)?.value
        return Element(
            id = str("id") ?: throw IllegalArgumentException("missing id"),
            type = str("type") ?: throw IllegalArgumentException("missing type"),
            x = num("x") ?: 0.0,
            y = num("y") ?: 0.0,
            width = num("width") ?: 0.0,
            height = num("height") ?: 0.0,
            // Absent in canvases saved before rotation existed — those load unrotated.
            rotation = num("rotation") ?: 0.0,
            startX = num("startX"),
            startY = num("startY"),
            endX = num("endX"),
            endY = num("endY"),
            startElementId = str("startElementId"),
            endElementId = str("endElementId"),
        )
    }

    /** Minimal JSON value tree — only what [deserializeElements] needs to walk. */
    private sealed class JsonValue {
        data class Str(
            val value: String,
        ) : JsonValue()

        data class Num(
            val value: Double,
        ) : JsonValue()

        data class Arr(
            val items: List<JsonValue>,
        ) : JsonValue()

        data class Obj(
            val entries: Map<String, JsonValue>,
        ) : JsonValue()

        object Null : JsonValue()

        data class Bool(
            val value: Boolean,
        ) : JsonValue()
    }

    private class JsonParseException(
        message: String,
    ) : Exception(message)

    /**
     * A small recursive-descent JSON parser covering exactly the JSON grammar
     * (object/array/string/number/true/false/null) — general enough to parse
     * any well-formed JSON document (not just what [serializeElements] itself
     * emits), which is what makes [deserializeElements] safe against
     * hand-edited or foreign-tool-written files, not only its own output.
     */
    private class JsonParser(
        private val text: String,
    ) {
        private var pos = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (pos != text.length) throw JsonParseException("trailing content at $pos")
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (pos >= text.length) throw JsonParseException("unexpected end of input")
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseStringLiteral())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                else -> parseNumber()
            }
        }

        private fun parseLiteral(
            literal: String,
            value: JsonValue,
        ): JsonValue {
            if (!text.startsWith(literal, pos)) throw JsonParseException("expected '$literal' at $pos")
            pos += literal.length
            return value
        }

        private fun parseObject(): JsonValue.Obj {
            expect('{')
            val entries = mutableMapOf<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                val key = parseStringLiteral()
                skipWhitespace()
                expect(':')
                entries[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        pos++
                    }
                    '}' -> {
                        pos++
                        return JsonValue.Obj(entries)
                    }
                    else -> throw JsonParseException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        pos++
                    }
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> throw JsonParseException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseStringLiteral(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) throw JsonParseException("unterminated string")
                when (val c = text[pos]) {
                    '"' -> {
                        pos++
                        return sb.toString()
                    }
                    '\\' -> {
                        pos++
                        sb.append(readEscapedChar())
                    }
                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
            }
        }

        /** Reads one escape sequence's *body* (the part after the backslash already consumed by the caller). */
        private fun readEscapedChar(): Char {
            if (pos >= text.length) throw JsonParseException("unterminated escape")
            val esc = text[pos]
            if (esc == 'u') return readUnicodeEscape()
            val mapped = SIMPLE_ESCAPES[esc] ?: throw JsonParseException("invalid escape '\\$esc'")
            pos++
            return mapped
        }

        private fun readUnicodeEscape(): Char {
            if (pos + 4 >= text.length) throw JsonParseException("truncated unicode escape")
            val hex = text.substring(pos + 1, pos + 5)
            pos += 5
            return hex.toInt(16).toChar()
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && isNumberChar(text[pos])) pos++
            val slice = text.substring(start, pos)
            val value = slice.toDoubleOrNull() ?: throw JsonParseException("invalid number '$slice' at $start")
            return JsonValue.Num(value)
        }

        private fun isNumberChar(c: Char): Boolean = c.isDigit() || c in NUMBER_SYMBOL_CHARS

        // A non-nullable sentinel (never a valid structural character we compare
        // against) rather than `Char?` for end-of-input: `peek() == someChar`
        // comparisons against a nullable Char box an otherwise-unreachable
        // null-check branch that no test input can ever hit, which is exactly
        // what was showing up as permanently "missed" branches in coverage —
        // not a real gap, a tooling artifact of nullable-primitive comparisons.
        private fun peek(): Char = if (pos < text.length) text[pos] else END_OF_INPUT

        private fun expect(c: Char) {
            if (peek() != c) throw JsonParseException("expected '$c' at $pos")
            pos++
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private companion object {
            // @JvmField: plain static fields, no generated getters that nothing calls
            // (those showed up as permanently-uncovered methods in the coverage report).
            @JvmField
            val SIMPLE_ESCAPES =
                mapOf(
                    '"' to '"',
                    '\\' to '\\',
                    '/' to '/',
                    'n' to '\n',
                    'r' to '\r',
                    't' to '\t',
                    'b' to '\b',
                )

            @JvmField
            val NUMBER_SYMBOL_CHARS = charArrayOf('.', 'e', 'E', '+', '-')
            const val END_OF_INPUT = ' '
        }
    }
}
