package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

/** The canvas-state event body the UI receives (FR18/FR19/FR24). */
class CanvasUiStateTest {
    private val style = ShapeStyle(StyleColor.LIGHT_RED, 0.75, FillStyle.SEMI, DashStyle.DASHED, SizeStyle.L)
    private val styleIds = mapOf("color" to "light-red", "opacity" to 0.75, "fill" to "semi", "dash" to "dashed", "size" to "l")

    @Test
    fun `the payload carries the action states, the selection's type and the style by id`() {
        assertEquals(
            mapOf(
                "canUndo" to true,
                "canRedo" to false,
                "hasSelection" to true,
                "hasContent" to true,
                "selectionCount" to 1,
                "canUngroup" to false,
                "hasLink" to false,
                "selectedType" to "table",
                "style" to styleIds,
            ),
            CanvasUiState(
                canUndo = true,
                canRedo = false,
                hasSelection = true,
                style = style,
                selectedType = "table",
                hasContent = true,
                selectionCount = 1,
            ).toPayload(),
        )
    }

    @Test
    fun `with nothing selected the type is empty`() {
        assertEquals("", CanvasUiState(canUndo = false, canRedo = false, hasSelection = false, style = style).toPayload()["selectedType"])
    }
}
