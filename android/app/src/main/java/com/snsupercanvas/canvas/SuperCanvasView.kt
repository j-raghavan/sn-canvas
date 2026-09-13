package com.snsupercanvas.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot

/**
 * The SuperCanvas surface (spec/SuperCanvas-PRD.md §9, NFR1-NFR3, NFR5): holds
 * the live [CanvasState], turns touches into edits, and hands each frame to
 * [CanvasRenderer]. Every edit is a pure [SuperCanvasCore] operation and every
 * undo step an [EditHistory] entry, so what remains here is Android glue:
 * gesture dispatch, the selection, and when the minimap shows.
 *
 * All gesture math runs natively, so raw touch samples never cross the JS
 * bridge (NFR2). A drawing tool's drag draws its element. With the select
 * tool, a tap selects; a drag on the selected shape moves it; a drag on one of
 * its handles resizes or rotates it, or re-points a connector end (FR7/FR9);
 * any other drag pans. Pinch zooms with every tool. A line/arrow moves by its
 * endpoint handles only; dragging its body pans.
 *
 * While attached, the view registers itself in the injected [registry]; that
 * is how [SuperCanvasModule] reaches the live canvas.
 *
 * Not yet implemented (PRD sections noted):
 *  - TODO(NFR3): a cached world bitmap with dirty-rect repaint, instead of a full redraw per frame.
 *  - TODO(NFR1): a cheap draw pass during a gesture, and a full-quality pass once it settles.
 *  - TODO(JS events): batched events to JS (e.g. onSelectionChanged). Until then the JS toolbar's
 *    Delete/Undo/Redo stay tappable, and are safe no-ops here when nothing applies.
 */
