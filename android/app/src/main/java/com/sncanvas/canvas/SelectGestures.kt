package com.sncanvas.canvas

/**
 * The select tool's drags (FR7/FR9/FR24): which one a touch on the selected
 * element starts, and the edit each makes. A handle wins over a table's row
 * edge, which wins over the element's body, which wins over panning. The live
 * preview and the commit on release both come from [edit], so what the user
 * sees while dragging is what they get. Pure: [CanvasView] feeds it world
 * points, [state] and [selected] read the controller's current values (not a
 * snapshot: a gesture spans many calls), and it draws or commits what [edit]
 * returns — no [CanvasController] reference, so nothing here can command it.
 */
internal class SelectGestures(
    private val state: () -> CanvasState,
    private val selection: () -> List<Element>,
    private val fitted: (CanvasState, String) -> CanvasState,
    private val measurer: TextMeasurer,
) {
    /**
     * The drag a select-tool touch at [world] starts; [tolerance] is the
     * handles' reach in world units. Handles belong to a single selected
     * element: with several selected a touch on any of them drags the lot, and
     * a touch away from them all grabs nothing.
     */
    fun startAt(
        world: Point,
        tolerance: Double,
    ): CanvasGesture {
        val selected = selection()
        val element = selected.singleOrNull() ?: return dragSelectionAt(selected, world)
        return when (val handle = CanvasCore.handleAt(state(), element.id, world.x, world.y, tolerance)) {
            is HandleTarget.CornerHandle -> CanvasGesture.Resize(handle.corner)
            is HandleTarget.EndpointHandle -> CanvasGesture.DragEndpoint(handle.which)
            HandleTarget.RotateHandle -> CanvasGesture.Rotate
            null -> bodyGestureAt(element, world, tolerance)
        }
    }

    /**
     * The edit [active] makes to element [id] with the pointer at [pointer], or
     * null if it edits nothing. A resize refits text and stretches a table's rows.
     */
    fun edit(
        active: CanvasGesture?,
        id: String,
        pointer: Point,
    ): CanvasState? {
        val current = state()
        return when (active) {
            is CanvasGesture.Move -> CanvasCore.moveElement(current, id, active.dx, active.dy)
            is CanvasGesture.Resize -> fitted(ShapeEdits.resizeElement(current, id, active.corner, pointer.x, pointer.y), id)
            is CanvasGesture.ResizeRow -> TableEdits.dragRowEdge(current, id, active.row, pointer, measurer)
            is CanvasGesture.DragEndpoint -> {
                val target = CanvasCore.bindingTargetAt(pointer, current.elements)
                ShapeEdits.moveEndpoint(current, id, active.which, pointer, target?.id)
            }
            CanvasGesture.Rotate -> ShapeEdits.rotateElement(current, id, pointer)
            else -> null
        }
    }

    /**
     * With several selected (or none): a touch on any of them drags the lot,
     * and a touch away from them all grabs nothing, leaving the canvas to pan
     * or a selection to be dragged out.
     */
    private fun dragSelectionAt(
        selected: List<Element>,
        world: Point,
    ): CanvasGesture =
        if (selected.any { CanvasCore.hitTest(world.x, world.y, listOf(it)) != null }) CanvasGesture.Move() else CanvasGesture.Pan

    /** On the selected element itself: a table's row edge, its body (a connector's body pans), or neither. */
    private fun bodyGestureAt(
        element: Element,
        world: Point,
        tolerance: Double,
    ): CanvasGesture {
        val rowEdge = TableElements.rowEdgeAt(element, element.toLocal(world.x, world.y), tolerance * ROW_EDGE_REACH, measurer)
        val hit = CanvasCore.hitTest(world.x, world.y, state().elements)
        return when {
            rowEdge != null -> CanvasGesture.ResizeRow(rowEdge)
            hit?.id == element.id && !element.hasEndpoints() -> CanvasGesture.Move()
            else -> CanvasGesture.Pan
        }
    }

    private companion object {
        // Row edges answer within this share of the handles' reach, leaving the rest of each cell to taps that edit it.
        const val ROW_EDGE_REACH = 0.5
    }
}
