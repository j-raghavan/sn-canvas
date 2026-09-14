package com.sncanvas.canvas

/** The one gesture a touch sequence performs on [CanvasView]: decided when it starts, finished when it ends. */
internal sealed interface CanvasGesture {
    data object Pan : CanvasGesture

    data object DrawShape : CanvasGesture

    data object Rotate : CanvasGesture

    data object PlaceText : CanvasGesture

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

    /** The pencil's world-space samples so far. */
    class Freehand(
        val samples: MutableList<StrokePoint>,
    ) : CanvasGesture

    /** The ids the eraser has passed over so far. */
    class Erase(
        val ids: MutableSet<String> = mutableSetOf(),
    ) : CanvasGesture
}
