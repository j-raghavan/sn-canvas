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

    private var toolMode = CanvasTools.SELECT
    private val isPencil: Boolean get() = toolMode == CanvasTools.DRAW
    private var gesture: CanvasGesture? = null
    private var isFitPending = false
    private val arbiter = TouchArbiter()

    // Screen-space touch positions for the current gesture.
    private val downTouch = PointF()
    private val lastTouch = PointF()
    private val dragCurrent = PointF()

    // A cached frame taken when a pan begins, blitted with a translate/scale while it's dragged (NFR1/NFR3)
    // instead of repainting every element on each move sample: fewer full-surface e-ink repaints while panning.
    // Reused across pans, as LiveInk's stroke backdrop already is; self-heals on a size change (renderSnapshot).
    private var panSnapshot: Bitmap? = null
    private var panSnapshotTransform: ViewTransform? = null

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
                    controller.setViewport(
                        CanvasCore.zoomTo(controller.state, detector.scaleFactor.toDouble(), focal.x, focal.y).transform,
                    )
                    showMinimap()
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
        removeCallbacks(hideMinimapRunnable)
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

    /** Replaces the canvas content (after `loadCanvas`), restarts the undo history from it, and frames it (FR13). */
    fun setElements(elements: List<Element>) {
        controller.load(elements)
        // Said again even when it reads the same as the last canvas, so the screen always hears what a load left it with.
        controller.republish()
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
        gesture = gestureFor(contact)
        if (gesture == CanvasGesture.Pan) beginPanSnapshot()
        Log.d(
            LOG_TAG,
            "touch start pen=${contact.isPen} eraser=${contact.isEraser} tool=$toolMode gesture=${gesture?.javaClass?.simpleName}",
        )
    }

    /**
     * A linked element's glyph under [world], if the select tool is in hand: a
     * tap on it follows the link. Only the select tool, so the glyph never
     * swallows a stroke from a drawing tool.
     */
    private fun linkGlyphAt(world: Point): Element? {
        if (CanvasTools.usesPen(toolMode)) return null
        val zoom = controller.state.zoom
        return CanvasCore.linkGlyphAt(world.x, world.y, controller.state.elements, zoom, LINK_GLYPH_HIT_PX / zoom)
    }

    /** The gesture [contact] starts: the controls drawn over the canvas answer first, then the tool in hand. */
    private fun gestureFor(contact: Contact): CanvasGesture? {
        val world = toWorld(contact.x, contact.y)
        return controlGestureAt(contact, world) ?: toolGestureAt(contact, world)
    }

    /** What a touch on something drawn over the canvas starts: the minimap, or a linked element's glyph. */
    private fun controlGestureAt(
        contact: Contact,
        world: Point,
    ): CanvasGesture? {
        val minimap = if (isMinimapVisible && navigatesByMinimap(contact)) minimapLayout() else null
        if (minimap != null && minimap.contains(contact.x.toDouble(), contact.y.toDouble())) {
            return startMinimapDrag(minimap, contact)
        }
        return linkGlyphAt(world)?.let { linked -> CanvasGesture.FollowLink(linked.id) }
    }

    /** What the tool in hand makes of a touch at [world]. */
    private fun toolGestureAt(
        contact: Contact,
        world: Point,
    ): CanvasGesture? =
        when {
            // The pen's eraser end erases with any tool, as does the eraser tool with the pen.
            contact.isEraser || (contact.isPen && toolMode == CanvasTools.ERASER) -> CanvasGesture.Erase().also { eraseAt(it, world) }
            !CanvasTools.usesPen(toolMode) -> selectGesture(world, contact)
            // Palm rejection: every other tool works with the pen alone, as in Supernote's own notes.
            !contact.isPen -> null
            toolMode == CanvasTools.DRAW -> startFreehand(sampleAt(contact.x, contact.y, contact.pressure))
            CanvasTools.placesText(toolMode) -> CanvasGesture.PlaceText
            else -> CanvasGesture.DrawShape
        }

    /**
     * Whether [contact] navigates when it lands on the minimap. The pen goes on
     * drawing wherever it would have drawn — a stroke in that corner, moments
     * after a pan, must not be swallowed by the map — so with a pen tool in hand
     * only a finger navigates. The pen's eraser end always erases.
     */
    private fun navigatesByMinimap(contact: Contact): Boolean = !contact.isEraser && !(contact.isPen && CanvasTools.usesPen(toolMode))

    private fun minimapLayout(): MinimapLayout? = ViewTransforms.minimapLayout(controller.state, width.toDouble(), height.toDouble())

    /**
     * A touch on the minimap navigates (FR15): landing on the "you are here"
     * rectangle drags it from where it was grabbed, and landing anywhere else in
     * the box takes the view there at once, then drags on from it.
     */
    private fun startMinimapDrag(
        layout: MinimapLayout,
        contact: Contact,
    ): CanvasGesture {
        val world = layout.worldAt(contact.x.toDouble(), contact.y.toDouble())
        val grabbed = layout.holdsViewport(world)
        val center = layout.visible.let { Point((it.left + it.right) / 2, (it.top + it.bottom) / 2) }
        if (!grabbed) centerViewOn(world)
        showMinimap()
        return CanvasGesture.MinimapDrag(
            layout,
            grabX = if (grabbed) center.x - world.x else 0.0,
            grabY = if (grabbed) center.y - world.y else 0.0,
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
     * The select tool's gesture. Where it grabs nothing the pen drags a selection
     * rectangle out instead of panning (FR7): the device has no modifier key, so
     * the pen selects and the finger navigates, as everywhere else in the canvas.
     */
    private fun selectGesture(
        world: Point,
        contact: Contact,
    ): CanvasGesture {
        val gesture = selectGestures.startAt(world, HANDLE_HIT_RADIUS_PX / controller.state.zoom)
        return if (gesture == CanvasGesture.Pan && contact.isPen) CanvasGesture.Marquee else gesture
    }

    /**
     * Drops the gesture in progress: a cancelled stream, or a second finger starting a pinch. A resting palm starts
     * nothing, so its cancel (the system sends one between words) redraws nothing. A pencil stroke is kept as drawn.
     */
    private fun abandonGesture() {
        val abandoned = gesture ?: return
        val redraw = needsRedraw()
        gesture = null
        if (abandoned is CanvasGesture.Freehand) commitStroke(abandoned) else liveInk.wipe()
        if (isMinimapVisible) scheduleMinimapHide()
        if (redraw) invalidate()
    }

    /** The pencil lands: the firmware inks the stroke, or the canvas snapshots itself to draw it over. */
    private fun startFreehand(first: StrokePoint): CanvasGesture {
        liveInk.beginStroke(controller.state.elements, controller.state.transform)
        return CanvasGesture.Freehand(mutableListOf(first))
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
                if (distanceFromDown(x, y) > TAP_SLOP_PX) showMinimap()
            }
            is CanvasGesture.MinimapDrag -> {
                val world = active.layout.worldAt(x.toDouble(), y.toDouble())
                centerViewOn(Point(world.x + active.grabX, world.y + active.grabY))
                // It has to stay up for as long as it is being dragged by.
                showMinimap()
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
        commitGesture(finished, toWorld(x, y), totalDist)
        // Whatever else the pen did (the eraser end, with the pencil) leaves no firmware ink behind. A touch that
        // started nothing (a palm resting while writing) leaves it alone: wiping there erased strokes the canvas only
        // redraws once the pen rests, which in a light colour's pale gray looked like the writing had vanished.
        if (finished != null && finished !is CanvasGesture.Freehand) liveInk.wipe()
        gesture = null
        if (isMinimapVisible) scheduleMinimapHide()
        if (redraw) invalidate()
    }

    /** What [finished] leaves behind, its pointer having ended at [end] after travelling [totalDist] on screen. */
    private fun commitGesture(
        finished: CanvasGesture?,
        end: Point,
        totalDist: Double,
    ) {
        val isTap = totalDist <= TAP_SLOP_PX
        when (finished) {
            CanvasGesture.Pan -> tapSelect(end, isTap)
            CanvasGesture.Marquee -> selectInMarquee(end)
            is CanvasGesture.FollowLink -> followLink(finished.elementId, isTap)
            CanvasGesture.DrawShape -> commitDrawnShape(end, totalDist)
            CanvasGesture.PlaceText -> placeTextOnTap(isTap)
            is CanvasGesture.Freehand -> commitStroke(finished)
            is CanvasGesture.Erase -> controller.erase(finished.ids)
            // The view moved as it was dragged; there is nothing to commit, and panning is not an undo step.
            is CanvasGesture.MinimapDrag -> Unit
            is CanvasGesture.Move, is CanvasGesture.ResizeRow -> finishDrag(finished, end, isTap)
            is CanvasGesture.Resize, is CanvasGesture.DragEndpoint, CanvasGesture.Rotate -> commitGestureEdit(finished, end)
            null -> Unit
        }
    }

    /**
     * A gesture redraws the canvas as it moves and when it ends, except the pencil's while the firmware inks it: that
     * stroke already shows, and a redraw would repaint the panel over the ink of the next one, still being written.
     */
    private fun needsRedraw(): Boolean = gesture != null && (gesture !is CanvasGesture.Freehand || liveInk.drawsLiveStroke)

    /** Caches the current frame so [onDraw] can blit it, cheaply transformed, while the pan being started drags (NFR1/NFR3). */
    private fun beginPanSnapshot() {
        if (width == 0 || height == 0) return
        panSnapshotTransform = controller.state.transform
        panSnapshot = renderer.renderSnapshot(controller.state.elements, controller.state.transform, width, height, panSnapshot)
    }

    /**
     * The fast pass while panning (NFR1): blits [panSnapshot] under a matrix that maps the transform it was taken at
     * onto [transform], instead of repainting every element. False if there's no snapshot to blit, so the caller
     * falls back to a full repaint.
     */
    private fun drawPanPreview(
        canvas: Canvas,
        transform: ViewTransform,
    ): Boolean {
        val base = panSnapshotTransform
        val snapshot = panSnapshot
        if (base == null || snapshot == null) return false
        val scale = (transform.zoom / base.zoom).toFloat()
        val dx = ((base.viewportX - transform.viewportX) * transform.zoom).toFloat()
        val dy = ((base.viewportY - transform.viewportY) * transform.zoom).toFloat()
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        canvas.drawBitmap(snapshot, 0f, 0f, null)
        canvas.restore()
        drawMinimap(canvas)
        return true
    }

    /**
     * The minimap, while it shows. A drag by it keeps the box and transform it
     * was grabbed at, so only its viewport rectangle moves under the finger.
     */
    private fun drawMinimap(canvas: Canvas) {
        if (!isMinimapVisible) return
        val layout = (gesture as? CanvasGesture.MinimapDrag)?.layout ?: minimapLayout() ?: return
        val state = controller.state
        minimapRenderer.draw(canvas, state.elements, state.transform.visibleRect(width.toDouble(), height.toDouble()), layout)
    }

    /** Commits the pencil's stroke, keeping its firmware ink: that is what the user just drew. Too short for a stroke, it goes. */
    private fun commitStroke(freehand: CanvasGesture.Freehand) {
        val stroke = StrokeElements.fromSamples(newElementId(), freehand.samples, controller.state.zoom)
        if (stroke == null) liveInk.wipe() else liveInk.keepInk { controller.insert(stroke) }
    }

    private fun tapSelect(
        at: Point,
        isTap: Boolean,
    ) {
        if (isTap) controller.select(CanvasCore.hitTest(at.x, at.y, controller.state.elements)?.id)
    }

    /** A tap that stayed on the glyph follows its link; a drag off it was the user changing their mind. */
    private fun followLink(
        elementId: String,
        isTap: Boolean,
    ) {
        val link =
            controller.state.elements
                .find { it.id == elementId }
                ?.link ?: return
        if (isTap) events.onFollowLink(this, link)
    }

    /** Everything the selection rectangle covered, selected together (FR7); one that covered nothing clears the selection. */
    private fun selectInMarquee(end: Point) {
        val start = toWorld(downTouch.x, downTouch.y)
        val rect =
            WorldRect(
                left = minOf(start.x, end.x),
                top = minOf(start.y, end.y),
                right = maxOf(start.x, end.x),
                bottom = maxOf(start.y, end.y),
            )
        controller.selectAll(CanvasCore.elementsIn(rect, controller.state.elements).map { it.id }.toSet())
    }

    private fun placeTextOnTap(isTap: Boolean) {
        if (isTap) controller.placeText(toolMode, toWorld(downTouch.x, downTouch.y))
    }

    /** Within tap distance, a drag on the selected element or a table's row edge is a tap: on text or a cell, it opens the editor. */
    private fun finishDrag(
        drag: CanvasGesture,
        end: Point,
        isTap: Boolean,
    ) {
        val move = drag as? CanvasGesture.Move
        when {
            isTap || drag == CanvasGesture.Move() -> editTappedText(end)
            move != null -> controller.moveSelected(move.dx, move.dy)
            else -> commitGestureEdit(drag, end)
        }
    }

    /** A tap on the selected text box, note or table cell opens the keyboard editor on it (FR6/FR24). */
    private fun editTappedText(at: Point) {
        val element = controller.selected ?: return
        val cell = if (element.table != null) TableElements.cellAt(element, element.toLocal(at.x, at.y), measurer) else null
        when {
            TextElements.isEditable(element) -> controller.beginEdit(CanvasController.EditTarget(element.id))
            cell != null -> controller.beginEdit(CanvasController.EditTarget(element.id, cell))
        }
    }

    private fun commitGestureEdit(
        finished: CanvasGesture,
        pointer: Point,
    ) {
        val id = controller.selected?.id ?: return
        selectGestures.edit(finished, id, pointer)?.let { controller.commit(it.elements) }
    }

    private fun commitDrawnShape(
        end: Point,
        totalDist: Double,
    ) {
        if (totalDist <= MIN_SHAPE_DRAG_PX) return
        val start = toWorld(downTouch.x, downTouch.y)
        val id = newElementId()
        controller.insert(
            when {
                toolMode == CanvasTools.TABLE -> TableElements.create(id, start, end)
                CanvasTools.isConnector(toolMode) -> CanvasCore.connectorFromDrag(id, toolMode, start, end, controller.state.elements)
                else -> CanvasCore.boxFromDrag(id, toolMode, start, end)
            },
        )
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

    /** The elements to draw this frame: any in-progress edit previewed, and what the eraser is about to take faded. */
    private fun elementsForDrawing(): List<Element> {
        val erasing = (gesture as? CanvasGesture.Erase)?.ids.orEmpty()
        return (previewEdit()?.elements ?: controller.state.elements).map {
            if (it.id in erasing) it.copy(style = it.style.copy(opacity = ERASE_PREVIEW_OPACITY)) else it
        }
    }

    /** The canvas as the gesture in progress would leave it: a move takes the whole selection, every other edit one element. */
    private fun previewEdit(): CanvasState? {
        val move = gesture as? CanvasGesture.Move
        return if (move != null) {
            CanvasCore.moveElements(controller.state, controller.selectedIds, move.dx, move.dy)
        } else {
            controller.selected?.id?.let { selectGestures.edit(gesture, it, toWorld(dragCurrent.x, dragCurrent.y)) }
        }
    }

    /** [controller.editing] as the painter's own [ElementPainter.HiddenText], so it never needs [CanvasController]'s type. */
    private fun hiddenText(): ElementPainter.HiddenText? = controller.editing?.let { ElementPainter.HiddenText(it.elementId, it.cellIndex) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val transform = controller.state.transform
        val freehand = gesture as? CanvasGesture.Freehand
        if (freehand != null && liveInk.drawLive(canvas, liveStroke(freehand), transform)) return
        if (gesture == CanvasGesture.Pan && drawPanPreview(canvas, transform)) return
        renderer.drawBackground(canvas, width.toFloat(), height.toFloat())
        val elements = elementsForDrawing()
        renderer.drawElements(canvas, elements, transform, StylePalette.EINK, hiddenText())
        renderer.drawSelection(canvas, elements, controller.selectedIds, transform)
        renderer.drawLinkGlyphs(canvas, elements, transform)
        if (gesture == CanvasGesture.DrawShape) renderer.drawDragPreview(canvas, toolMode, downTouch, dragCurrent, transform.zoom)
        if (gesture == CanvasGesture.Marquee) renderer.drawMarquee(canvas, downTouch, dragCurrent)
        drawMinimap(canvas)
    }

    /** The stroke the pencil is drawing, in the style new elements get. */
    private fun liveStroke(freehand: CanvasGesture.Freehand): Element? =
        StrokeElements.fromSamples(LIVE_STROKE_ID, freehand.samples, controller.state.zoom)?.copy(style = controller.currentStyle)

    private companion object {
        const val TAP_SLOP_PX = 12f
        const val MIN_SHAPE_DRAG_PX = 8f
        const val HANDLE_HIT_RADIUS_PX = 28f

        // A link glyph's tap radius; a touch this close to it follows the link (FR7).
        const val LINK_GLYPH_HIT_PX = 24f

        // Long enough after a pan to reach the minimap and take hold of it, since that is the only way it comes up.
        const val MINIMAP_LINGER_MS = 3000L

        // Generous, so fitted content clears the floating toolbar at the bottom.
        const val FIT_PADDING_PX = 96.0

        const val LOG_TAG = "SnCanvas"

        // How far a duplicate lands from its original, in screen pixels.
        const val DUPLICATE_OFFSET_PX = 16.0

        // What the eraser is about to take shows at this opacity until the pen lifts.
        const val ERASE_PREVIEW_OPACITY = 0.2

        const val LIVE_STROKE_ID = "live-stroke"
    }
}
