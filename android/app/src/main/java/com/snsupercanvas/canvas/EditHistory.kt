package com.snsupercanvas.canvas

/**
 * Undo/redo (FR10) as snapshots of the element list only, not the full
 * [CanvasState], so panning and zooming between edits are never themselves
 * undone. Committing after an undo discards the redo branch, as in any editor.
 */
class EditHistory(
    initial: List<Element> = emptyList(),
) {
    private val snapshots = mutableListOf(initial)
    private var index = 0

    /** Starts a fresh history at [elements], e.g. after a canvas is loaded. */
    fun reset(elements: List<Element>) {
        snapshots.clear()
        snapshots.add(elements)
        index = 0
    }

    /** Records [elements] as the newest snapshot, dropping any redo branch. */
    fun commit(elements: List<Element>) {
        snapshots.subList(index + 1, snapshots.size).clear()
        snapshots.add(elements)
        index++
    }

    /** The previous snapshot, or null when there is nothing to undo. */
    fun undo(): List<Element>? {
        if (index == 0) return null
        index--
        return snapshots[index]
    }

    /** The next snapshot, or null when there is nothing to redo. */
    fun redo(): List<Element>? {
        if (index == snapshots.lastIndex) return null
        index++
        return snapshots[index]
    }
}
