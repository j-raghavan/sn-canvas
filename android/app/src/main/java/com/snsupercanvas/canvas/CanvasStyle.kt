package com.snsupercanvas.canvas

import kotlin.math.roundToInt

/** A style option stored by its id, such as "light-blue" or "dashed" (FR19). */
interface StyleOption {
    val id: String
}

/** The option with [id], or [fallback] for an unknown or missing id. */
fun <E : StyleOption> List<E>.byId(
    id: String?,
    fallback: E,
): E = firstOrNull { it.id == id } ?: fallback

/**
 * tldraw's 12 colours (FR19) with their true light-theme RGB. The live e-ink
 * view draws each as a gray of its own; see [StylePalette.EINK].
 */
enum class StyleColor(
    override val id: String,
    val rgb: Int,
) : StyleOption {
    BLACK("black", 0x1D1D1D),
    GREY("grey", 0x9FA8B2),
    LIGHT_VIOLET("light-violet", 0xE085F4),
    VIOLET("violet", 0xAE3EC9),
    BLUE("blue", 0x4465E9),
    LIGHT_BLUE("light-blue", 0x4BA1F1),
    YELLOW("yellow", 0xF1AC4B),
    ORANGE("orange", 0xE16919),
    GREEN("green", 0x099268),
    LIGHT_GREEN("light-green", 0x4CB05E),
    LIGHT_RED("light-red", 0xF87777),
    RED("red", 0xE03131),
}

enum class FillStyle(
    override val id: String,
) : StyleOption {
    NONE("none"),
    SEMI("semi"),
    SOLID("solid"),
    PATTERN("pattern"),
}

enum class DashStyle(
    override val id: String,
) : StyleOption {
    /** The hand-drawn wobble; see [ShapeOutline.handDrawn]. */
    DRAW("draw"),
    DASHED("dashed"),
    DOTTED("dotted"),
    SOLID("solid"),
}

/** Stroke weights and text sizes in world units, so a PDF or a thumbnail matches the screen at 100% (tldraw's s/m/l/xl). */
enum class SizeStyle(
    override val id: String,
    val strokeWidth: Double,
    val fontSize: Double,
) : StyleOption {
    S("s", 2.0, 18.0),
    M("m", 3.5, 24.0),
    L("l", 5.0, 36.0),
    XL("xl", 10.0, 44.0),
}

/** An element's look (FR19): colour, opacity, fill, dash and size. */
data class ShapeStyle(
    val color: StyleColor = StyleColor.BLACK,
    val opacity: Double = 1.0,
    val fill: FillStyle = FillStyle.NONE,
    val dash: DashStyle = DashStyle.DRAW,
    val size: SizeStyle = SizeStyle.M,
) {
    init {
        require(opacity in MIN_OPACITY..1.0) { "opacity must be in $MIN_OPACITY..1" }
    }

    /** Paint alpha (0..255) for this style's opacity. */
    val alpha: Int get() = (opacity * 255).roundToInt()

    /** The stroke width in screen pixels at [zoom]; never thinner than [MIN_STROKE_PX], so zoomed-out lines stay visible. */
    fun strokeWidthPx(zoom: Double): Float = (size.strokeWidth * zoom).toFloat().coerceAtLeast(MIN_STROKE_PX)

    /**
     * This style with one property changed, the way the style panel sends it
     * (for example "color" to "red"). An unknown property or value leaves the
     * style as it is, so no UI message can corrupt a canvas.
     */
    fun with(
        property: String,
        value: String,
    ): ShapeStyle =
        when (property) {
            "color" -> copy(color = StyleColor.entries.byId(value, color))
            "fill" -> copy(fill = FillStyle.entries.byId(value, fill))
            "dash" -> copy(dash = DashStyle.entries.byId(value, dash))
            "size" -> copy(size = SizeStyle.entries.byId(value, size))
            "opacity" -> copy(opacity = value.toDoubleOrNull()?.takeUnless { it.isNaN() }?.coerceIn(MIN_OPACITY, 1.0) ?: opacity)
            else -> this
        }

    companion object {
        const val MIN_OPACITY = 0.1
        const val MIN_STROKE_PX = 1f

        /** What new elements start with: tldraw's defaults. */
        val DEFAULT = ShapeStyle()

        /** Elements saved before styles existed keep the solid look they were drawn with. */
        val LEGACY = ShapeStyle(dash = DashStyle.SOLID)
    }
}
