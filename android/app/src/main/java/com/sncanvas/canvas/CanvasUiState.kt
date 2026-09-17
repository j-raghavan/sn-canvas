package com.sncanvas.canvas

/**
 * What the UI shows about the canvas (FR18/FR19): which action-bar buttons
 * apply, the style the style panel displays, and what kind of element is
 * selected (a table brings its row and column actions). The canvas publishes
 * one each time it changes; [toPayload] is the event body the JS side receives.
 */
data class CanvasUiState(
    val canUndo: Boolean,
    val canRedo: Boolean,
    val hasSelection: Boolean,
    /** The selected element's style, or the style new elements get when nothing is selected. */
    val style: ShapeStyle,
    /** The selected element's type, or null when nothing is selected. */
    val selectedType: String? = null,
    /** Whether the canvas holds anything at all; Clear canvas applies only then. */
    val hasContent: Boolean = false,
    /** How many elements are selected; several at once rule out resizing, rotating and editing text. */
    val selectionCount: Int = 0,
    /** Whether anything selected belongs to a group, so Ungroup applies. */
    val canUngroup: Boolean = false,
) {
    fun toPayload(): Map<String, Any> =
        mapOf(
            "canUndo" to canUndo,
            "canRedo" to canRedo,
            "hasSelection" to hasSelection,
            "hasContent" to hasContent,
            "selectionCount" to selectionCount,
            "canUngroup" to canUngroup,
            "selectedType" to selectedType.orEmpty(),
            "style" to
                mapOf(
                    "color" to style.color.id,
                    "opacity" to style.opacity,
                    "fill" to style.fill.id,
                    "dash" to style.dash.id,
                    "size" to style.size.id,
                ),
        )
}
