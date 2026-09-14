package com.snsupercanvas.canvas

/**
 * The canvas's command layer: the element list, the undo history, the
 * selection, the style for new elements and the text being edited. Every
 * command (from the action bar, the style panel, the text editor or a finished
 * gesture) becomes one undoable step here. Pure Kotlin: [SuperCanvasView]
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

    var selectedId: String? = null
        private set

    var editing: EditTarget? = null
        private set

    /** The style new elements get (FR19); the pencil's live stroke draws in it too. */
    var currentStyle = ShapeStyle.DEFAULT
        private set

    private val history = EditHistory()
    private var lastUiState: CanvasUiState? = null

    val selected: Element? get() = selectedId?.let { id -> state.elements.find { it.id == id } }

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

    fun select(id: String?) {
        selectedId = id
        changed()
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
        commit(SuperCanvasCore.insertElement(state, TextElements.fit(element.copy(style = currentStyle), measurer)).elements)
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
        state = SuperCanvasCore.insertElement(state, TextElements.fit(blank.copy(style = currentStyle), measurer))
        beginEdit(EditTarget(id))
    }

    fun beginEdit(target: EditTarget) {
        editing = target
        selectedId = target.elementId
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
        if (next.elements.none { it.id == target.elementId }) selectedId = null
        if (next.elements == history.current) {
            state = next
            changed()
        } else {
            commit(next.elements)
        }
    }

    fun deleteSelected() {
        val id = selectedId ?: return
        selectedId = null
        commit(SuperCanvasCore.deleteElement(state, id).elements)
    }

    fun undo() {
        history.undo()?.let(::show)
    }

    fun redo() {
        history.redo()?.let(::show)
    }

    /** Duplicates the selected element [offset] world units down and right, and selects the copy (FR18). */
    fun duplicateSelected(offset: Double) {
        val id = selectedId ?: return
        val copyId = newId()
        selectedId = copyId
        commit(CanvasActions.duplicate(state, id, copyId, offset).elements)
    }

    fun bringSelectedToFront() = editSelected { CanvasActions.bringToFront(state, it) }

    fun sendSelectedToBack() = editSelected { CanvasActions.sendToBack(state, it) }

    /**
     * Sets one style property (FR19): for elements drawn from now on and, when
     * an element is selected, on it too as one undoable step (text refits to a
     * new size). Only that property changes; the rest of its style stays.
     */
    fun setStyle(
        property: String,
        value: String,
    ) {
        currentStyle = currentStyle.with(property, value)
        val element = selected
        if (element != null && element.style.with(property, value) != element.style) {
            val restyled = CanvasActions.restyle(state, element.id, element.style.with(property, value))
            commit(TextElements.refit(restyled, element.id, measurer).elements)
        } else {
            changed()
        }
    }

    /** Deletes every element in [ids] as one undoable step (FR20). */
    fun erase(ids: Set<String>) {
        if (ids.isEmpty()) return
        if (selectedId in ids) selectedId = null
        commit(ids.fold(state) { erased, id -> SuperCanvasCore.deleteElement(erased, id) }.elements)
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

    /** Applies [edit] to the selected element as one undoable step; a no-op with nothing selected. */
    private inline fun editSelected(edit: (String) -> CanvasState) {
        val id = selectedId ?: return
        commit(edit(id).elements)
    }

    /** Shows [elements] as they are (a load, or an undo/redo step), clearing the selection. */
    private fun show(elements: List<Element>) {
        state = state.copy(elements = elements)
        selectedId = null
        changed()
    }

    private fun changed() {
        publish()
        listener.onChanged()
    }

    private fun publish() {
        val element = selected
        val uiState =
            if (element == null) {
                CanvasUiState(history.canUndo, history.canRedo, hasSelection = false, style = currentStyle)
            } else {
                CanvasUiState(history.canUndo, history.canRedo, hasSelection = true, style = element.style, selectedType = element.type)
            }
        if (uiState == lastUiState) return
        lastUiState = uiState
        listener.onUiState(uiState)
    }
}
