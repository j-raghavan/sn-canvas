package com.snsupercanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

/** The versioned JSON persistence codec: round-trips, backward compatibility and never-throw parsing. */
class CanvasJsonTest {
    // --- serializeElements / deserializeElements (persistence) ------------------

    @Test
    fun `serializeElements then deserializeElements round-trips a mix of bbox and connector elements`() {
        val rect = Element(id = "r1", type = "rectangle", x = 1.0, y = 2.0, width = 3.0, height = 4.0)
        val ellipse = Element(id = "e1", type = "ellipse", x = -5.5, y = 0.0, width = 10.0, height = 20.0)
        val freeLine = Element(id = "l1", type = "line", startX = 1.0, startY = 2.0, endX = 3.0, endY = 4.0)
        val boundArrow =
            Element(
                id = "a1",
                type = "arrow",
                startX = 0.0,
                startY = 0.0,
                endX = 5.0,
                endY = 5.0,
                startElementId = "r1",
                endElementId = "e1",
            )
        val original = listOf(rect, ellipse, freeLine, boundArrow)

        val json = CanvasJson.serializeElements(original)
        val restored = CanvasJson.deserializeElements(json)

        assertEquals(original, restored)
    }

    @Test
    fun `serializeElements round-trips an empty element list`() {
        val json = CanvasJson.serializeElements(emptyList())
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements(json))
    }

    @Test
    fun `serializeElements escapes special characters in string fields`() {
        val tricky = Element(id = "id-with-\"quote\"-and-\\backslash\\-and-\nnewline", type = "rectangle")
        val restored = CanvasJson.deserializeElements(CanvasJson.serializeElements(listOf(tricky)))
        assertEquals(listOf(tricky), restored)
    }

    @Test
    fun `deserializeElements degrades to an empty list for malformed JSON`() {
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("{not valid json"))
    }

    @Test
    fun `deserializeElements degrades to an empty list for an empty string`() {
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements(""))
    }

    @Test
    fun `deserializeElements degrades to an empty list when the top-level shape is wrong`() {
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("[1,2,3]"))
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("42"))
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("\"just a string\""))
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("null"))
    }

    @Test
    fun `deserializeElements degrades to an empty list when the elements field is missing or the wrong type`() {
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("""{"version":1}"""))
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("""{"version":1,"elements":"not an array"}"""))
    }

    @Test
    fun `deserializeElements skips one malformed element but keeps the rest of a valid array`() {
        val json =
            """{"version":1,"elements":[
                {"id":"good1","type":"rectangle","x":0,"y":0,"width":1,"height":1},
                {"id":"bad-negative-width","type":"rectangle","x":0,"y":0,"width":-1,"height":1},
                "not even an object",
                {"id":"good2","type":"ellipse","x":0,"y":0,"width":1,"height":1}
            ]}"""
        val restored = CanvasJson.deserializeElements(json)
        assertEquals(listOf("good1", "good2"), restored.map { it.id })
    }

    @Test
    fun `deserializeElements skips an element missing its required id field`() {
        val json = """{"version":1,"elements":[{"type":"rectangle"}]}"""
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements(json))
    }

    @Test
    fun `deserializeElements skips an element missing its required type field`() {
        val json = """{"version":1,"elements":[{"id":"r1"}]}"""
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements(json))
    }

    @Test
    fun `serializeElements escapes an arbitrary control character via unicode escape, not just the named ones`() {
        val tricky = Element(id = "id-with--control-char", type = "rectangle")
        val json = CanvasJson.serializeElements(listOf(tricky))
        assertEquals(listOf(tricky), CanvasJson.deserializeElements(json))
    }

    @Test
    fun `deserializeElements defaults missing bbox fields to zero rather than throwing`() {
        val json = """{"version":1,"elements":[{"id":"r1","type":"rectangle"}]}"""
        val restored = CanvasJson.deserializeElements(json)
        assertEquals(listOf(Element(id = "r1", type = "rectangle")), restored)
    }

    @Test
    fun `deserializeElements ignores unknown extra fields rather than failing`() {
        val json =
            """{"version":1,"elements":[
                {"id":"r1","type":"rectangle","x":0,"y":0,"width":1,"height":1,"futureField":"ignored","nested":{"a":1}}
            ],"futureTopLevelField":true}"""
        val restored = CanvasJson.deserializeElements(json)
        assertEquals(listOf(Element(id = "r1", type = "rectangle", width = 1.0, height = 1.0)), restored)
    }

    @Test
    fun `deserializeElements handles a unicode escape sequence in a string field`() {
        val json = "{\"version\":1,\"elements\":[{\"id\":\"caf\\u00e9\",\"type\":\"rectangle\"}]}"
        assertEquals(listOf("café"), CanvasJson.deserializeElements(json).map { it.id })
    }

    @Test
    fun `deserializeElements degrades to empty on an invalid escape sequence`() {
        val json = "{\"version\":1,\"elements\":[{\"id\":\"bad\\qescape\",\"type\":\"rectangle\"}]}"
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements(json))
    }

    @Test
    fun `deserializeElements degrades to empty on trailing content after the top-level value`() {
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("""{"version":1,"elements":[]}garbage"""))
    }

    @Test
    fun `deserializeElements degrades to empty on a truncated boolean or null literal`() {
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("tru"))
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("nul"))
    }

    @Test
    fun `deserializeElements parses an empty top-level object and an empty elements array without error`() {
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("{}"))
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("""{"version":1,"elements":[]}"""))
    }

    @Test
    fun `deserializeElements degrades to empty when an object key is not followed by a colon`() {
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("""{"version" 1}"""))
    }

    @Test
    fun `deserializeElements degrades to empty when an object is missing a comma between entries`() {
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("""{"version":1 "elements":[]}"""))
    }

    @Test
    fun `deserializeElements degrades to empty when an array is missing a comma between entries`() {
        val json = """{"version":1,"elements":[{"id":"a","type":"rectangle"} {"id":"b","type":"rectangle"}]}"""
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements(json))
    }

    @Test
    fun `deserializeElements degrades to empty on a truncated unicode escape`() {
        // Runtime document text is exactly `"\u12"` — a backslash-u escape with
        // only 2 of the required 4 hex digits before the string (and input) ends.
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("\"\\u12\""))
    }

    @Test
    fun `deserializeElements degrades to empty when a string ends immediately after a lone escaping backslash`() {
        // Runtime document text is exactly `"\` — nothing follows the backslash at all.
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("\"\\"))
    }

    @Test
    fun `deserializeElements degrades to empty when a string is never closed`() {
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("\"abc"))
    }

    @Test
    fun `deserializeElements degrades to empty on a malformed number literal`() {
        val json = """{"version":1,"elements":[{"id":"a","type":"rectangle","x":1.2.3}]}"""
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements(json))
    }

    @Test
    fun `deserializeElements degrades to empty when input runs out right where a specific character was expected`() {
        // Ends immediately after '{' — parseObject's very first inner call, `parseStringLiteral`'s
        // `expect('"')`, sees end-of-input rather than a mismatched-but-present character.
        assertEquals(emptyList<Element>(), CanvasJson.deserializeElements("{"))
    }

    @Test
    fun `deserializeElements parses explicit true and false literal values without throwing`() {
        val json = """{"version":1,"elements":[{"id":"a","type":"rectangle","extraTrue":true,"extraFalse":false}]}"""
        assertEquals(listOf("a"), CanvasJson.deserializeElements(json).map { it.id })
    }

    @Test
    fun `deserializeElements handles whitespace, negative numbers, and true-false-null literals without throwing`() {
        val json =
            "  { \"version\" : 1 , \"elements\" : [ { \"id\" : \"r1\" , \"type\" : \"rectangle\" , " +
                "\"x\" : -1.5 , \"y\" : 0 , \"width\" : 2 , \"height\" : 3 , \"startElementId\" : null } ] }  "
        val restored = CanvasJson.deserializeElements(json)
        assertEquals(listOf(Element(id = "r1", type = "rectangle", x = -1.5, width = 2.0, height = 3.0)), restored)
    }

    @Test
    fun `serialization round-trips rotation and loads pre-rotation files unrotated`() {
        val restored = CanvasJson.deserializeElements(CanvasJson.serializeElements(listOf(barRotated90)))
        assertEquals(listOf(barRotated90), restored)
        val legacy = """{"version":1,"elements":[{"id":"old","type":"rectangle","x":0,"y":0,"width":5,"height":5}]}"""
        assertEquals(0.0, CanvasJson.deserializeElements(legacy).first().rotation, 0.0)
    }

    // --- JSON codec edge cases: escapes, literals, malformed structure ------------------

    @Test
    fun `serialization round-trips ids containing carriage returns, tabs and other control characters`() {
        val tricky = Element(id = "a\rb\tcd", type = "rectangle")
        val json = CanvasJson.serializeElements(listOf(tricky))
        assertEquals(true, json.contains("\\r") && json.contains("\\t") && json.contains("\\u0001"))
        assertEquals(listOf(tricky), CanvasJson.deserializeElements(json))
    }

    @Test
    fun `deserializeElements reads slash and backspace escapes and ignores unknown boolean fields`() {
        val json = """{"version":1,"elements":[{"id":"a\/b\bc","type":"rectangle","locked":true,"hidden":false}]}"""
        assertEquals("a/b\bc", CanvasJson.deserializeElements(json).single().id)
    }

    @Test
    fun `deserializeElements skips an empty element object and reads nested or empty unknown arrays`() {
        val json = """{"version":1,"elements":[{},{"id":"ok","type":"ellipse","tags":[[],[1,2]]}],"extra":[]}"""
        assertEquals(listOf("ok"), CanvasJson.deserializeElements(json).map { it.id })
    }

    @Test
    fun `deserializeElements returns an empty list for malformed structure or a non-object document`() {
        val malformed =
            listOf(
                """{"version":1 "elements":[]}""", // missing comma between object members
                """{"version":1,"elements":[1 2]}""", // missing comma between array items
                """{"version":1,"elements":[{"id":"x","type":"rectangle","x":-}]}""", // invalid number
                """{"version":1,"elements":[{"id":"x","type":"rectangle","flag":tru}]}""", // bad literal
                "42", // a bare number, read right to the end of input
            )
        for (json in malformed) {
            assertEquals(json, emptyList<Element>(), CanvasJson.deserializeElements(json))
        }
    }

    // --- styles (FR19) -------------------------------------------------------------------

    @Test
    fun `styles survive a save and load`() {
        val style = ShapeStyle(StyleColor.LIGHT_BLUE, 0.5, FillStyle.PATTERN, DashStyle.DOTTED, SizeStyle.XL)
        val element = Element(id = "s", type = "ellipse", width = 10.0, height = 10.0, style = style)
        assertEquals(listOf(element), CanvasJson.deserializeElements(CanvasJson.serializeElements(listOf(element))))
    }

    @Test
    fun `canvases saved before styles existed load with their original look`() {
        val json = """{"version":1,"elements":[{"id":"old","type":"rectangle","width":5,"height":5}]}"""
        assertEquals(ShapeStyle.LEGACY, CanvasJson.deserializeElements(json).single().style)
    }

    @Test
    fun `unknown style ids fall back and opacity is clamped`() {
        val json =
            """{"version":1,"elements":[{"id":"s","type":"rectangle","width":5,"height":5,""" +
                """"style":{"color":"magenta","opacity":9,"fill":"glitter","dash":"wavy","size":"xxl"}}]}"""
        assertEquals(ShapeStyle.LEGACY, CanvasJson.deserializeElements(json).single().style)
    }

    @Test
    fun `a style without an opacity loads opaque`() {
        val json = """{"version":1,"elements":[{"id":"s","type":"rectangle","width":5,"height":5,"style":{"color":"red"}}]}"""
        assertEquals(ShapeStyle.LEGACY.copy(color = StyleColor.RED), CanvasJson.deserializeElements(json).single().style)
    }
}
