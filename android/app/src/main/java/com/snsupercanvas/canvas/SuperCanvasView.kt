package com.snsupercanvas.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The SuperCanvas rendering surface (spec/SuperCanvas-PRD.md §9, NFR1-NFR3, NFR5).
 *
 * All gesture and gesture-to-transform math happens here, natively, so raw
 * touch/stylus samples never cross the JS bridge (NFR2) — only coarse,
 * batched events (stroke committed, selection changed, viewport settled)
 * are meant to be relayed up via a Fabric/RN event emitter, which is not
 * yet wired (see TODOs below).
 *
 * v1a scope: select/rectangle/ellipse tools, tap-to-select, drag-to-insert
 * a shape, drag-to-move the selected element, delete/undo/redo.
 *
 * v1b adds: line/arrow tools (endpoint-defined, not bbox), connector binding
 * (FR7 — an arrow's endpoint can anchor to a shape and re-routes live when
 * that shape moves, via [SuperCanvasCore.resolveArrowEndpoints]), and resize
 * handles (FR9 — 4 corner handles for a selected rectangle/ellipse, 2
 * endpoint handles for a selected line/arrow). A shape/line/arrow tool being
 * active claims the whole drag gesture for drawing — there's no dedicated
 * "hand" tool yet, so panning is only available in "select" mode (tap an
 * empty area, or drag not starting on the selected element or one of its
 * handles).
 *
 * Known v1b scope limitation: dragging the *body* of a selected line/arrow
 * (as opposed to one of its endpoint handles) pans the canvas instead of
 * moving the line — whole-line translation isn't implemented this slice,
 * only per-endpoint dragging. See [handleActionDown]'s `!hit.hasEndpoints()`
 * guard.
 *
 * NOT yet implemented (tracked against the PRD sections noted):
 *  - TODO(v1, NFR3): cached World Bitmap + invalidate(dirtyRect) partial
 *    repaint instead of a full redraw on every frame.
 *  - TODO(v1, NFR1): a cheap draw pass during an active gesture (skip AA /
 *    smoothing) vs. a full-quality pass once the gesture settles.
 *  - TODO(v1): emit batched events to JS (e.g. onSelectionChanged) via a
 *    Fabric/RN event emitter — deliberately deferred; the JS toolbar's
 *    Delete/Undo/Redo buttons are always tappable and the native methods
 *    below are safe no-ops when nothing applies.
 *
 * Gesture dispatch is deliberately split into many small, single-purpose
 * handlers rather than a few large ones, hence the [Suppress] below.
 */