@Suppress("TooManyFunctions") // small single-purpose gesture handlers, by design
class SuperCanvasView(
    context: Context,
    private val registry: ActiveViewRegistry<SuperCanvasView>,
) : View(context) {
    /** The one gesture a touch sequence performs: decided on ACTION_DOWN, finished on ACTION_UP. */
    private sealed interface Gesture {
        data object Pan : Gesture

        data object DrawShape : Gesture

        data object Rotate : Gesture

        /** Dragging the selected shape's body; the world-space offset accumulates until release. */
        data class Move(
            val dx: Double = 0.0,
            val dy: Double = 0.0,
        ) : Gesture

        data class Resize(
            val corner: Corner,
        ) : Gesture

        data class DragEndpoint(
            val which: Endpoint,
        ) : Gesture
    }

    private var state = CanvasState(elements = emptyList(), viewportX = 0.0, viewportY = 0.0, zoom = 1.0)
    private val history = EditHistory()
    private val renderer = CanvasRenderer()
    private var toolMode = CanvasTools.SELECT
    private var selectedElementId: String? = null
    private var gesture: Gesture? = null

    // Screen-space touch positions for the current gesture.
    private val downTouch = PointF()
    private val lastTouch = PointF()
    private val dragCurrent = PointF()

    // Minimap: this view decides when it shows (during pan/zoom); MinimapRenderer draws it.
    private val minimapRenderer = MinimapRenderer()
    private var isMinimapVisible = false
    private val hideMinimapRunnable =
        Runnable {
            isMinimapVisible = false
            invalidate()
        }

    private val scaleGestureDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val focal = toWorld(detector.focusX, detector.focusY)
                    state = SuperCanvasCore.zoomTo(state, detector.scaleFactor.toDouble(), focal.x, focal.y)
                    showMinimap()
                    invalidate()
                    return true
                }
            },
        )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registry.attach(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hideMinimapRunnable)
        registry.detach(this)
    }

    /** Sets the active tool, a [CanvasTools] id. Unknown ids behave as select. */
    fun setToolMode(mode: String) {
        toolMode = mode
    }

    /** Replaces the canvas content (after `loadCanvas`) and restarts the undo history from it. */
    fun setElements(elements: List<Element>) {
        history.reset(elements)
        showElements(elements)
    }

    /** A snapshot of the current canvas state, e.g. for `saveCanvas`. */
    fun getState(): CanvasState = state

    /** Deletes the selected element; a no-op with nothing selected. */
    fun deleteSelected() {
        val id = selectedElementId ?: return
        commitElements(SuperCanvasCore.deleteElement(state, id).elements)
        selectedElementId = null
    }

    /** Steps back one edit; a no-op with nothing to undo. */
    fun undo() {
        history.undo()?.let(::showElements)
    }

    /** Steps forward one edit; a no-op with nothing to redo. */
    fun redo() {
        history.redo()?.let(::showElements)
    }

    /** The note thumbnail of [elements] (FR12). Safe off the UI thread, see [CanvasRenderer.renderThumbnail]. */
    fun renderThumbnailBitmap(elements: List<Element>): Bitmap = renderer.renderThumbnail(elements)

    /** Shows [elements] as they are (a load, or an undo/redo step), clearing the selection. */
    private fun showElements(elements: List<Element>) {
        state = state.copy(elements = elements)
        selectedElementId = null
        invalidate()
    }

    /** Applies an edit's result as one undoable step. */
    private fun commitElements(elements: List<Element>) {
        state = state.copy(elements = elements)
        history.commit(elements)
        invalidate()
    }

    private fun toWorld(
        screenX: Float,
        screenY: Float,
    ): Point = Point(state.viewportX + screenX / state.zoom, state.viewportY + screenY / state.zoom)

    private fun distanceFromDown(event: MotionEvent): Double = hypot((event.x - downTouch.x).toDouble(), (event.y - downTouch.y).toDouble())

    private fun generateElementId(): String = "el-" + System.currentTimeMillis() + "-" + (0..9999).random()

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
        downTouch.set(event.x, event.y)
        lastTouch.set(event.x, event.y)
        dragCurrent.set(event.x, event.y)
        gesture = if (CanvasTools.drawsElement(toolMode)) Gesture.DrawShape else selectToolGestureAt(toWorld(event.x, event.y))
    }

    /** With the select tool, a handle of the selected element wins over its body, which wins over panning. */
    private fun selectToolGestureAt(world: Point): Gesture {
        val id = selectedElementId ?: return Gesture.Pan
        val tolerance = HANDLE_HIT_RADIUS_PX / state.zoom
        return when (val handle = SuperCanvasCore.handleAt(state, id, world.x, world.y, tolerance)) {
            is HandleTarget.CornerHandle -> Gesture.Resize(handle.corner)
            is HandleTarget.EndpointHandle -> Gesture.DragEndpoint(handle.which)
            HandleTarget.RotateHandle -> Gesture.Rotate
            null -> {
                val hit = SuperCanvasCore.hitTest(world.x, world.y, state.elements)
                if (hit != null && hit.id == id && !hit.hasEndpoints()) Gesture.Move() else Gesture.Pan
            }
        }
    }

    private fun handleActionMove(event: MotionEvent) {
        if (scaleGestureDetector.isInProgress) return
        val dx = (event.x - lastTouch.x) / state.zoom
        val dy = (event.y - lastTouch.y) / state.zoom
        when (val active = gesture) {
            Gesture.Pan -> {
                state = SuperCanvasCore.panBy(state, dx, dy)
                // Past tap distance only, so a plain tap never flashes the minimap (an e-ink repaint).
                if (distanceFromDown(event) > TAP_SLOP_PX) showMinimap()
            }
            is Gesture.Move -> gesture = active.copy(dx = active.dx + dx, dy = active.dy + dy)
            Gesture.DrawShape, Gesture.Rotate, is Gesture.Resize, is Gesture.DragEndpoint -> dragCurrent.set(event.x, event.y)
            null -> Unit
        }
        if (gesture != null) invalidate()
        lastTouch.set(event.x, event.y)
    }

    private fun handleActionUp(event: MotionEvent) {
        val totalDist = distanceFromDown(event)
        val end = toWorld(event.x, event.y)
        when (val finished = gesture) {
            Gesture.Pan -> {
                if (totalDist <= TAP_SLOP_PX) selectedElementId = SuperCanvasCore.hitTest(end.x, end.y, state.elements)?.id
            }
            Gesture.DrawShape -> commitDrawnShape(end, totalDist)
            // Within tap distance, a drag on the selected shape is a tap, not a move.
            is Gesture.Move -> {
                if (totalDist > TAP_SLOP_PX && (finished.dx != 0.0 || finished.dy != 0.0)) commitGestureEdit(finished, end)
            }
            is Gesture.Resize, is Gesture.DragEndpoint, Gesture.Rotate -> commitGestureEdit(finished, end)
            null -> Unit
        }
        gesture = null
        // TODO(NFR1): emit onViewportSettled here, the trigger for the full-quality "settle" pass.
        if (isMinimapVisible) scheduleMinimapHide()
        invalidate()
    }

    /**
     * The edit [active] makes to the selected element [id] with the pointer at
     * [pointer], or null if it edits nothing. The live preview and the commit on
     * release both come from here, so what the user sees while dragging is
     * exactly what they get.
     */
    private fun gestureEdit(
        active: Gesture?,
        id: String,
        pointer: Point,
    ): CanvasState? =
        when (active) {
            is Gesture.Move -> SuperCanvasCore.moveElement(state, id, active.dx, active.dy)
            is Gesture.Resize -> SuperCanvasCore.resizeElement(state, id, active.corner, pointer.x, pointer.y)
            is Gesture.DragEndpoint -> {
                val target = SuperCanvasCore.bindingTargetAt(pointer, state.elements)
                SuperCanvasCore.moveEndpoint(state, id, active.which, pointer, target?.id)
            }
            Gesture.Rotate -> SuperCanvasCore.rotateElement(state, id, pointer)
            Gesture.Pan, Gesture.DrawShape, null -> null
        }

    private fun commitGestureEdit(
        finished: Gesture,
        pointer: Point,
    ) {
        val id = selectedElementId ?: return
        gestureEdit(finished, id, pointer)?.let { commitElements(it.elements) }
    }

    private fun commitDrawnShape(
        end: Point,
        totalDist: Double,
    ) {
        if (totalDist <= MIN_SHAPE_DRAG_PX) return
        val start = toWorld(downTouch.x, downTouch.y)
        val element =
            if (CanvasTools.isConnector(toolMode)) {
                SuperCanvasCore.connectorFromDrag(generateElementId(), toolMode, start, end, state.elements)
            } else {
                SuperCanvasCore.boxFromDrag(generateElementId(), toolMode, start, end)
            }
        commitElements(SuperCanvasCore.insertElement(state, element).elements)
    }

    /** Shows the minimap and cancels any pending hide; called on every pan/zoom step. */
    private fun showMinimap() {
        removeCallbacks(hideMinimapRunnable)
        isMinimapVisible = true
    }

    /** Hides the minimap [MINIMAP_LINGER_MS] after the gesture ends (e-ink: no fade, one repaint). */
    private fun scheduleMinimapHide() {
        removeCallbacks(hideMinimapRunnable)
        postDelayed(hideMinimapRunnable, MINIMAP_LINGER_MS)
    }

    /** The elements to draw this frame: the committed ones, with any in-progress edit previewed on top. */
    private fun elementsForDrawing(): List<Element> {
        val id = selectedElementId ?: return state.elements
        return gestureEdit(gesture, id, toWorld(dragCurrent.x, dragCurrent.y))?.elements ?: state.elements
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.drawBackground(canvas, width.toFloat(), height.toFloat())
        val elements = elementsForDrawing()
        val transform = state.transform
        renderer.drawElements(canvas, elements, transform, selectedElementId)
        selectedElementId?.let { renderer.drawSelectionHandles(canvas, elements, it, transform) }
        if (gesture == Gesture.DrawShape) renderer.drawDragPreview(canvas, toolMode, downTouch, dragCurrent)
        if (isMinimapVisible) minimapRenderer.draw(canvas, state, width, height)
    }

    private companion object {
        const val TAP_SLOP_PX = 12f
        const val MIN_SHAPE_DRAG_PX = 8f
        const val HANDLE_HIT_RADIUS_PX = 28f
        const val MINIMAP_LINGER_MS = 1500L
    }
}
