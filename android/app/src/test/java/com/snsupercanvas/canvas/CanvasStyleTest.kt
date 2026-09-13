package com.snsupercanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Style options by id, the single-property updates the style panel sends, and the two starting styles (FR19). */
class CanvasStyleTest {
    @Test
    fun `byId finds an option by id and falls back for an unknown or missing one`() {
        assertEquals(StyleColor.LIGHT_BLUE, StyleColor.entries.byId("light-blue", StyleColor.BLACK))
        assertEquals(DashStyle.SOLID, DashStyle.entries.byId("wavy", DashStyle.SOLID))
        assertEquals(SizeStyle.M, SizeStyle.entries.byId(null, SizeStyle.M))
    }

    @Test
    fun `tldraw's 12 colours, 4 fills, 4 dashes and 4 sizes`() {
        assertEquals(12, StyleColor.entries.size)
        assertEquals(listOf("none", "semi", "solid", "pattern"), FillStyle.entries.map { it.id })
        assertEquals(listOf("draw", "dashed", "dotted", "solid"), DashStyle.entries.map { it.id })
        assertEquals(listOf(2.0, 3.5, 5.0, 10.0), SizeStyle.entries.map { it.strokeWidth })
    }

    @Test
    fun `new elements start with tldraw's defaults, older ones keep their solid look`() {
        assertEquals(ShapeStyle(StyleColor.BLACK, 1.0, FillStyle.NONE, DashStyle.DRAW, SizeStyle.M), ShapeStyle.DEFAULT)
        assertEquals(ShapeStyle.DEFAULT.copy(dash = DashStyle.SOLID), ShapeStyle.LEGACY)
    }

    @Test
    fun `with changes exactly one property`() {
        val style = ShapeStyle.DEFAULT
        assertEquals(style.copy(color = StyleColor.RED), style.with("color", "red"))
        assertEquals(style.copy(fill = FillStyle.PATTERN), style.with("fill", "pattern"))
        assertEquals(style.copy(dash = DashStyle.DOTTED), style.with("dash", "dotted"))
        assertEquals(style.copy(size = SizeStyle.XL), style.with("size", "xl"))
        assertEquals(style.copy(opacity = 0.5), style.with("opacity", "0.5"))
    }

    @Test
    fun `with ignores an unknown property or value, and clamps opacity`() {
        val style = ShapeStyle.DEFAULT
        assertSame(style, style.with("glow", "yes"))
        assertEquals(style, style.with("color", "magenta"))
        assertEquals(style, style.with("opacity", "half"))
        assertEquals(style, style.with("opacity", "NaN"))
        assertEquals(ShapeStyle.MIN_OPACITY, style.with("opacity", "0").opacity, 0.0)
        assertEquals(1.0, style.with("opacity", "7").opacity, 0.0)
    }

    @Test
    fun `a style rejects an opacity outside its range`() {
        assertThrows(IllegalArgumentException::class.java) { ShapeStyle(opacity = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ShapeStyle(opacity = 1.5) }
    }

    @Test
    fun `alpha follows opacity and stroke width follows size and zoom, never below a hairline`() {
        assertEquals(128, ShapeStyle(opacity = 0.5).alpha)
        assertEquals(255, ShapeStyle.DEFAULT.alpha)
        assertEquals(7f, ShapeStyle.DEFAULT.strokeWidthPx(2.0), 0f)
        assertEquals(ShapeStyle.MIN_STROKE_PX, ShapeStyle(size = SizeStyle.S).strokeWidthPx(0.1), 0f)
    }
}