@Suppress("TooManyFunctions")
class SuperCanvasView(
    context: Context,
) : View(context) {
    private var state =
        CanvasState(
            elements = emptyList(),
            viewportX = 0.0,
            viewportY = 0.0,
            zoom = 1.0,
        )

    private var toolMode: String = TOOL_SELECT
    private var selectedElementId: String? = null

    // Undo/redo: a snapshot history of just the element list (NOT the full
    // CanvasState) so panning/zooming between edits is never itself undone —
    // only create/move/resize/delete are undoable, per FR10's stated scope.
    private val history = mutableListOf<List<Element>>(emptyList())
    private var historyIndex = 0

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

    // Minimap: this view decides when it shows (pan/zoom); MinimapRenderer draws it.
    private val minimapRenderer = MinimapRenderer()
    private var isMinimapVisible = false

    private val hideMinimapRunnable =
        Runnable {
            isMinimapVisible = false
            invalidate()
        }

    // Gesture bookkeeping. Only one of isPanning/isMovingElement/isDraggingShape/
    // isResizing/isDraggingEndpoint is ever true at a time within a single touch sequence.
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var isPanning = false
    private var isMovingElement = false
    private var isDraggingShape = false
    private var isResizing = false
    private var isDraggingEndpoint = false
    private var isRotating = false
    private var activeCorner: Corner? = null
    private var activeEndpoint: Endpoint? = null
    private var liveMoveOffsetX = 0.0
    private var liveMoveOffsetY = 0.0
    private var dragCurrentX = 0f
    private var dragCurrentY = 0f

    private val scaleGestureDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val focalWorldX = screenXToWorld(detector.focusX)
                    val focalWorldY = screenYToWorld(detector.focusY)
                    state =
                        SuperCanvasCore.zoomTo(
                            state,
                            scaleFactor = detector.scaleFactor.toDouble(),
                            focalWorldX = focalWorldX,
                            focalWorldY = focalWorldY,
                        )
                    showMinimap()
                    invalidate()
                    return true
                }
            },
        )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activeInstance = this
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hideMinimapRunnable)
        if (activeInstance === this) activeInstance = null
    }

    /** Sets the active tool ("select" | "rectangle" | "ellipse" | "line" | "arrow"). Unknown values behave as "select". */
    fun setToolMode(mode: String) {
        toolMode = mode
    }

    /** Replaces the current canvas content, e.g. after `loadCanvas` resolves in JS, and resets undo history to it. */
    fun setElements(elements: List<Element>) {
        state = state.copy(elements = elements)
        history.clear()
        history.add(elements)
        historyIndex = 0
        selectedElementId = null
        invalidate()
    }

    /** Returns a snapshot of the current canvas state, e.g. for `saveCanvas`. */
    fun getState(): CanvasState = state

    /** Deletes the selected element, if any. Safe no-op if nothing is selected. */
    fun deleteSelected() {
        val id = selectedElementId ?: return
        commitElements(SuperCanvasCore.deleteElement(state, id).elements)
        selectedElementId = null
    }

    /** Steps the undo history back one entry. Safe no-op if there's nothing to undo. */
    fun undo() {
        if (historyIndex <= 0) return
        historyIndex--
        state = state.copy(elements = history[historyIndex])
        selectedElementId = null
        invalidate()
    }

    /** Steps the undo history forward one entry. Safe no-op if there's nothing to redo. */
    fun redo() {
        if (historyIndex >= history.size - 1) return
        historyIndex++
        state = state.copy(elements = history[historyIndex])
        selectedElementId = null
        invalidate()
    }

    private fun commitElements(newElements: List<Element>) {
        state = state.copy(elements = newElements)
        if (historyIndex < history.size - 1) {
            history.subList(historyIndex + 1, history.size).clear()
        }
        history.add(newElements)
        historyIndex++
        invalidate()
    }

    private fun screenXToWorld(screenX: Float): Double = state.viewportX + screenX / state.zoom

    private fun screenYToWorld(screenY: Float): Double = state.viewportY + screenY / state.zoom

    private fun generateElementId(): String = "el-" + System.currentTimeMillis() + "-" + (0..9999).random()

    private fun isShapeTool(): Boolean =
        toolMode == TOOL_RECTANGLE || toolMode == TOOL_ELLIPSE || toolMode == TOOL_LINE || toolMode == TOOL_ARROW

    /** Bindable targets for a new line/arrow endpoint: shapes only, never another line/arrow. */
    private fun bindableElements(): List<Element> = state.elements.filterNot { it.hasEndpoints() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown(event)
            MotionEvent.ACTION_MOVE -> handleActionMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handleActionUp(event)
        }
        return true
    }

    private fun handleActionDown(event: MotionEvent) {
        downTouchX = event.x
        downTouchY = event.y
        lastTouchX = event.x
        lastTouchY = event.y
        dragCurrentX = event.x
        dragCurrentY = event.y
        liveMoveOffsetX = 0.0
        liveMoveOffsetY = 0.0
        isResizing = false
        isDraggingEndpoint = false
        isRotating = false
        activeCorner = null
        activeEndpoint = null

        if (isShapeTool()) {
            isDraggingShape = true
            isMovingElement = false
            isPanning = false
            return
        }

        val handleHit = selectedElementId?.let { id -> handleAtScreen(id, event.x, event.y) }
        when (handleHit) {
            is HandleTarget.CornerHandle -> {
                isResizing = true
                activeCorner = handleHit.corner
                isMovingElement = false
                isPanning = false
                isDraggingShape = false
            }
            is HandleTarget.EndpointHandle -> {
                isDraggingEndpoint = true
                activeEndpoint = handleHit.which
                isMovingElement = false
                isPanning = false
                isDraggingShape = false
            }
            HandleTarget.RotateHandle -> {
                isRotating = true
                isMovingElement = false
                isPanning = false
                isDraggingShape = false
            }
            null -> {
                val hit = SuperCanvasCore.hitTest(screenXToWorld(event.x), screenYToWorld(event.y), state.elements)
                // A selected line/arrow's *body* isn't draggable this slice (only its
                // endpoint handles are, handled above) — falling through to pan is the
                // safe/graceful behavior, see class doc.
                isMovingElement = selectedElementId != null && hit != null && hit.id == selectedElementId && !hit.hasEndpoints()
                isDraggingShape = false
                isPanning = !isMovingElement
            }
        }
    }

    private fun handleAtScreen(
        elementId: String,
        screenX: Float,
        screenY: Float,
    ): HandleTarget? {
        val toleranceWorld = HANDLE_HIT_RADIUS_PX / state.zoom
        return SuperCanvasCore.handleAt(
            state,
            elementId,
            screenXToWorld(screenX).toDouble(),
            screenYToWorld(screenY).toDouble(),
            toleranceWorld.toDouble(),
        )
    }

    private fun handleActionMove(event: MotionEvent) {
        if (scaleGestureDetector.isInProgress) return
        val dxScreen = event.x - lastTouchX
        val dyScreen = event.y - lastTouchY
        when {
            isMovingElement -> {
                liveMoveOffsetX += dxScreen / state.zoom
                liveMoveOffsetY += dyScreen / state.zoom
                invalidate()
            }
            isResizing || isDraggingEndpoint || isDraggingShape -> {
                dragCurrentX = event.x
                dragCurrentY = event.y
                invalidate()
            }
            isRotating -> {
                dragCurrentX = event.x
                dragCurrentY = event.y
                invalidate()
            }
            isPanning -> {
                state = SuperCanvasCore.panBy(state, (dxScreen / state.zoom).toDouble(), (dyScreen / state.zoom).toDouble())
                // Past tap distance only, so a plain tap never flashes the minimap (an e-ink repaint).
                if (hypot((event.x - downTouchX).toDouble(), (event.y - downTouchY).toDouble()) > TAP_SLOP_PX) showMinimap()
                invalidate()
            }
        }
        lastTouchX = event.x
        lastTouchY = event.y
    }

    private fun handleActionUp(event: MotionEvent) {
        val totalDist = hypot((event.x - downTouchX).toDouble(), (event.y - downTouchY).toDouble())
        when {
            isMovingElement -> commitPendingMove(totalDist)
            isResizing -> commitResize(event)
            isDraggingEndpoint -> commitEndpointDrag(event)
            isRotating -> commitRotation(event)
            isDraggingShape -> commitDraggedShape(event, totalDist)
            toolMode == TOOL_SELECT && totalDist <= TAP_SLOP_PX -> {
                val worldX = screenXToWorld(event.x)
                val worldY = screenYToWorld(event.y)
                selectedElementId = SuperCanvasCore.hitTest(worldX, worldY, state.elements)?.id
            }
        }
        isPanning = false
        isMovingElement = false
        isDraggingShape = false
        isResizing = false
        isDraggingEndpoint = false
        isRotating = false
        activeCorner = null
        activeEndpoint = null
        liveMoveOffsetX = 0.0
        liveMoveOffsetY = 0.0
        // TODO(v1): emit onViewportSettled here (NFR1's "settle" pass trigger).
        if (isMinimapVisible) scheduleMinimapHide()
        invalidate()
    }

    /** Shows the minimap and cancels any pending hide — called on every pan/zoom step. */
    private fun showMinimap() {
        removeCallbacks(hideMinimapRunnable)
        isMinimapVisible = true
    }

    /** Hides the minimap [MINIMAP_LINGER_MS] after the gesture ends (e-ink: no fade, one repaint). */
    private fun scheduleMinimapHide() {
        removeCallbacks(hideMinimapRunnable)
        postDelayed(hideMinimapRunnable, MINIMAP_LINGER_MS)
    }

    private fun commitPendingMove(totalDist: Double) {
        val id = selectedElementId ?: return
        val hasMoved = liveMoveOffsetX != 0.0 || liveMoveOffsetY != 0.0
        if (totalDist > TAP_SLOP_PX && hasMoved) {
            commitElements(SuperCanvasCore.moveElement(state, id, liveMoveOffsetX, liveMoveOffsetY).elements)
        }
    }

    private fun commitResize(event: MotionEvent) {
        val id = selectedElementId ?: return
        val corner = activeCorner ?: return
        val worldX = screenXToWorld(event.x)
        val worldY = screenYToWorld(event.y)
        commitElements(SuperCanvasCore.resizeElement(state, id, corner, worldX, worldY).elements)
    }

    private fun commitEndpointDrag(event: MotionEvent) {
        val id = selectedElementId ?: return
        val which = activeEndpoint ?: return
        val worldX = screenXToWorld(event.x)
        val worldY = screenYToWorld(event.y)
        val target = SuperCanvasCore.hitTest(worldX, worldY, bindableElements().filterNot { it.id == id })
        commitElements(SuperCanvasCore.moveEndpoint(state, id, which, Point(worldX, worldY), target?.id).elements)
    }

    private fun commitRotation(event: MotionEvent) {
        val id = selectedElementId ?: return
        val pointer = Point(screenXToWorld(event.x), screenYToWorld(event.y))
        commitElements(SuperCanvasCore.rotateElement(state, id, pointer).elements)
    }

    private fun commitDraggedShape(
        event: MotionEvent,
        totalDist: Double,
    ) {
        if (totalDist <= MIN_SHAPE_DRAG_PX) return
        val worldX1 = screenXToWorld(downTouchX)
        val worldY1 = screenYToWorld(downTouchY)
        val worldX2 = screenXToWorld(event.x)
        val worldY2 = screenYToWorld(event.y)
        val newElement =
            if (toolMode == TOOL_LINE || toolMode == TOOL_ARROW) {
                buildLineElement(worldX1, worldY1, worldX2, worldY2)
            } else {
                buildBoxElement(worldX1, worldY1, worldX2, worldY2)
            }
        commitElements(SuperCanvasCore.insertElement(state, newElement).elements)
    }

    private fun buildBoxElement(
        worldX1: Double,
        worldY1: Double,
        worldX2: Double,
        worldY2: Double,
    ): Element {
        val left = minOf(worldX1, worldX2)
        val top = minOf(worldY1, worldY2)
        val right = maxOf(worldX1, worldX2)
        val bottom = maxOf(worldY1, worldY2)
        return Element(id = generateElementId(), type = toolMode, x = left, y = top, width = right - left, height = bottom - top)
    }

    private fun buildLineElement(
        worldX1: Double,
        worldY1: Double,
        worldX2: Double,
        worldY2: Double,
    ): Element {
        val startTarget = SuperCanvasCore.hitTest(worldX1, worldY1, bindableElements())
        val endTarget = SuperCanvasCore.hitTest(worldX2, worldY2, bindableElements())
        return Element(
            id = generateElementId(),
            type = toolMode,
            startX = worldX1,
            startY = worldY1,
            endX = worldX2,
            endY = worldY2,
            startElementId = startTarget?.id,
            endElementId = endTarget?.id,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val drawElements = elementsForDrawing()
        val liveTransform = RenderTransform(state.viewportX, state.viewportY, state.zoom.toFloat())
        for (element in drawElements) {
            if (element.hasEndpoints()) {
                drawLineElement(canvas, element, drawElements, liveTransform, selectedElementId)
            } else {
                drawBoxElement(canvas, element, liveTransform, selectedElementId)
            }
        }

        val selectedId = selectedElementId
        if (selectedId != null) drawSelectionHandles(canvas, drawElements, selectedId, liveTransform.zoom)

        if (isDraggingShape) drawShapeDragPreview(canvas)

        if (isMinimapVisible) minimapRenderer.draw(canvas, state, width, height)
    }

    /**
     * Renders [elements] into a fresh [sizePx]-square [Bitmap] (v1d, FR12 —
     * `SuperCanvasModule.generateThumbnail`'s note-embedded thumbnail). Reuses
     * [drawBoxElement]/[drawLineElement] — the exact same per-element drawing
     * this view uses for its own live [onDraw] — against a transform that fits
     * the content into the thumbnail instead of the view's live pan/zoom
     * transform (see [SuperCanvasCore.computeThumbnailTransform]). No selection
     * highlight and no drag preview: a saved thumbnail has no live gesture
     * state to reflect, so `selectedElementId` is passed as `null` here.
     *
     * Safe to call off the main thread: this only draws into a private,
     * not-yet-attached [Bitmap]/[Canvas] pair, never touches this View's own
     * attached surface or invalidates it.
     */
    fun renderThumbnailBitmap(
        elements: List<Element>,
        sizePx: Int = THUMBNAIL_SIZE_PX,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), backgroundPaint)
        val fit = SuperCanvasCore.computeThumbnailTransform(elements, sizePx.toDouble(), THUMBNAIL_PADDING_PX)
        val thumbnailTransform = RenderTransform(fit.viewportX, fit.viewportY, fit.zoom.toFloat())
        for (element in elements) {
            if (element.hasEndpoints()) {
                drawLineElement(canvas, element, elements, thumbnailTransform, highlightSelectedId = null)
            } else {
                drawBoxElement(canvas, element, thumbnailTransform, highlightSelectedId = null)
            }
        }
        return bitmap
    }

    /** The element list to render this frame, substituting a live (uncommitted) preview during an active gesture. */
    private fun elementsForDrawing(): List<Element> {
        val id = selectedElementId
        return when {
            isMovingElement && id != null ->
                state.elements.map { el ->
                    if (el.id == id) el.copy(x = el.x + liveMoveOffsetX, y = el.y + liveMoveOffsetY) else el
                }
            isResizing && id != null && activeCorner != null ->
                SuperCanvasCore
                    .resizeElement(
                        state,
                        id,
                        activeCorner!!,
                        screenXToWorld(dragCurrentX),
                        screenYToWorld(dragCurrentY),
                    ).elements
            isDraggingEndpoint && id != null && activeEndpoint != null ->
                SuperCanvasCore
                    .moveEndpoint(
                        state,
                        id,
                        activeEndpoint!!,
                        Point(screenXToWorld(dragCurrentX), screenYToWorld(dragCurrentY)),
                        targetElementId = null,
                    ).elements
            isRotating && id != null ->
                SuperCanvasCore
                    .rotateElement(state, id, Point(screenXToWorld(dragCurrentX), screenYToWorld(dragCurrentY)))
                    .elements
            else -> state.elements
        }
    }

    /**
     * Bundles the three values that vary between this view's own live
     * [onDraw] (its live pan/zoom) and [renderThumbnailBitmap] (a
     * fit-to-content transform) so [drawBoxElement]/[drawLineElement] can
     * share one draw path against either without an overlong parameter list.
     */
    private data class RenderTransform(
        val viewportX: Double,
        val viewportY: Double,
        val zoom: Float,
    )

    /**
     * [transform] is passed explicitly (not read from `state`) so this same
     * drawing logic serves both this view's own live [onDraw] and
     * [renderThumbnailBitmap] without duplicating it (v1d, FR12).
     * [highlightSelectedId] is `null` for a thumbnail render — a saved
     * thumbnail has no live selection to highlight.
     */
    private fun drawBoxElement(
        canvas: Canvas,
        element: Element,
        transform: RenderTransform,
        highlightSelectedId: String?,
    ) {
        val (viewportX, viewportY, zoom) = transform
        val left = ((element.x - viewportX) * zoom).toFloat()
        val top = ((element.y - viewportY) * zoom).toFloat()
        val right = ((element.x + element.width - viewportX) * zoom).toFloat()
        val bottom = ((element.y + element.height - viewportY) * zoom).toFloat()
        val paint = if (element.id == highlightSelectedId) selectedPaint else elementPaint
        val bounds = RectF(left, top, right, bottom)
        drawRotatedBox(canvas, element, bounds, paint)
    }

    /** See [drawBoxElement]'s doc — same explicit-transform reuse rationale. */
    private fun drawLineElement(
        canvas: Canvas,
        element: Element,
        allElements: List<Element>,
        transform: RenderTransform,
        highlightSelectedId: String?,
    ) {
        val (viewportX, viewportY, zoom) = transform
        val (startWorld, endWorld) = SuperCanvasCore.resolveArrowEndpoints(element, allElements)
        val x1 = ((startWorld.x - viewportX) * zoom).toFloat()
        val y1 = ((startWorld.y - viewportY) * zoom).toFloat()
        val x2 = ((endWorld.x - viewportX) * zoom).toFloat()
        val y2 = ((endWorld.y - viewportY) * zoom).toFloat()
        val paint = if (element.id == highlightSelectedId) selectedPaint else elementPaint
        canvas.drawLine(x1, y1, x2, y2, paint)
        if (element.type == TOOL_ARROW) drawArrowhead(canvas, PointF(x1, y1), PointF(x2, y2), paint)
    }

    private fun drawArrowhead(
        canvas: Canvas,
        start: PointF,
        end: PointF,
        paint: Paint,
    ) {
        val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
        val leftAngle = angle - ARROWHEAD_SPREAD_RAD
        val rightAngle = angle + ARROWHEAD_SPREAD_RAD
        val leftX = end.x - (ARROWHEAD_LENGTH_PX * cos(leftAngle)).toFloat()
        val leftY = end.y - (ARROWHEAD_LENGTH_PX * sin(leftAngle)).toFloat()
        val rightX = end.x - (ARROWHEAD_LENGTH_PX * cos(rightAngle)).toFloat()
        val rightY = end.y - (ARROWHEAD_LENGTH_PX * sin(rightAngle)).toFloat()
        canvas.drawLine(end.x, end.y, leftX, leftY, paint)
        canvas.drawLine(end.x, end.y, rightX, rightY, paint)
    }

    private fun drawSelectionHandles(
        canvas: Canvas,
        elements: List<Element>,
        selectedId: String,
        zoom: Float,
    ) {
        val element = elements.find { it.id == selectedId } ?: return
        if (element.hasEndpoints()) {
            val (startWorld, endWorld) = SuperCanvasCore.resolveArrowEndpoints(element, elements)
            drawHandle(canvas, (startWorld.x - state.viewportX) * zoom, (startWorld.y - state.viewportY) * zoom)
            drawHandle(canvas, (endWorld.x - state.viewportX) * zoom, (endWorld.y - state.viewportY) * zoom)
        } else {
            for (point in SuperCanvasCore.cornerPoints(element)) {
                drawHandle(canvas, (point.x - state.viewportX) * zoom, (point.y - state.viewportY) * zoom)
            }
            drawRotateHandle(canvas, element, zoom)
        }
    }

    private fun drawHandle(
        canvas: Canvas,
        screenX: Double,
        screenY: Double,
    ) {
        val half = HANDLE_DRAW_SIZE_PX / 2f
        canvas.drawRect(
            (screenX - half).toFloat(),
            (screenY - half).toFloat(),
            (screenX + half).toFloat(),
            (screenY + half).toFloat(),
            handlePaint,
        )
    }

    /** A circular-arrow handle above the shape's top-center (rotated with it), joined to the shape by a short stem. */
    private fun drawRotateHandle(
        canvas: Canvas,
        element: Element,
        zoom: Float,
    ) {
        val topCenter = element.toWorld(element.x + element.width / 2, element.y)
        val handle = SuperCanvasCore.rotateHandlePoint(element, zoom.toDouble())
        val stemX = ((topCenter.x - state.viewportX) * zoom).toFloat()
        val stemY = ((topCenter.y - state.viewportY) * zoom).toFloat()
        val handleX = ((handle.x - state.viewportX) * zoom).toFloat()
        val handleY = ((handle.y - state.viewportY) * zoom).toFloat()
        canvas.drawLine(stemX, stemY, handleX, handleY, rotateStemPaint)
        drawRotateHandleGlyph(canvas, handleX, handleY, ROTATE_HANDLE_RADIUS_PX)
    }

    private fun drawShapeDragPreview(canvas: Canvas) {
        if (toolMode == TOOL_LINE || toolMode == TOOL_ARROW) {
            canvas.drawLine(downTouchX, downTouchY, dragCurrentX, dragCurrentY, previewPaint)
            if (toolMode == TOOL_ARROW) {
                drawArrowhead(canvas, PointF(downTouchX, downTouchY), PointF(dragCurrentX, dragCurrentY), previewPaint)
            }
            return
        }
        val bounds =
            RectF(
                minOf(downTouchX, dragCurrentX),
                minOf(downTouchY, dragCurrentY),
                maxOf(downTouchX, dragCurrentX),
                maxOf(downTouchY, dragCurrentY),
            )
        if (toolMode == TOOL_ELLIPSE) canvas.drawOval(bounds, previewPaint) else canvas.drawRect(bounds, previewPaint)
    }

    companion object {
        const val TOOL_SELECT = "select"
        const val TOOL_RECTANGLE = "rectangle"
        const val TOOL_ELLIPSE = "ellipse"
        const val TOOL_LINE = "line"
        const val TOOL_ARROW = "arrow"
        private const val TAP_SLOP_PX = 12f
        private const val MIN_SHAPE_DRAG_PX = 8f
        private const val HANDLE_HIT_RADIUS_PX = 28f
        private const val HANDLE_DRAW_SIZE_PX = 24f
        private const val ROTATE_HANDLE_RADIUS_PX = 22f
        private const val ARROWHEAD_LENGTH_PX = 28f
        private const val ARROWHEAD_SPREAD_RAD = Math.PI / 7

        /** Note-embedded thumbnail size (v1d, FR12) — a square PNG, not the live view's own pixel dimensions. */
        const val THUMBNAIL_SIZE_PX = 400
        private const val THUMBNAIL_PADDING_PX = 24.0

        private const val MINIMAP_LINGER_MS = 1500L

        private var activeInstance: SuperCanvasView? = null

        /**
         * The currently-mounted view instance, if any (v1c persistence). There is
         * no other cross-class link between [SuperCanvasModule] (which needs the
         * live element list to save, and needs to push a loaded list back in) and
         * this view — a single mutable module-level reference, updated from
         * [onAttachedToWindow]/[onDetachedFromWindow], is the standard, simplest
         * way to bridge that for a plugin that only ever mounts one canvas at a
         * time. Not device-verified whether attach/detach fire exactly once each
         * across this plugin's specific show/hide lifecycle (vs. e.g. multiple
         * attach/detach cycles if the host reuses the view) — worth confirming
         * on-device that save-on-close still reads a non-null instance.
         */
        fun currentInstance(): SuperCanvasView? = activeInstance
    }
}
