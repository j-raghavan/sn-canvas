package com.sncanvas.canvas

/**
 * What a finished gesture leaves behind: a stroke, a shape, a selection, an
 * edit, or nothing at all. [CanvasView] decides what a touch starts and follows
 * it while it moves; this is the one place that says what it did.
 *
 * Nothing here touches the Android view: the caller hands over the pointer's
 * start and end in world coordinates, and how far it travelled on screen, which
 * is what tells a tap from a drag.
 */
internal class GestureCommit(
    private val controller: CanvasController,
    private val selectGestures: SelectGestures,
    private val measurer: TextMeasurer,
    private val ink: StrokeInk,
    private val host: Host,
) {
    /** The pencil's firmware ink, as far as a committed stroke is concerned ([LiveInk] on the device). */
    interface StrokeInk {
        /** Keeps the ink already on screen while [commit] puts the stroke on the canvas under it. */
        fun keepInk(commit: () -> Unit)

        /** Takes the ink off: there is no stroke to keep. */
        fun wipe()
    }

    /** What the canvas around the gesture says: the tool in hand, ids for what it makes, and where a link leads. */
    interface Host {
        fun newElementId(): String

        /** The active tool, a [CanvasTools] id. */
        fun toolMode(): String

        /** Only the screen can follow a link, so a tap on a glyph is passed up. */
        fun followLink(link: ElementLink)
    }

    /**
     * Commits [finished], whose pointer went from [start] to [end] in world
     * coordinates, travelling [totalDist] on screen. Within [TAP_SLOP_PX] it is
     * a tap, which selects, follows a link or opens an editor rather than
     * drawing or moving anything.
     */
    fun commit(
        finished: CanvasGesture?,
        start: Point,
        end: Point,
        totalDist: Double,
    ) {
        val isTap = totalDist <= TAP_SLOP_PX
        when (finished) {
            CanvasGesture.Pan -> if (isTap) controller.select(CanvasCore.hitTest(end.x, end.y, controller.state.elements)?.id)
            CanvasGesture.Marquee -> selectInMarquee(start, end)
            is CanvasGesture.FollowLink -> followLink(finished.elementId, isTap)
            CanvasGesture.DrawShape -> commitDrawnShape(start, end, totalDist)
            CanvasGesture.PlaceText -> if (isTap) controller.placeText(host.toolMode(), start)
            is CanvasGesture.Freehand -> commitStroke(finished)
            is CanvasGesture.Erase -> controller.erase(finished.ids)
            // The view moved as it was dragged; there is nothing to commit, and panning is not an undo step.
            is CanvasGesture.MinimapDrag -> Unit
            is CanvasGesture.Move, is CanvasGesture.ResizeRow -> finishDrag(finished, end, isTap)
            is CanvasGesture.Resize, is CanvasGesture.DragEndpoint, CanvasGesture.Rotate -> commitGestureEdit(finished, end)
            null -> Unit
        }
    }

    /** Commits the pencil's stroke, keeping its firmware ink: that is what the user just drew. Too short for a stroke, it goes. */
    fun commitStroke(freehand: CanvasGesture.Freehand) {
        val stroke = StrokeElements.fromSamples(host.newElementId(), freehand.samples, controller.state.zoom)
        if (stroke == null) ink.wipe() else ink.keepInk { controller.insert(stroke) }
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
        if (isTap) host.followLink(link)
    }

    /** Everything the selection rectangle covered, selected together (FR7); one that covered nothing clears the selection. */
    private fun selectInMarquee(
        start: Point,
        end: Point,
    ) {
        val rect =
            WorldRect(
                left = minOf(start.x, end.x),
                top = minOf(start.y, end.y),
                right = maxOf(start.x, end.x),
                bottom = maxOf(start.y, end.y),
            )
        controller.selectAll(CanvasCore.elementsIn(rect, controller.state.elements).map { it.id }.toSet())
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
        start: Point,
        end: Point,
        totalDist: Double,
    ) {
        if (totalDist <= MIN_SHAPE_DRAG_PX) return
        val id = host.newElementId()
        val tool = host.toolMode()
        controller.insert(
            when {
                tool == CanvasTools.TABLE -> TableElements.create(id, start, end)
                CanvasTools.isConnector(tool) -> CanvasCore.connectorFromDrag(id, tool, start, end, controller.state.elements)
                else -> CanvasCore.boxFromDrag(id, tool, start, end)
            },
        )
    }

    companion object {
        /** How far a pointer may travel and still count as a tap, in screen pixels. */
        const val TAP_SLOP_PX = 12.0

        /** A drag shorter than this leaves no shape: a tap with a shape tool selected is not a zero-sized rectangle. */
        const val MIN_SHAPE_DRAG_PX = 8.0
    }
}
