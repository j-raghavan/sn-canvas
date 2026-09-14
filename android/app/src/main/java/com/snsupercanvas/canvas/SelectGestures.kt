package com.snsupercanvas.canvas

/**
 * The select tool's drags (FR7/FR9/FR24): which one a touch on the selected
 * element starts, and the edit each makes. A handle wins over a table's row
 * edge, which wins over the element's body, which wins over panning. The live
 * preview and the commit on release both come from [edit], so what the user
 * sees while dragging is what they get. Pure: [SuperCanvasView] feeds it world
 * points and draws or commits what it returns.
 */
internal class SelectGestures(
    private val controller: CanvasController,
    private val measurer: TextMeasurer,
) {
    /** The drag a select-tool touch at [world] starts; [tolerance] is the handles' reach in world units. */
    fun startAt(
        world: Point,
        tolerance: Double,
    ): CanvasGesture {
        val element = controller.selected ?: return CanvasGesture.Pan
        return when (val handle = SuperCanvasCore.handleAt(controller.state, element.id, world.x, world.y, tolerance)) {
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
        val state = controller.state
        return when (active) {
            is CanvasGesture.Move -> SuperCanvasCore.moveElement(state, id, active.dx, active.dy)
            is CanvasGesture.Resize -> controller.fitted(SuperCanvasCore.resizeElement(state, id, active.corner, pointer.x, pointer.y), id)
            is CanvasGesture.ResizeRow -> TableEdits.dragRowEdge(state, id, active.row, pointer, measurer)
            is CanvasGesture.DragEndpoint -> {
                val target = SuperCanvasCore.bindingTargetAt(pointer, state.elements)
                SuperCanvasCore.moveEndpoint(state, id, active.which, pointer, target?.id)
            }
            CanvasGesture.Rotate -> SuperCanvasCore.rotateElement(state, id, pointer)
            else -> null
        }
    }

    /** On the selected element itself: a table's row edge, its body (a connector's body pans), or neither. */
    private fun bodyGestureAt(
        element: Element,
        world: Point,
        tolerance: Double,
    ): CanvasGesture {
        val rowEdge = TableElements.rowEdgeAt(element, element.toLocal(world.x, world.y), tolerance * ROW_EDGE_REACH, measurer)
        val hit = SuperCanvasCore.hitTest(world.x, world.y, controller.state.elements)
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
