package com.sncanvas.canvas

/**
 * The canvas's command layer: the element list, the undo history, the
 * selection, the style for new elements and the text being edited. Every
 * command (from the action bar, the style panel, the text editor or a finished
 * gesture) becomes one undoable step here. Pure Kotlin: [CanvasView]
 * turns touches into calls on it and draws what it holds, and the view
 * manager routes the RN commands to it.
 */
@Suppress("TooManyFunctions") // one small method per canvas command
class CanvasController(
    private val measurer: TextMeasurer,
    private val newId: () -> String,
    private val listener: Listener,
) {
    interface Listener {
        /** Something visible changed; redraw. */
        fun onChanged()

        /** The action bar and style panel should now show [uiState] (FR18/FR19). */
        fun onUiState(uiState: CanvasUiState)

        /** Text editing began on [target]: open the keyboard editor over it (FR6/FR24). */
        fun onEditText(target: EditTarget)
    }

    /** What the text editor is editing: a text box or sticky note, or one cell of a table. */
    data class EditTarget(
        val elementId: String,
        val cellIndex: Int? = null,
    )

    var state = CanvasState(elements = emptyList(), viewportX = 0.0, viewportY = 0.0, zoom = 1.0)
        private set

    /** What is selected (FR7): one element, several dragged out together, or none. */
    var selectedIds: Set<String> = emptySet()
        private set

    var editing: EditTarget? = null
        private set

    /** The style new elements get (FR19); the pencil's live stroke draws in it too. */
    var currentStyle = ShapeStyle.DEFAULT
        private set

    private val history = EditHistory()
    private var lastUiState: CanvasUiState? = null

    /** The one selected element; null with nothing selected and with several, which resize, rotate and text editing don't apply to. */
    val selected: Element? get() = selectedIds.singleOrNull()?.let { id -> state.elements.find { it.id == id } }

    /** Every selected element, in the order they are drawn. */
    val selectedElements: List<Element> get() = state.elements.filter { it.id in selectedIds }

    /** Replaces the content (a load) and restarts the undo history from it. */
    fun load(elements: List<Element>) {
        history.reset(elements)
        editing = null
        show(elements)
    }

    /** Pans and zooms; not an edit, so no undo step. */
    fun setViewport(transform: ViewTransform) {
        state = state.copy(viewportX = transform.viewportX, viewportY = transform.viewportY, zoom = transform.zoom)
        listener.onChanged()
    }

    /** Selects one element, or nothing; a grouped one brings its group with it. */
    fun select(id: String?) {
        selectedIds = withGroups(setOfNotNull(id))
        changed()
    }

    /** Selects every element in [ids]: what a selection dragged out over them takes (FR7), groups whole. */
    fun selectAll(ids: Set<String>) {
        selectedIds = withGroups(ids)
        changed()
    }

    /** Makes one group of everything selected (FR7); a no-op unless at least two elements are. */
    fun groupSelected() {
        if (selectedElements.size < 2) return
        val groupId = newId()
        val grouped = CanvasActions.group(state, selectedIds, groupId)
        selectedIds =
            grouped.elements
                .filter { it.groupId == groupId }
                .map { it.id }
                .toSet()
        commit(grouped.elements)
    }

    /** Breaks up the group of whatever is selected; a no-op when none of it is grouped. */
    fun ungroupSelected() {
        if (selectedElements.none { it.groupId != null }) return
        commit(CanvasActions.ungroup(state, selectedIds).elements)
    }

    /** [ids] and everything grouped with them: a group is selected, moved and deleted as one. */
    private fun withGroups(ids: Set<String>): Set<String> = ids + CanvasActions.groupsOf(state, ids)

    /** Moves everything selected by ([dx], [dy]) as one undoable step. */
    fun moveSelected(
        dx: Double,
        dy: Double,
    ) {
        if (selectedIds.isEmpty()) return
        commit(CanvasCore.moveElements(state, selectedIds, dx, dy).elements)
    }

    /** A finished gesture's result, as one undoable step. */
    fun commit(elements: List<Element>) {
        state = state.copy(elements = elements)
        history.commit(elements)
        changed()
    }

    /** [edited] after a resize of element [id], for its preview and its commit: a table's rows share its new height, text refits. */
    fun fitted(
        edited: CanvasState,
        id: String,
    ): CanvasState =
        if (edited.elements.find { it.id == id }?.table != null) {
            TableEdits.stretch(edited, id, measurer)
        } else {
            TextElements.refit(edited, id, measurer)
        }

    /** Adds [element] in the current style, fitted to its content, as one undoable step. */
    fun insert(element: Element) {
        commit(CanvasCore.insertElement(state, TextElements.fit(element.copy(style = currentStyle), measurer)).elements)
    }

    /** Adds [image] (FR22) in the middle of the [visible] world rect, in the current style and selected, as one undoable step. */
    fun insertImage(
        image: ImageData,
        visible: WorldRect,
    ) {
        val element = ImageElements.create(newId(), image, visible)
        selectedIds = setOf(element.id)
        insert(element)
    }

    /**
     * Places a new text box (or, for [CanvasTools.NOTE], a sticky note) at [at]
     * and opens the editor on it. It joins the undo history only once it has
     * been given text, as a single step.
     */
    fun placeText(
        tool: String,
        at: Point,
    ) {
        val id = newId()
        val blank = if (tool == CanvasTools.NOTE) TextElements.createNote(id, at) else TextElements.createText(id, at)
        state = CanvasCore.insertElement(state, TextElements.fit(blank.copy(style = currentStyle), measurer))
        beginEdit(EditTarget(id))
    }

    fun beginEdit(target: EditTarget) {
        editing = target
        selectedIds = setOf(target.elementId)
        changed()
        listener.onEditText(target)
    }

    /** Ends editing with [text] as the new content: one undoable step, and none if nothing changed. */
    fun finishEdit(text: String) {
        val target = editing ?: return
        editing = null
        val cell = target.cellIndex
        val next =
            if (cell == null) {
                TextElements.setText(state, target.elementId, text, measurer)
            } else {
                TableEdits.setCell(state, target.elementId, cell, text, measurer)
            }
        if (next.elements.none { it.id == target.elementId }) selectedIds = emptySet()
        if (next.elements == history.current) {
            state = next
            changed()
        } else {
            commit(next.elements)
        }
    }

    /** Deletes everything selected, as one undoable step. */
    fun deleteSelected() {
        if (selectedIds.isEmpty()) return
        val deleted = selectedIds
        selectedIds = emptySet()
        commit(deleted.fold(state) { left, id -> CanvasCore.deleteElement(left, id) }.elements)
    }

    /** Empties the canvas as one undoable step; a no-op when it already is empty. */
    fun clearCanvas() {
        if (state.elements.isEmpty()) return
        selectedIds = emptySet()
        editing = null
        commit(emptyList())
    }

    fun undo() {
        history.undo()?.let(::show)
    }

    fun redo() {
        history.redo()?.let(::show)
    }

    /** Duplicates the selected element [offset] world units down and right, and selects the copy (FR18). */
    fun duplicateSelected(offset: Double) {
        if (selectedIds.isEmpty()) return
        val copies = mutableSetOf<String>()
        val duplicated =
            selectedElements.fold(state) { copied, element ->
                val copyId = newId()
                copies += copyId
                CanvasActions.duplicate(copied, element.id, copyId, offset)
            }
        // Copies of a group form a group of their own, or they would join the one they were copied from.
        val groupsForCopies = mutableMapOf<String, String>()
        val regrouped =
            duplicated.elements.map { element ->
                val group = element.groupId
                if (element.id !in copies || group == null) {
                    element
                } else {
                    element.copy(groupId = groupsForCopies.getOrPut(group) { newId() })
                }
            }
        selectedIds = copies
        commit(regrouped)
    }

    /** Brings everything selected to the front, lowest first, so they keep the order they had among themselves. */
    fun bringSelectedToFront() {
        if (selectedIds.isEmpty()) return
        commit(selectedElements.fold(state) { moved, element -> CanvasActions.bringToFront(moved, element.id) }.elements)
    }

    /** Sends everything selected to the back, highest first, for the same reason in reverse. */
    fun sendSelectedToBack() {
        if (selectedIds.isEmpty()) return
        commit(selectedElements.asReversed().fold(state) { moved, element -> CanvasActions.sendToBack(moved, element.id) }.elements)
    }

    /**
     * Sets one style property (FR19): for elements drawn from now on and on
     * everything selected too, as one undoable step (text refits to a new
     * size). Only that property changes; the rest of each element's style
     * stays. An outline of none is for a selected image alone: what is drawn
     * next keeps its outline.
     */
    fun setStyle(
        property: String,
        value: String,
    ) {
        currentStyle.with(property, value).takeIf { it.dash != DashStyle.NONE }?.let { currentStyle = it }
        val restyled =
            selectedElements.filter { it.style.with(property, value) != it.style }.fold(state) { styled, element ->
                val next = CanvasActions.restyle(styled, element.id, element.style.with(property, value))
                TextElements.refit(next, element.id, measurer)
            }
        if (restyled.elements == state.elements) changed() else commit(restyled.elements)
    }

    /** Deletes every element in [ids] as one undoable step (FR20). */
    fun erase(ids: Set<String>) {
        if (ids.isEmpty()) return
        selectedIds = selectedIds - ids
        commit(ids.fold(state) { erased, id -> CanvasCore.deleteElement(erased, id) }.elements)
    }

    fun addTableRow() = editSelected { TableEdits.addRow(state, it, measurer) }

    fun removeTableRow() = editSelected { TableEdits.removeRow(state, it, measurer) }

    fun addTableColumn() = editSelected { TableEdits.addColumn(state, it, measurer) }

    fun removeTableColumn() = editSelected { TableEdits.removeColumn(state, it, measurer) }

    /** Sends the UI state again even if it hasn't changed, for a view that has just attached. */
    fun republish() {
        lastUiState = null
        publish()
    }

    /** Applies [edit] to the one selected element as one undoable step; a no-op unless exactly one is selected. */
    private inline fun editSelected(edit: (String) -> CanvasState) {
        val id = selected?.id ?: return
        commit(edit(id).elements)
    }

    /** Shows [elements] as they are (a load, or an undo/redo step), clearing the selection. */
    private fun show(elements: List<Element>) {
        state = state.copy(elements = elements)
        selectedIds = emptySet()
        changed()
    }

    private fun changed() {
        publish()
        listener.onChanged()
    }

    private fun publish() {
        // With several selected there is no one type or style to show: the panel keeps showing the style new elements get.
        val element = selected
        // Counted from the elements themselves, so an id left over from something deleted counts as nothing selected.
        val selectedNow = selectedElements.size
        val uiState =
            CanvasUiState(
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                hasSelection = selectedNow > 0,
                style = element?.style ?: currentStyle,
                selectedType = element?.type,
                hasContent = state.elements.isNotEmpty(),
                selectionCount = selectedNow,
                canUngroup = selectedElements.any { it.groupId != null },
            )
        if (uiState == lastUiState) return
        lastUiState = uiState
        listener.onUiState(uiState)
    }
}
