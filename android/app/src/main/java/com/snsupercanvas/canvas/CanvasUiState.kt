package com.snsupercanvas.canvas

/**
 * What the UI shows about the canvas (FR18/FR19): which action-bar buttons
 * apply, and the style the style panel displays. The view publishes one each
 * time it changes; [toPayload] is the event body the JS side receives.
 */
data class CanvasUiState(
    val canUndo: Boolean,
    val canRedo: Boolean,
    val hasSelection: Boolean,
    /** The selected element's style, or the style new elements get when nothing is selected. */
    val style: ShapeStyle,
) {
    fun toPayload(): Map<String, Any> =
        mapOf(
            "canUndo" to canUndo,
            "canRedo" to canRedo,
            "hasSelection" to hasSelection,
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
