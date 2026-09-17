package com.sncanvas.canvas

/**
 * The action-bar and style-panel operations on existing elements (FR18/FR19):
 * z-order, duplicate and restyle. Like [CanvasCore], each takes a
 * [CanvasState] and returns a new one, and an unknown id is a no-op.
 */
object CanvasActions {
    /** Element [id] moved to the top of the z-order: drawn last, hit first. */
    fun bringToFront(
        state: CanvasState,
        id: String,
    ): CanvasState {
        val element = state.elements.find { it.id == id } ?: return state
        return state.copy(elements = state.elements.filterNot { it.id == id } + element)
    }

    /** Element [id] moved to the bottom of the z-order. */
    fun sendToBack(
        state: CanvasState,
        id: String,
    ): CanvasState {
        val element = state.elements.find { it.id == id } ?: return state
        return state.copy(elements = listOf(element) + state.elements.filterNot { it.id == id })
    }

    /**
     * A copy of element [id] under [newId], shifted by [offset] world units on
     * both axes and placed on top. A duplicated connector keeps the route it has
     * but not its bindings, so it doesn't snap back onto the original's shapes.
     */
    fun duplicate(
        state: CanvasState,
        id: String,
        newId: String,
        offset: Double,
    ): CanvasState {
        val original = state.elements.find { it.id == id } ?: return state
        val copy =
            if (original.hasEndpoints()) {
                val (start, end) = CanvasCore.resolveArrowEndpoints(original, state.elements)
                original.copy(
                    id = newId,
                    startX = start.x + offset,
                    startY = start.y + offset,
                    endX = end.x + offset,
                    endY = end.y + offset,
                    startElementId = null,
                    endElementId = null,
                )
            } else {
                original.copy(id = newId, x = original.x + offset, y = original.y + offset)
            }
        return state.copy(elements = state.elements + copy)
    }

    /**
     * Every element in [ids] made one group under [groupId] (FR7), so they are
     * selected and moved together from now on. An element already in a group
     * joins this one, and so does the rest of its old group: groups do not nest,
     * and leaving half a group behind would break the half that stayed.
     */
    fun group(
        state: CanvasState,
        ids: Set<String>,
        groupId: String,
    ): CanvasState {
        val joining = ids + groupsOf(state, ids)
        return state.copy(elements = state.elements.map { if (it.id in joining) it.copy(groupId = groupId) else it })
    }

    /** Every element in [ids], and the rest of any group they belong to, left on its own again. */
    fun ungroup(
        state: CanvasState,
        ids: Set<String>,
    ): CanvasState {
        val leaving = ids + groupsOf(state, ids)
        return state.copy(elements = state.elements.map { if (it.id in leaving) it.copy(groupId = null) else it })
    }

    /** Every element sharing a group with one of [ids]; empty when none of them is in a group. */
    fun groupsOf(
        state: CanvasState,
        ids: Set<String>,
    ): Set<String> {
        val groups =
            state.elements
                .filter { it.id in ids }
                .mapNotNull { it.groupId }
                .toSet()
        if (groups.isEmpty()) return emptySet()
        return state.elements
            .filter { it.groupId in groups }
            .map { it.id }
            .toSet()
    }

    /** Element [id] with [style]. */
    fun restyle(
        state: CanvasState,
        id: String,
        style: ShapeStyle,
    ): CanvasState = state.copy(elements = state.elements.map { if (it.id == id) it.copy(style = style) else it })
}
