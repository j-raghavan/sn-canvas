package com.sncanvas.canvas

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
 * The Canvas surface (spec/Canvas-PRD.md §9, NFR1-NFR3, NFR5): it
 * turns touches into edits on its [controller], which holds the canvas and
 * runs every command, and draws what the controller holds through
 * [CanvasRenderer]. What remains here is Android glue: gesture dispatch
 * ([CanvasGesture]), text measuring, the pencil's live ink ([LiveInk]), and when
 * the minimap shows. Raw touch samples never cross the JS bridge (NFR2).
 *
 * Select-tool taps select, and a tap on the selected text, note or table cell
 * edits it; drags move, resize, rotate or re-point it, or resize a table's row
 * ([SelectGestures]), or pan. Every
 * other tool works with the pen alone (FR15); the pen's eraser end erases with
 * any tool (FR20). Pinch zooms. While attached, the view registers itself in
 * [registry], which is how [CanvasModule] reaches the live canvas.
 * TODO(NFR1/NFR3): a cached world bitmap with dirty-rect repaint, and a cheap pass during gestures.
 */
@Suppress("TooManyFunctions") // small single-purpose gesture handlers, by design
class CanvasView(
    context: Context,
    private val registry: ActiveViewRegistry<CanvasView>,
    private val events: Events,
    private val images: ImageSource,
) : View(context) {
    /** What the view tells the UI; the view manager sends each as an RN event. */
    interface Events {
        fun onUiState(
            view: CanvasView,
            uiState: CanvasUiState,
        )

        fun onEditText(
            view: CanvasView,
            request: TextEditRequest,
        )

        /** The canvas was touched, by pen or finger: once per touch, at its first contact (the onboarding hints go). */
        fun onTouched(view: CanvasView)

        /**
         * A canvas finished loading, with [hasContent] saying whether anything is on it: the one signal the screen
         * decides the onboarding hints from. Not the canvas state, which the view also republishes as it re-attaches
         * with the canvas it held before, so it cannot tell a load from a leftover.
         */
        fun onLoaded(
            view: CanvasView,
            hasContent: Boolean,
        )

        /** A linked element's glyph was tapped (FR7): the screen follows it, since only JS can open a note. */
        fun onFollowLink(
            view: CanvasView,
            link: ElementLink,
        )
    }

    private val measurer = AndroidTextMeasurer()
    private val renderer = CanvasRenderer(measurer, images)
    private val liveInk = LiveInk(this, renderer)

    /** The canvas itself: elements, history, selection, style and text editing. The view manager routes commands to it. */
    val controller =
        CanvasController(
            measurer,
            ::newElementId,
            object : CanvasController.Listener {
                override fun onChanged() {
                    // A stroke the firmware inked already shows; redrawing would repaint the panel over the next one's ink.
                    if (liveInk.isKeepingInk) return
                    liveInk.wipe()
                    invalidate()
                }

                override fun onUiState(uiState: CanvasUiState) = events.onUiState(this@CanvasView, uiState)

                override fun onEditText(target: CanvasController.EditTarget) = requestTextEditor(target)
            },
        )
    private val selectGestures = SelectGestures({ controller.state }, { controller.selectedElements }, controller::fitted, measurer)

    // What the canvas shows this frame, as opposed to what it holds (CanvasFrame).
    private val frame = CanvasFrame(controller, selectGestures)

    // What a finished gesture leaves behind (GestureCommit); this view decides what one starts and follows it.
    private val commits =
        GestureCommit(
            controller,
            selectGestures,
            measurer,
            liveInk,
            object : GestureCommit.Host {
                override fun newElementId(): String = this@CanvasView.newElementId()

                override fun toolMode(): String = toolMode

                override fun followLink(link: ElementLink) = events.onFollowLink(this@CanvasView, link)
            },
        )

    // What a touch starts (GestureStart); this view follows it and hands it to commits when it ends.
    private val starts =
        GestureStart(
            { controller.state },
            selectGestures,
            object : GestureStart.Host {
                override fun toolMode(): String = toolMode

                override fun minimapLayout(): MinimapLayout? = if (minimap.isShowing()) minimap.layout() else null

                override fun showMinimap() = minimap.show()

                override fun centerViewOn(world: Point) = this@CanvasView.centerViewOn(world)

                override fun beginStroke() = liveInk.beginStroke(controller.state.elements, controller.state.transform)

                override fun eraseAt(
                    erase: CanvasGesture.Erase,
                    world: Point,
                ) = this@CanvasView.eraseAt(erase, world)
            },
        )

    private var toolMode = CanvasTools.SELECT
    private val isPencil: Boolean get() = toolMode == CanvasTools.DRAW
    private var gesture: CanvasGesture? = null
    private var isFitPending = false
    private val arbiter = TouchArbiter()

    // Screen-space touch positions for the current gesture.
    private val downTouch = PointF()
    private val lastTouch = PointF()
    private val dragCurrent = PointF()

    // The frame as it looked when a pan began, blitted while it drags instead of repainting (PanPreview).
    private val panPreview = PanPreview(renderer)

    // When the minimap shows, where it sits, and what it draws (Minimap).
    private val minimap = Minimap(this) { controller.state }

    private val scaleGestureDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val focal = toWorld(detector.focusX, detector.focusY)
                    controller.setViewport(
                        CanvasCore.zoomTo(controller.state, detector.scaleFactor.toDouble(), focal.x, focal.y).transform,
                    )
                    minimap.show()
                    return true
                }
            },
        )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registry.attach(this)
        controller.republish()
    }

    // Closing the plugin hides the canvas before the note takes the pen back, so the pen is released right then.
    override fun onVisibilityChanged(
        changedView: View,
        visibility: Int,
    ) {
        super.onVisibilityChanged(changedView, visibility)
        syncPen()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncPen()
    }

    // The firmware drops the pen whenever another window takes focus, so it is claimed again on every return.
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) syncPen()
    }

    /** The pencil's firmware ink while the canvas shows; the pen goes back to the note the moment it doesn't. */
    private fun syncPen() {
        if (isShown && windowVisibility == VISIBLE) liveInk.update(isPencil) else liveInk.release()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        minimap.forget()
        liveInk.release()
        registry.detach(this)
    }

    /** Sets the active tool, a [CanvasTools] id. Unknown ids behave as select. */
    fun setToolMode(mode: String) {
        if (mode == toolMode) return
        toolMode = mode
        liveInk.update(isPencil)
    }

    /** The pen the note writes with, set back as the canvas gives the pen back; null when unknown. */
    internal fun setNotePen(pen: FirmwarePen?) {
        liveInk.notePen = pen
    }

    /**
     * The file the canvas shown was loaded from, or saved under since: the one
     * file it may be saved to (see `saveCanvas`). Null until a canvas is shown.
     */
    @Volatile
    var heldPath: String? = null
        private set

    /** Shows the canvas loaded from [path]: its [elements], and the file it may from now on be saved to. */
    fun show(
        path: String,
        elements: List<Element>,
    ) {
        heldPath = path
        setElements(elements)
    }

    /** The canvas shown is now kept at [path] (the scratch canvas, given its own id as it goes into a note). */
    fun rebind(path: String?) {
        heldPath = path
    }

    /** Replaces the canvas content, restarts the undo history from it, and frames it (FR13). */
    private fun setElements(elements: List<Element>) {
        controller.load(elements)
        // Said again even when it reads the same as the last canvas, so the screen always hears what a load left it with.
        controller.republish()
        events.onLoaded(this, elements.isNotEmpty())
        fitToContent()
    }

    /** A snapshot of the current canvas state, e.g. for `saveCanvas`. */
    fun getState(): CanvasState = controller.state

    /** The note thumbnail of [elements] (FR12), in true colour; off the UI thread, so with a renderer of its own. */
    fun renderThumbnailBitmap(elements: List<Element>): Bitmap = CanvasRenderer(images = images).renderThumbnail(elements)

    /** Adds [image] in the middle of what the view shows, selected (FR22). */
    fun insertImage(image: ImageData) =
        controller.insertImage(image, controller.state.transform.visibleRect(width.toDouble(), height.toDouble()))

    /** Frames all content in the view. */
    fun zoomToFit() = fitToContent()

    /** Returns to 100% zoom about the view's center. */
    fun zoomTo100() {
        val center = toWorld(width / 2f, height / 2f)
        controller.setViewport(CanvasCore.zoomTo(controller.state, 1.0 / controller.state.zoom, center.x, center.y).transform)
    }

    /** Groups what is selected (FR7); logged, since nothing else says what the command found to group. */
    fun groupSelected() {
        Log.d(LOG_TAG, "group selected=${controller.selectedIds.size}")
        controller.groupSelected()
    }

    fun ungroupSelected() {
        Log.d(LOG_TAG, "ungroup selected=${controller.selectedIds.size}")
        controller.ungroupSelected()
    }

    /** Duplicates the selected element a little down and to the right (FR18). */
    fun duplicateSelected() = controller.duplicateSelected(DUPLICATE_OFFSET_PX / controller.state.zoom)

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
        controller.setViewport(ViewTransforms.fitToView(controller.state.elements, width.toDouble(), height.toDouble(), FIT_PADDING_PX))
    }

    /** Opens the JS keyboard editor over [target]'s text (FR6/FR24). */
    private fun requestTextEditor(target: CanvasController.EditTarget) {
        val element = controller.state.elements.find { it.id == target.elementId } ?: return
        val density = resources.displayMetrics.density.toDouble()
        events.onEditText(this, TextEditRequest.of(element, target, controller.state.transform, measurer, density))
    }

    private fun toWorld(
        screenX: Float,
        screenY: Float,
    ): Point {
        val state = controller.state
        return Point(state.viewportX + screenX / state.zoom, state.viewportY + screenY / state.zoom)
    }

    private fun sampleAt(
        screenX: Float,
        screenY: Float,
        pressure: Float,
    ): StrokePoint {
        val world = toWorld(screenX, screenY)
        return StrokePoint(world.x, world.y, pressure.coerceIn(0f, 1f).toDouble())
    }

    private fun distanceFromDown(
        x: Float,
        y: Float,
    ): Double = hypot((x - downTouch.x).toDouble(), (y - downTouch.y).toDouble())

    private fun newElementId(): String = "el-" + System.currentTimeMillis() + "-" + (0..9999).random()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // One coarse event per touch, never per sample (NFR5): it only tells the UI the canvas was touched.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) events.onTouched(this)
        // Pinch-zoom is a two-finger gesture; a palm beside the drawing pen must not zoom.
        if (!arbiter.isPenActive) scaleGestureDetector.onTouchEvent(event)
        val inputs =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> arbiter.down(MotionSamples.contactAt(event, event.actionIndex))
                MotionEvent.ACTION_MOVE -> {
                    addHistoricalSamples(event)
                    listOfNotNull(arbiter.move(MotionSamples.contacts(event)))
                }
                MotionEvent.ACTION_POINTER_UP ->
                    listOfNotNull(
                        arbiter.up(MotionSamples.contactAt(event, event.actionIndex), isLastContact = false),
                    )
                MotionEvent.ACTION_UP -> listOfNotNull(arbiter.up(MotionSamples.contactAt(event, event.actionIndex), isLastContact = true))
                MotionEvent.ACTION_CANCEL -> listOfNotNull(arbiter.cancel())
                else -> emptyList()
            }
        inputs.forEach(::handleInput)
        return true
    }

    /** The pen's batched positions between moves, for the pencil's smooth line. */
    private fun addHistoricalSamples(event: MotionEvent) {
        val freehand = gesture as? CanvasGesture.Freehand ?: return
        val pointerId = arbiter.followedId ?: return
        MotionSamples.history(event, pointerId) { x, y, pressure -> freehand.samples += sampleAt(x, y, pressure) }
    }

    private fun handleInput(input: PointerInput) {
        when (input) {
            is PointerInput.Start -> handleStart(input.contact)
            is PointerInput.Move -> handleMove(input.x, input.y, input.pressure)
            is PointerInput.End -> handleEnd(input.x, input.y)
            PointerInput.Abandon -> abandonGesture()
        }
    }

    private fun handleStart(contact: Contact) {
        if (contact.isPen) liveInk.penDown()
        downTouch.set(contact.x, contact.y)
        lastTouch.set(contact.x, contact.y)
        dragCurrent.set(contact.x, contact.y)
        gesture = starts.at(contact, toWorld(contact.x, contact.y))
        if (gesture == CanvasGesture.Pan) panPreview.take(controller.state, width, height)
        Log.d(
            LOG_TAG,
            "touch start pen=${contact.isPen} eraser=${contact.isEraser} tool=$toolMode gesture=${gesture?.javaClass?.simpleName}",
        )
    }

    /** Moves the view so [world] sits in the middle of it; the zoom is untouched. */
    private fun centerViewOn(world: Point) {
        val zoom = controller.state.zoom
        controller.setViewport(
            ViewTransform(viewportX = world.x - width / (2 * zoom), viewportY = world.y - height / (2 * zoom), zoom = zoom),
        )
    }

    /**
     * Drops the gesture in progress: a cancelled stream, or a second finger starting a pinch. A resting palm starts
     * nothing, so its cancel (the system sends one between words) redraws nothing. A pencil stroke is kept as drawn.
     */
    private fun abandonGesture() {
        val abandoned = gesture ?: return
        val redraw = needsRedraw()
        gesture = null
        if (abandoned is CanvasGesture.Freehand) commits.commitStroke(abandoned) else liveInk.wipe()
        if (minimap.isShowing()) minimap.hideSoon()
        if (redraw) invalidate()
    }

    /** Marks everything under [world] for the eraser; it all goes, as one undo step, when the pen lifts (FR20). */
    private fun eraseAt(
        erase: CanvasGesture.Erase,
        world: Point,
    ) {
        CanvasCore.hitsAt(world.x, world.y, controller.state.elements).mapTo(erase.ids) { it.id }
        invalidate()
    }

    private fun handleMove(
        x: Float,
        y: Float,
        pressure: Float,
    ) {
        if (scaleGestureDetector.isInProgress) return
        val state = controller.state
        val dx = (x - lastTouch.x) / state.zoom
        val dy = (y - lastTouch.y) / state.zoom
        when (val active = gesture) {
            CanvasGesture.Pan -> {
                controller.setViewport(CanvasCore.panBy(state, dx, dy).transform)
                // Past tap distance only, so a plain tap never flashes the minimap (an e-ink repaint).
                if (distanceFromDown(x, y) > GestureCommit.TAP_SLOP_PX) minimap.show()
            }
            is CanvasGesture.MinimapDrag -> {
                val world = active.layout.worldAt(x.toDouble(), y.toDouble())
                centerViewOn(Point(world.x + active.grabX, world.y + active.grabY))
                // It has to stay up for as long as it is being dragged by.
                minimap.show()
            }
            is CanvasGesture.Move -> gesture = active.copy(dx = active.dx + dx, dy = active.dy + dy)
            is CanvasGesture.Freehand -> active.samples += sampleAt(x, y, pressure)
            is CanvasGesture.Erase -> eraseAt(active, toWorld(x, y))
            CanvasGesture.PlaceText, null -> Unit
            // Shapes, handles and row edges preview from the pointer.
            else -> dragCurrent.set(x, y)
        }
        if (needsRedraw()) invalidate()
        lastTouch.set(x, y)
    }

    private fun handleEnd(
        x: Float,
        y: Float,
    ) {
        val totalDist = distanceFromDown(x, y)
        val finished = gesture
        val redraw = needsRedraw()
        commits.commit(finished, toWorld(downTouch.x, downTouch.y), toWorld(x, y), totalDist)
        // Whatever else the pen did (the eraser end, with the pencil) leaves no firmware ink behind. A touch that
        // started nothing (a palm resting while writing) leaves it alone: wiping there erased strokes the canvas only
        // redraws once the pen rests, which in a light colour's pale gray looked like the writing had vanished.
        if (finished != null && finished !is CanvasGesture.Freehand) liveInk.wipe()
        gesture = null
        if (minimap.isShowing()) minimap.hideSoon()
        if (redraw) invalidate()
    }

    /**
     * A gesture redraws the canvas as it moves and when it ends, except the pencil's while the firmware inks it: that
     * stroke already shows, and a redraw would repaint the panel over the ink of the next one, still being written.
     */
    private fun needsRedraw(): Boolean = gesture != null && (gesture !is CanvasGesture.Freehand || liveInk.drawsLiveStroke)

    /** The minimap box a drag by it was grabbed at, so only its viewport rectangle moves under the finger. */
    private fun draggingMinimap(): MinimapLayout? = (gesture as? CanvasGesture.MinimapDrag)?.layout

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val transform = controller.state.transform
        val freehand = gesture as? CanvasGesture.Freehand
        if (freehand != null && liveInk.drawLive(canvas, frame.liveStroke(freehand), transform)) return
        if (gesture == CanvasGesture.Pan && panPreview.drawUnder(canvas, transform)) {
            minimap.draw(canvas, draggingMinimap())
            return
        }
        renderer.drawBackground(canvas, width.toFloat(), height.toFloat())
        val elements = frame.elements(gesture, toWorld(dragCurrent.x, dragCurrent.y))
        renderer.drawElements(canvas, elements, transform, StylePalette.EINK, frame.hiddenText())
        renderer.drawSelection(canvas, elements, controller.selectedIds, transform)
        renderer.drawLinkGlyphs(canvas, elements, transform)
        if (gesture == CanvasGesture.DrawShape) renderer.drawDragPreview(canvas, toolMode, downTouch, dragCurrent, transform.zoom)
        if (gesture == CanvasGesture.Marquee) renderer.drawMarquee(canvas, downTouch, dragCurrent)
        minimap.draw(canvas, draggingMinimap())
    }

    private companion object {
        // Generous, so fitted content clears the floating toolbar at the bottom.
        const val FIT_PADDING_PX = 96.0

        const val LOG_TAG = "SnCanvas"

        // How far a duplicate lands from its original, in screen pixels.
        const val DUPLICATE_OFFSET_PX = 16.0
    }
}
