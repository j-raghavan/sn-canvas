package com.sncanvas.canvas

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Pure editing and geometry operations on the canvas model (CanvasModel.kt):
 * hit-testing, pan/zoom, insert/move/delete, handles and connector resolution.
 * What a handle drag reshapes lives beside it in [ShapeEdits]. Every function
 * takes a [CanvasState]/[Element] and returns a new one, so [CanvasView] owns
 * only rendering and gestures (PRD NFR5).
 */
@Suppress("TooManyFunctions") // one pure-math home for the model's geometry, hit-testing and edits, per file doc above
object CanvasCore {
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

    internal fun elementCenter(element: Element): Point = Point(element.x + element.width / 2, element.y + element.height / 2)

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

    internal val CORNER_ORDER = listOf(Corner.TOP_LEFT, Corner.TOP_RIGHT, Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT)

    /** Screen-space gap between a shape's top edge and its rotate handle; divided by zoom to get world units. */
    const val ROTATE_HANDLE_OFFSET_PX = 56.0

    /** Screen-space gap between a shape's top-right corner and its link glyph (FR7); divided by zoom as above. */
    const val LINK_GLYPH_OFFSET_PX = 34.0

    /** cos 45°, which is how far out along each axis an ellipse's curve reaches on its top-right diagonal. */
    private val DIAGONAL = sqrt(2.0) / 2

    /** Where a bbox [element]'s rotate handle sits: [ROTATE_HANDLE_OFFSET_PX] screen px above its top-center, rotated with it. */
    fun rotateHandlePoint(
        element: Element,
        zoom: Double,
    ): Point = element.toWorld(element.x + element.width / 2, element.y - ROTATE_HANDLE_OFFSET_PX / zoom)

    /**
     * Where a linked element's glyph sits (FR7): just outside its top-right
     * corner, at a fixed size on screen, so it is as tappable zoomed out as
     * zoomed in. A connector carries it at the end it points to, and an
     * ellipse off its own curve: the corner of the box around a circle stands
     * well clear of it, and a glyph there reads as belonging to nothing.
     */
    fun linkGlyphPoint(
        element: Element,
        elements: List<Element>,
        zoom: Double,
    ): Point {
        // Where it ends now: an end bound to a shape moves with it, and the endpoint the element carries is stale.
        if (element.hasEndpoints()) {
            return resolveArrowEndpoints(element, elements).second
        }
        // How far right and up of the shape's centre its outline reaches on that diagonal: the box's corner for a
        // rectangle, the curve itself for an ellipse. Only a shape with a box has one, hence after the endpoints.
        val reach = if (element.type == CanvasTools.ELLIPSE) DIAGONAL else 1.0
        val gap = LINK_GLYPH_OFFSET_PX / zoom
        return element.toWorld(
            element.x + element.width / 2 * (1 + reach) + gap,
            element.y + element.height / 2 * (1 - reach) - gap,
        )
    }

    /**
     * The topmost linked element whose glyph is under the world point, or null
     * when the point is on none. Topmost first, as hit-testing is, so a glyph
     * over another element's answers before the one beneath it.
     */
    fun linkGlyphAt(
        worldX: Double,
        worldY: Double,
        elements: List<Element>,
        zoom: Double,
        toleranceWorld: Double,
    ): Element? =
        elements.lastOrNull { element ->
            if (element.link == null) {
                false
            } else {
                val glyph = linkGlyphPoint(element, elements, zoom)
                distance(worldX, worldY, glyph.x, glyph.y) <= toleranceWorld
            }
        }

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
     * [CanvasView], since that's where the screen<->world scale factor is
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
     * ellipse) — [CanvasView] never routes a whole-element move for a
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
     * Every element in [ids] moved by ([dx], [dy]): a drag of a whole selection
     * (FR7). A line or arrow among them moves by both its endpoints, since it
     * has no x/y of its own; an endpoint bound to an element that is moving too
     * follows that element anyway, so only free endpoints are shifted.
     */
    fun moveElements(
        state: CanvasState,
        ids: Set<String>,
        dx: Double,
        dy: Double,
    ): CanvasState =
        state.copy(
            elements =
                state.elements.map { element ->
                    when {
                        element.id !in ids -> element
                        !element.hasEndpoints() -> element.copy(x = element.x + dx, y = element.y + dy)
                        else ->
                            element.copy(
                                startX = element.startX?.let { if (element.startElementId in ids) it else it + dx },
                                startY = element.startY?.let { if (element.startElementId in ids) it else it + dy },
                                endX = element.endX?.let { if (element.endElementId in ids) it else it + dx },
                                endY = element.endY?.let { if (element.endElementId in ids) it else it + dy },
                            )
                    }
                },
        )

    /**
     * The elements whose bounds meet [rect], for a selection dragged out over
     * them (FR7): touching is enough, an element need not be wholly inside. A
     * connector's endpoints are resolved against [elements], so one bound to a
     * shape is judged where it is drawn, not where it was stored.
     */
    fun elementsIn(
        rect: WorldRect,
        elements: List<Element>,
    ): List<Element> =
        elements.filter { element ->
            val corners = if (element.hasEndpoints()) resolveArrowEndpoints(element, elements).toList() else cornerPoints(element)
            val bounds = ViewTransforms.boundsOf(corners)
            bounds.left <= rect.right && bounds.right >= rect.left && bounds.top <= rect.bottom && bounds.bottom >= rect.top
        }
}
