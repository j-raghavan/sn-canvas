package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/** Style options by id, the single-property updates the style panel sends, and the two starting styles (FR19). */
class CanvasStyleTest {
    @Test
    fun `byId finds an option by id and falls back for an unknown or missing one`() {
        assertEquals(StyleColor.LIGHT_BLUE, StyleColor.entries.byId("light-blue", StyleColor.BLACK))
        assertEquals(DashStyle.SOLID, DashStyle.entries.byId("wavy", DashStyle.SOLID))
        assertEquals(SizeStyle.M, SizeStyle.entries.byId(null, SizeStyle.M))
    }

    /**
     * The style panel's catalog also lives, independently, as src/domain/styles.ts (FR19) — the two must
     * agree on ids and values. style-catalog.json at the repo root is the golden fixture both this test
     * and styles.test.ts assert their own copy against, so the two can't silently drift apart (see its
     * `_comment`). Read with CanvasJson's own JSON parser (widened to `internal` for exactly this) rather
     * than a second hand-rolled one, or org.json, stubbed under this plain-JUnit setup (see CanvasJson.kt).
     */
    @Test
    fun `tldraw's 12 colours, 4 fills, 4 dashes and 4 sizes, matching the shared style-catalog fixture`() {
        val catalog = styleCatalogFixture()
        assertEquals(catalog.array("colors").map { it.obj().string("id") }, StyleColor.entries.map { it.id })
        assertEquals(catalog.array("colors").map { it.obj().string("hex") }, StyleColor.entries.map { it.rgb.toHexColor() })
        assertEquals(catalog.array("fills").map { it.str() }, FillStyle.entries.map { it.id })
        assertEquals(catalog.array("imageDashes").map { it.str() }, DashStyle.entries.map { it.id })
        assertEquals(catalog.array("sizes").map { it.obj().string("id") }, SizeStyle.entries.map { it.id })
        assertEquals(catalog.array("sizes").map { it.obj().number("strokeWidth") }, SizeStyle.entries.map { it.strokeWidth })
        assertEquals(catalog.array("sizes").map { it.obj().number("fontSize") }, SizeStyle.entries.map { it.fontSize })
        assertEquals(catalog.obj("defaultStyle").string("color"), ShapeStyle.DEFAULT.color.id)
        assertEquals(catalog.obj("defaultStyle").string("fill"), ShapeStyle.DEFAULT.fill.id)
        assertEquals(catalog.obj("defaultStyle").string("dash"), ShapeStyle.DEFAULT.dash.id)
        assertEquals(catalog.obj("defaultStyle").string("size"), ShapeStyle.DEFAULT.size.id)
        assertEquals(catalog.number("minOpacity"), ShapeStyle.MIN_OPACITY, 0.0)
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

    @Test
    fun `each size has a text size for text boxes, notes and tables`() {
        assertEquals(listOf(18.0, 24.0, 36.0, 44.0), SizeStyle.entries.map { it.fontSize })
    }
}

/** Finds style-catalog.json above the test's working directory (Gradle's module dir, or an IDE's), and parses it. */
private fun styleCatalogFixture(): JsonValue.Obj {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        val candidate = File(dir, "style-catalog.json")
        if (candidate.isFile) return JsonParser(candidate.readText()).parseDocument() as JsonValue.Obj
        dir = dir.parentFile
    }
    error("style-catalog.json not found above ${File(".").absoluteFile}")
}

// Thin accessors over JsonValue, for the fixture's own shape only; Json.kt owns the actual grammar.
private fun JsonValue.obj(): JsonValue.Obj = this as JsonValue.Obj

private fun JsonValue.str(): String = (this as JsonValue.Str).value

private fun JsonValue.Obj.array(key: String): List<JsonValue> = (entries.getValue(key) as JsonValue.Arr).items

private fun JsonValue.Obj.obj(key: String): JsonValue.Obj = entries.getValue(key) as JsonValue.Obj

private fun JsonValue.Obj.string(key: String): String = (entries.getValue(key) as JsonValue.Str).value

private fun JsonValue.Obj.number(key: String): Double = (entries.getValue(key) as JsonValue.Num).value

private fun Int.toHexColor(): String = "#" + toString(16).padStart(HEX_COLOR_DIGITS, '0')

private const val HEX_COLOR_DIGITS = 6
