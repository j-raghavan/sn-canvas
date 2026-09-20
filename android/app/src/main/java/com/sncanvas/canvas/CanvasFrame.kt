package com.sncanvas.canvas

/**
 * What the canvas shows this frame, as opposed to what it holds: the gesture in
 * progress previewed, what the eraser is about to take faded, the text hidden
 * under an open editor, and the stroke the pencil is drawing. [CanvasView]
 * paints what this returns through [CanvasRenderer].
 */
internal class CanvasFrame(
    private val controller: CanvasController,
    private val selectGestures: SelectGestures,
) {
    /** The elements to draw while [gesture] runs, its pointer at [pointer] in world coordinates. */
    fun elements(
        gesture: CanvasGesture?,
        pointer: Point,
    ): List<Element> {
        val erasing = (gesture as? CanvasGesture.Erase)?.ids.orEmpty()
        return (previewEdit(gesture, pointer)?.elements ?: controller.state.elements).map {
            if (it.id in erasing) it.copy(style = it.style.copy(opacity = ERASE_PREVIEW_OPACITY)) else it
        }
    }

    /** [CanvasController.editing] as the painter's own [ElementPainter.HiddenText], so it never needs the controller's type. */
    fun hiddenText(): ElementPainter.HiddenText? = controller.editing?.let { ElementPainter.HiddenText(it.elementId, it.cellIndex) }

    /** The stroke the pencil is drawing, in the style new elements get. */
    fun liveStroke(freehand: CanvasGesture.Freehand): Element? =
        StrokeElements.fromSamples(LIVE_STROKE_ID, freehand.samples, controller.state.zoom)?.copy(style = controller.currentStyle)

    /** The canvas as [gesture] would leave it: a move takes the whole selection, every other edit one element. */
    private fun previewEdit(
        gesture: CanvasGesture?,
        pointer: Point,
    ): CanvasState? {
        val move = gesture as? CanvasGesture.Move
        return if (move != null) {
            CanvasCore.moveElements(controller.state, controller.selectedIds, move.dx, move.dy)
        } else {
            controller.selected?.id?.let { selectGestures.edit(gesture, it, pointer) }
        }
    }

    private companion object {
        // What the eraser is about to take shows at this opacity until the pen lifts.
        const val ERASE_PREVIEW_OPACITY = 0.2

        const val LIVE_STROKE_ID = "live-stroke"
    }
}
