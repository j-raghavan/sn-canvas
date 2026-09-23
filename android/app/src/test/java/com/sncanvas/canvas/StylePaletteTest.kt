package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** True colour for exports, a distinct gray per colour for the e-ink view, and fills as tints (FR19). */
class StylePaletteTest {
    private fun channels(argb: Int) = listOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)

    @Test
    fun `true colour keeps tldraw's colours, opaque`() {
        assertEquals(0xFFE03131.toInt(), StylePalette.TRUE_COLOR.stroke(StyleColor.RED))
    }

    @Test
    fun `e-ink gives all 12 colours distinct neutral grays, black darkest and yellow lightest`() {
        val grays = StyleColor.entries.map { StylePalette.EINK.stroke(it) }
        assertEquals(12, grays.toSet().size)
        grays.forEach { assertEquals(1, channels(it).toSet().size) }
        assertEquals(0xFF000000.toInt(), StylePalette.EINK.stroke(StyleColor.BLACK))
        assertEquals(0xFFB4B4B4.toInt(), StylePalette.EINK.stroke(StyleColor.YELLOW))
    }

    @Test
    fun `e-ink grays follow the colours' luminance order`() {
        val byLuminance = StyleColor.entries.sortedBy { StylePalette.luminance(it.rgb) }
        val levels = byLuminance.map { StylePalette.einkGray(it) and 0xFF }
        assertEquals(levels.sorted(), levels)
    }

    @Test
    fun `fills are lighter tints of the stroke, in both palettes`() {
        for (palette in StylePalette.entries) {
            val stroke = channels(palette.stroke(StyleColor.BLUE)).sum()
            val pattern = channels(palette.patternLine(StyleColor.BLUE)).sum()
            val solid = channels(palette.solidFill(StyleColor.BLUE)).sum()
            val semi = channels(palette.semiFill(StyleColor.BLUE)).sum()
            assertTrue("$palette", stroke < pattern && pattern < solid && solid < semi)
        }
    }

    // #59: the gradient fades to nothing, not to white. Fading to white would come out as a white
    // smear over an image or a coloured page, where fading to nothing lets what is under it through.
    @Test
    fun `only the gradient fill has a ramp, from the solid fill to nothing`() {
        for (palette in StylePalette.entries) {
            // The flat fills have no ramp at all, so nothing but the gradient gets a shader.
            for (flat in listOf(FillStyle.NONE, FillStyle.SEMI, FillStyle.SOLID, FillStyle.PATTERN)) {
                assertNull("$palette $flat", palette.gradientEnds(flat, StyleColor.BLUE))
            }

            val (from, to) = palette.gradientEnds(FillStyle.GRADIENT, StyleColor.BLUE)!!
            // It starts where a solid fill would, so the two read as the same colour at the top.
            assertEquals("$palette from", palette.solidFill(StyleColor.BLUE), from)
            assertTrue("$palette opaque end", (from ushr 24) > 0)
            // And fades to nothing, keeping the colour, so it is one hue fading rather than a slide to grey.
            assertEquals("$palette alpha", 0, to ushr 24)
            assertEquals("$palette rgb", from and 0xFFFFFF, to and 0xFFFFFF)
        }
    }

    @Test
    fun `mixWithWhite moves each channel toward white and keeps alpha`() {
        assertEquals(0x80406080.toInt(), StylePalette.mixWithWhite(0x80406080.toInt(), 0.0))
        assertEquals(0x80FFFFFF.toInt(), StylePalette.mixWithWhite(0x80406080.toInt(), 1.0))
        assertEquals(0xFF80C0E0.toInt(), StylePalette.mixWithWhite(0xFF0080C0.toInt(), 0.5))
    }
}
