package com.snsupercanvas.canvas

/**
 * Measures wrapped text in world units: how tall [text] is when wrapped to
 * `width` at `fontSize`. The Android side implements it with StaticLayout;
 * tests use a simple fake. It is what lets text boxes, notes and table rows
 * grow with their text while the model stays pure.
 */
fun interface TextMeasurer {
    fun height(
        text: String,
        width: Double,
        fontSize: Double,
    ): Double
}

/**
 * Text boxes and sticky notes (FR6/FR21): creating them, setting their text,
 * and keeping their height fitted to it. A text box keeps the width it is
 * given and grows downward; a note stays at least square.
 */
object TextElements {
    /** A new text box's width in world units; its height follows its text. */
    const val TEXT_WIDTH = 320.0

    /** A new sticky note's side. */
    const val NOTE_SIZE = 200.0

    /** The inset between a box's edge and its text, shared by measuring and drawing. */
    const val PADDING = 8.0

    /** The line spacing multiplier, shared by measuring and drawing. */
    const val LINE_SPACING = 1.2

    fun isEditable(element: Element): Boolean = element.type == CanvasTools.TEXT || element.type == CanvasTools.NOTE

    fun fontSize(element: Element): Double = element.style.size.fontSize

    /** A new, empty text box whose top-left corner is [at]. */
    fun createText(
        id: String,
        at: Point,
    ): Element = Element(id = id, type = CanvasTools.TEXT, x = at.x, y = at.y, width = TEXT_WIDTH, text = "")

    /** A new, empty sticky note centered on [center]. */
    fun createNote(
        id: String,
        center: Point,
    ): Element =
        Element(
            id = id,
            type = CanvasTools.NOTE,
            x = center.x - NOTE_SIZE / 2,
            y = center.y - NOTE_SIZE / 2,
            width = NOTE_SIZE,
            height = NOTE_SIZE,
            text = "",
        )

    /**
     * Element [id] with its text set to [text] and its height refitted. A text
     * box left blank is removed, as in tldraw; a note keeps its blank square.
     */
    fun setText(
        state: CanvasState,
        id: String,
        text: String,
        measurer: TextMeasurer,
    ): CanvasState {
        val element = state.elements.find { it.id == id } ?: return state
        val updated = if (element.type == CanvasTools.TEXT && text.isBlank()) null else fit(element.copy(text = text), measurer)
        return state.copy(elements = state.elements.mapNotNull { if (it.id == id) updated else it })
    }

    /** [element] with its height fitted to its content: text boxes and notes to their text, tables to their rows; others as they are. */
    fun fit(
        element: Element,
        measurer: TextMeasurer,
    ): Element =
        when (element.type) {
            CanvasTools.TEXT -> element.copy(height = textHeight(element, measurer))
            CanvasTools.NOTE -> element.copy(height = maxOf(element.width, textHeight(element, measurer)))
            CanvasTools.TABLE -> TableElements.fit(element, measurer)
            else -> element
        }

    /** [state] with element [id] refitted, after a resize or a size change moved its text. */
    fun refit(
        state: CanvasState,
        id: String,
        measurer: TextMeasurer,
    ): CanvasState = state.copy(elements = state.elements.map { if (it.id == id) fit(it, measurer) else it })

    private fun textHeight(
        element: Element,
        measurer: TextMeasurer,
    ): Double = measurer.height(element.text.orEmpty(), (element.width - 2 * PADDING).coerceAtLeast(1.0), fontSize(element)) + 2 * PADDING
}
