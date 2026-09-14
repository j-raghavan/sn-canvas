package com.snsupercanvas.canvas

/** A rectangle in the view's layout units (dp), where the text editor goes. */
data class ScreenRect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

/**
 * What the JS text editor needs to open over the text being edited (FR6/FR24):
 * which element (and table cell), its text, where it sits on screen and how
 * big its type is, in layout units (dp), so RN can place a TextInput exactly
 * over it. [toPayload] is the event body the JS side receives.
 */
data class TextEditRequest(
    val target: CanvasController.EditTarget,
    val text: String,
    val rect: ScreenRect,
    val fontSize: Double,
    val isNote: Boolean,
) {
    fun toPayload(): Map<String, Any> =
        mapOf(
            "elementId" to target.elementId,
            "cellIndex" to (target.cellIndex ?: -1),
            "text" to text,
            "left" to rect.left,
            "top" to rect.top,
            "width" to rect.width,
            "height" to rect.height,
            "fontSize" to fontSize,
            "isNote" to isNote,
        )

    companion object {
        /** The request for [target] within [element], seen through [transform]; [density] turns pixels into dp. */
        fun of(
            element: Element,
            target: CanvasController.EditTarget,
            transform: ViewTransform,
            measurer: TextMeasurer,
            density: Double,
        ): TextEditRequest {
            val cell = target.cellIndex
            val world = TableElements.cellRect(if (cell == null) element.copy(table = null) else element, cell ?: 0, measurer)
            val text = if (cell == null) element.text.orEmpty() else TableElements.cellText(element, cell)
            val fontSize = if (cell == null) TextElements.fontSize(element) else TableElements.fontSize(element)
            val scale = transform.zoom / density
            val rect =
                ScreenRect(
                    transform.screenX(world.left) / density,
                    transform.screenY(world.top) / density,
                    world.width * scale,
                    world.height * scale,
                )
            return TextEditRequest(target, text, rect, fontSize * scale, element.type == CanvasTools.NOTE)
        }
    }
}
