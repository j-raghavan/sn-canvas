package com.sncanvas.canvas

/** The one gesture a touch sequence performs on [CanvasView]: decided when it starts, finished when it ends. */
internal sealed interface CanvasGesture {
    data object Pan : CanvasGesture

    data object DrawShape : CanvasGesture

    data object Rotate : CanvasGesture

    data object PlaceText : CanvasGesture

    /** Dragging a selection rectangle out over the canvas with the pen (FR7); what it covers is selected on release. */
    data object Marquee : CanvasGesture

    /** Dragging the selected element's body; the world-space offset accumulates until release. */
    data class Move(
        val dx: Double = 0.0,
        val dy: Double = 0.0,
    ) : CanvasGesture

    data class Resize(
        val corner: Corner,
    ) : CanvasGesture

    /** Dragging the bottom edge of a table's [row] (FR24). */
    data class ResizeRow(
        val row: Int,
    ) : CanvasGesture

    data class DragEndpoint(
        val which: Endpoint,
    ) : CanvasGesture

    /**
     * Navigating by the minimap: [layout] is held from where it was grabbed, so
     * the map does not re-fit under the finger as the view it moves travels, and
     * [grabX]/[grabY] keep the world point grabbed under the finger (zero for a
     * touch outside the viewport rectangle, which jumps the view there instead).
     */
    data class MinimapDrag(
        val layout: MinimapLayout,
        val grabX: Double,
        val grabY: Double,
    ) : CanvasGesture

    /** The pencil's world-space samples so far. */
    class Freehand(
        val samples: MutableList<StrokePoint>,
    ) : CanvasGesture

    /** The ids the eraser has passed over so far. */
    class Erase(
        val ids: MutableSet<String> = mutableSetOf(),
    ) : CanvasGesture
}
