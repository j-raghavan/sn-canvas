package com.snsupercanvas.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.util.Log
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
    /** Receives what the action bar and style panel should show, whenever it changes (FR18/FR19). */
    private val uiStateListener: (SuperCanvasView, CanvasUiState) -> Unit = { _, _ -> },
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
        set(value) {
            field = value
            publishUiState()
        }

    // The style new elements get (FR19); the style panel changes it through setStyle.
    private var currentStyle = ShapeStyle.DEFAULT
    private var lastUiState: CanvasUiState? = null
    private var gesture: Gesture? = null
    private var isFitPending = false
    private val arbiter = TouchArbiter()

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
        publishUiState()
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

    /** Replaces the canvas content (after `loadCanvas`), restarts the undo history from it, and frames it (FR13). */
    fun setElements(elements: List<Element>) {
        history.reset(elements)
        showElements(elements)
        fitToContent()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isFitPending) fitToContent()
    }

    /** Frames all content in the view; before the first layout there is no size yet, so it waits for [onSizeChanged]. */
    private fun fitToContent() {
        isFitPending = width == 0 || height == 0
        if (isFitPending) return
        val fit = ViewTransforms.fitToView(state.elements, width.toDouble(), height.toDouble(), FIT_PADDING_PX)
        state = state.copy(viewportX = fit.viewportX, viewportY = fit.viewportY, zoom = fit.zoom)
        invalidate()
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

    /** Duplicates the selected element, a little down and to the right, and selects the copy (FR18). */
    fun duplicateSelected() {
        val id = selectedElementId ?: return
        val copyId = generateElementId()
        commitElements(CanvasActions.duplicate(state, id, copyId, DUPLICATE_OFFSET_PX / state.zoom).elements)
        selectedElementId = copyId
    }

    // Not bringToFront/sendToBack: View already has a bringToFront of its own.
    fun bringSelectedToFront() = editSelected { CanvasActions.bringToFront(state, it) }

    fun sendSelectedToBack() = editSelected { CanvasActions.sendToBack(state, it) }

    /** Frames all content in the view. */
    fun zoomToFit() = fitToContent()

    /** Returns to 100% zoom about the view's center. */
    fun zoomTo100() {
        val center = toWorld(width / 2f, height / 2f)
        state = SuperCanvasCore.zoomTo(state, 1.0 / state.zoom, center.x, center.y)
        invalidate()
    }

    /**
     * Sets one style property (FR19), such as "color" to "red": for elements
     * drawn from now on, and, when an element is selected, on it too, as one
     * undoable step. Only that property changes; the rest of its style stays.
     */
    fun setStyle(
        property: String,
        value: String,
    ) {
        currentStyle = currentStyle.with(property, value)
        val selected = selectedElementId?.let { id -> state.elements.find { it.id == id } }
        if (selected != null) {
            val restyled = selected.style.with(property, value)
            if (restyled != selected.style) commitElements(CanvasActions.restyle(state, selected.id, restyled).elements)
        }
        publishUiState()
    }

    /**
     * The note thumbnail of [elements] (FR12), in true colour. It runs off the UI
     * thread, so it uses a renderer of its own rather than the live one.
     */
    fun renderThumbnailBitmap(elements: List<Element>): Bitmap = CanvasRenderer().renderThumbnail(elements)

    /** Applies [edit] to the selected element as one undoable step; a no-op with nothing selected. */
    private inline fun editSelected(edit: (String) -> CanvasState) {
        val id = selectedElementId ?: return
        commitElements(edit(id).elements)
    }

    /** Tells the UI what the action bar and style panel should show, when that has changed. */
    private fun publishUiState() {
        val selected = selectedElementId?.let { id -> state.elements.find { it.id == id } }
        val uiState = CanvasUiState(history.canUndo, history.canRedo, selected != null, selected?.style ?: currentStyle)
        if (uiState == lastUiState) return
        lastUiState = uiState
        uiStateListener(this, uiState)
    }

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
        publishUiState()
        invalidate()
    }

    private fun toWorld(
        screenX: Float,
        screenY: Float,
    ): Point = Point(state.viewportX + screenX / state.zoom, state.viewportY + screenY / state.zoom)

    private fun distanceFromDown(
        x: Float,
        y: Float,
    ): Double = hypot((x - downTouch.x).toDouble(), (y - downTouch.y).toDouble())

    private fun generateElementId(): String = "el-" + System.currentTimeMillis() + "-" + (0..9999).random()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Pinch-zoom is a two-finger gesture; a palm beside the drawing pen must not zoom.
        if (!arbiter.isPenActive) scaleGestureDetector.onTouchEvent(event)
        val inputs =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> arbiter.down(contactAt(event, event.actionIndex))
                MotionEvent.ACTION_MOVE -> listOfNotNull(arbiter.move(List(event.pointerCount) { contactAt(event, it) }))
                MotionEvent.ACTION_POINTER_UP -> listOfNotNull(arbiter.up(contactAt(event, event.actionIndex), isLastContact = false))
                MotionEvent.ACTION_UP -> listOfNotNull(arbiter.up(contactAt(event, event.actionIndex), isLastContact = true))
                MotionEvent.ACTION_CANCEL -> listOfNotNull(arbiter.cancel())
                else -> emptyList()
            }
        inputs.forEach(::handleInput)
        return true
    }

    private fun contactAt(
        event: MotionEvent,
        index: Int,
    ): Contact {
        val tool = event.getToolType(index)
        val isPen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        return Contact(event.getPointerId(index), isPen, event.getX(index), event.getY(index))
    }

    private fun handleInput(input: PointerInput) {
        when (input) {
            is PointerInput.Start -> handleStart(input.contact)
            is PointerInput.Move -> handleMove(input.x, input.y)
            is PointerInput.End -> handleEnd(input.x, input.y)
            PointerInput.Abandon -> abandonGesture()
        }
    }

    private fun handleStart(contact: Contact) {
        downTouch.set(contact.x, contact.y)
        lastTouch.set(contact.x, contact.y)
        dragCurrent.set(contact.x, contact.y)
        gesture =
            when {
                !CanvasTools.drawsElement(toolMode) -> selectToolGestureAt(toWorld(contact.x, contact.y))
                // Palm rejection: with a drawing tool only the pen draws, as in Supernote's own notes.
                contact.isPen -> Gesture.DrawShape
                else -> null
            }
        Log.d(LOG_TAG, "touch start pen=${contact.isPen} tool=$toolMode gesture=$gesture")
    }

    /** Drops the gesture in progress without committing it: a cancelled stream, or a second finger starting a pinch. */
    private fun abandonGesture() {
        gesture = null
        if (isMinimapVisible) scheduleMinimapHide()
        invalidate()
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

    private fun handleMove(
        x: Float,
        y: Float,
    ) {
        if (scaleGestureDetector.isInProgress) return
        val dx = (x - lastTouch.x) / state.zoom
        val dy = (y - lastTouch.y) / state.zoom
        when (val active = gesture) {
            Gesture.Pan -> {
                state = SuperCanvasCore.panBy(state, dx, dy)
                // Past tap distance only, so a plain tap never flashes the minimap (an e-ink repaint).
                if (distanceFromDown(x, y) > TAP_SLOP_PX) showMinimap()
            }
            is Gesture.Move -> gesture = active.copy(dx = active.dx + dx, dy = active.dy + dy)
            Gesture.DrawShape, Gesture.Rotate, is Gesture.Resize, is Gesture.DragEndpoint -> dragCurrent.set(x, y)
            null -> Unit
        }
        if (gesture != null) invalidate()
        lastTouch.set(x, y)
    }

    private fun handleEnd(
        x: Float,
        y: Float,
    ) {
        val totalDist = distanceFromDown(x, y)
        val end = toWorld(x, y)
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
        commitElements(SuperCanvasCore.insertElement(state, element.copy(style = currentStyle)).elements)
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
        renderer.drawElements(canvas, elements, transform, StylePalette.EINK)
        selectedElementId?.let { renderer.drawSelectionHandles(canvas, elements, it, transform) }
        if (gesture == Gesture.DrawShape) renderer.drawDragPreview(canvas, toolMode, downTouch, dragCurrent)
        if (isMinimapVisible) minimapRenderer.draw(canvas, state, width, height)
    }

    private companion object {
        const val TAP_SLOP_PX = 12f
        const val MIN_SHAPE_DRAG_PX = 8f
        const val HANDLE_HIT_RADIUS_PX = 28f
        const val MINIMAP_LINGER_MS = 1500L

        // Generous, so fitted content clears the floating toolbar at the bottom.
        const val FIT_PADDING_PX = 96.0

        const val LOG_TAG = "SuperCanvas"

        // How far a duplicate lands from its original, in screen pixels.
        const val DUPLICATE_OFFSET_PX = 16.0
    }
}
