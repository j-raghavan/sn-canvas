package com.sncanvas.canvas

import org.junit.Assert.assertEquals
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

    // #59: a ramp fades to nothing, not to white. Fading to white would come out as a white smear
    // over an image or a coloured page, where fading to nothing lets what is under it through.
    @Test
    fun `fadeToNothing clears the alpha and keeps the colour`() {
        assertEquals(0x00406080, StylePalette.fadeToNothing(0xFF406080.toInt()))
        assertEquals(0x00FFFFFF, StylePalette.fadeToNothing(0xFFFFFFFF.toInt()))
        // Already faded stays faded rather than coming back.
        assertEquals(0x00123456, StylePalette.fadeToNothing(0x00123456))
    }

    // A ramp runs from where a solid fill paints to that same colour gone: one hue fading out, in
    // either palette, so the top of a gradient reads as a solid fill does on screen and in a PDF.
    @Test
    fun `a ramp's two ends are one colour fading out, in both palettes`() {
        for (palette in StylePalette.entries) {
            for (color in StyleColor.entries) {
                val near = palette.solidFill(color)
                val far = StylePalette.fadeToNothing(near)
                assertTrue("$palette $color near end opaque", (near ushr 24) > 0)
                assertEquals("$palette $color far end alpha", 0, far ushr 24)
                assertEquals("$palette $color hue", near and 0xFFFFFF, far and 0xFFFFFF)
            }
        }
    }

    // #59: a shape's fill and a sticky note's tint are painted in two files, neither of which a test
    // can reach. They both ask fillTint which tint a fill reads as, so the decision is pinned here
    // once instead of drifting between two copies of it.
    // Only the fills that read as solid are named: the rest is whatever the enum has left, so a fill
    // added later is checked here without anyone remembering to add it, and is expected to read as
    // semi until it is named here. Listing both sides by hand would leave a sixth fill in neither.
    private val readAsSolid = setOf(FillStyle.SOLID, FillStyle.GRADIENT)

    /** Every fill's tint for one palette and colour, against the member each one has to equal. */
    private fun assertTints(
        palette: StylePalette,
        color: StyleColor,
    ) {
        for (fill in FillStyle.entries) {
            val tint = if (fill in readAsSolid) palette.solidFill(color) else palette.semiFill(color)
            assertEquals("$palette $color $fill", tint, palette.fillTint(fill, color))
        }
    }

    @Test
    fun `each fill reads as the solid tint or the semi one, in both palettes`() {
        for (palette in StylePalette.entries) {
            StyleColor.entries.forEach { assertTints(palette, it) }
        }
    }

    // A ramp runs from its tint to that tint gone, so a fill that ramps has to start where a solid
    // fill paints: one put in the semi branch would fade from a tint no solid fill ever shows.
    @Test
    fun `a fill that ramps takes the solid tint`() {
        val ramping = FillStyle.entries.filter { it.ramps }
        assertEquals(listOf(FillStyle.GRADIENT), ramping)
        for (palette in StylePalette.entries) {
            ramping.forEach { assertEquals("$palette $it", palette.solidFill(StyleColor.RED), palette.fillTint(it, StyleColor.RED)) }
        }
    }

    @Test
    fun `mixWithWhite moves each channel toward white and keeps alpha`() {
        assertEquals(0x80406080.toInt(), StylePalette.mixWithWhite(0x80406080.toInt(), 0.0))
        assertEquals(0x80FFFFFF.toInt(), StylePalette.mixWithWhite(0x80406080.toInt(), 1.0))
        assertEquals(0xFF80C0E0.toInt(), StylePalette.mixWithWhite(0xFF0080C0.toInt(), 0.5))
    }
}
