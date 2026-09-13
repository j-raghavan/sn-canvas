package com.snsupercanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

/** The canvas-state event body the UI receives (FR18/FR19). */
class CanvasUiStateTest {
    @Test
    fun `the payload carries the action states and the style by id`() {
        val style = ShapeStyle(StyleColor.LIGHT_RED, 0.75, FillStyle.SEMI, DashStyle.DASHED, SizeStyle.L)
        assertEquals(
            mapOf(
                "canUndo" to true,
                "canRedo" to false,
                "hasSelection" to true,
                "style" to mapOf("color" to "light-red", "opacity" to 0.75, "fill" to "semi", "dash" to "dashed", "size" to "l"),
            ),
            CanvasUiState(canUndo = true, canRedo = false, hasSelection = true, style = style).toPayload(),
        )
    }
}
