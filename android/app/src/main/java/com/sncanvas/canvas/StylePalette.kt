package com.sncanvas.canvas

import kotlin.math.roundToInt

/**
 * Turns a [StyleColor] into the ARGB values a renderer paints with (FR19).
 * [TRUE_COLOR] keeps tldraw's colours, for the note thumbnail and the PDF.
 * [EINK] gives each colour a gray of its own for the live grayscale view,
 * spread evenly in the colours' luminance order so that hues of similar
 * brightness (red and violet, light blue and light green) never share a shade.
 * Fills are the stroke tone moved toward white, so they read as tints of it.
 */
enum class StylePalette {
    TRUE_COLOR,
    EINK,
    ;

    /** The opaque stroke colour. */
    fun stroke(color: StyleColor): Int = OPAQUE or if (this == TRUE_COLOR) color.rgb else einkGray(color)

    fun semiFill(color: StyleColor): Int = mixWithWhite(stroke(color), SEMI_WHITENESS)

    fun solidFill(color: StyleColor): Int = mixWithWhite(stroke(color), SOLID_WHITENESS)

    /**
     * The two ends of [fill]'s ramp for [color], or null for a fill that is one flat colour (#59).
     *
     * A gradient runs from the solid fill to that same colour with its alpha cleared, so it fades to
     * nothing rather than to white and comes out the same over the page, over an image and in a PDF.
     */
    fun gradientEnds(
        fill: FillStyle,
        color: StyleColor,
    ): Pair<Int, Int>? = if (fill == FillStyle.GRADIENT) solidFill(color) to (solidFill(color) and NO_ALPHA) else null

    /** The hatch lines of a pattern fill, drawn over [semiFill]. */
    fun patternLine(color: StyleColor): Int = mixWithWhite(stroke(color), PATTERN_WHITENESS)

    companion object {
        private const val OPAQUE = 0xFF shl 24

        /** A colour with its alpha cleared; the colour itself is untouched. */
        private const val NO_ALPHA = 0x00FFFFFF
        private const val DARKEST_GRAY = 0x00

        // Light enough to separate 12 levels, dark enough that a thin light stroke still shows on white.
        private const val LIGHTEST_GRAY = 0xB4
        private const val SEMI_WHITENESS = 0.85
        private const val SOLID_WHITENESS = 0.45
        private const val PATTERN_WHITENESS = 0.3

        private val einkGrays: Map<StyleColor, Int> =
            StyleColor.entries.sortedBy { luminance(it.rgb) }.withIndex().associate { (rank, color) ->
                val level = DARKEST_GRAY + rank * (LIGHTEST_GRAY - DARKEST_GRAY) / (StyleColor.entries.size - 1)
                color to (level shl 16 or (level shl 8) or level)
            }

        /** The e-ink gray (0xRRGGBB, R = G = B) the live view draws [color] with. */
        fun einkGray(color: StyleColor): Int = einkGrays.getValue(color)

        /** Relative luminance (Rec. 709) of a 0xRRGGBB colour, on a 0..255 scale. */
        fun luminance(rgb: Int): Double = 0.2126 * (rgb shr 16 and 0xFF) + 0.7152 * (rgb shr 8 and 0xFF) + 0.0722 * (rgb and 0xFF)

        /** [argb] moved [whiteness] (0..1) of the way to white, alpha unchanged. */
        fun mixWithWhite(
            argb: Int,
            whiteness: Double,
        ): Int {
            fun channel(shift: Int): Int {
                val value = argb shr shift and 0xFF
                return (value + (0xFF - value) * whiteness).roundToInt() shl shift
            }
            return (argb and OPAQUE) or channel(16) or channel(8) or channel(0)
        }
    }
}
