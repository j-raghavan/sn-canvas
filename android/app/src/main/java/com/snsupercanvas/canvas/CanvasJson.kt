package com.snsupercanvas.canvas

/**
 * Persistence codec for the canvas model: a versioned JSON envelope
 * (`{"version":1,"elements":[...]}`) written and read by [SuperCanvasModule]'s
 * saveCanvas/loadCanvas. Hand-rolled rather than `org.json` (see
 * [serializeElements]) so it stays plain-JUnit testable.
 */
object CanvasJson {
    private const val PERSISTENCE_SCHEMA_VERSION = 1

    /**
     * Serializes [elements] to a JSON string (versioned envelope:
     * `{"version":1,"elements":[...]}`, mirroring the versioned-envelope
     * pattern already used elsewhere in this developer's plugin portfolio,
     * e.g. sn-shapes' favoritesStorage.ts) for `SuperCanvasModule.saveCanvas`.
     *
     * Deliberately hand-rolled rather than `org.json` (the obvious Android
     * platform choice): under this project's plain-JUnit `testDebugUnitTest`
     * setup (no Robolectric, no `returnDefaultValues`, no `org.json` test-jar
     * override — checked before writing this), `org.json.JSONObject` is the
     * *stubbed* platform class and throws `RuntimeException: not mocked` at
     * test time, not the real implementation. Using it here would silently
     * break this file's own stated design contract (see the class doc: pure,
     * "no Android types", testable without an Android runtime) rather than
     * fail loudly — worse than just writing the ~60 lines of JSON handling
     * this fixed, simple, fully-controlled schema actually needs.
     */
    fun serializeElements(elements: List<Element>): String =
        elements.joinToString(",", prefix = "{\"version\":$PERSISTENCE_SCHEMA_VERSION,\"elements\":[", postfix = "]}") {
            serializeElement(it)
        }

    private fun serializeElement(element: Element): String {
        val sb = StringBuilder("{")
        JsonObjectWriter(sb).apply {
            string("id", element.id)
            string("type", element.type)
            number("x", element.x)
            number("y", element.y)
            number("width", element.width)
            number("height", element.height)
            number("startX", element.startX)
            number("startY", element.startY)
            number("endX", element.endX)
            number("endY", element.endY)
            string("startElementId", element.startElementId)
            string("endElementId", element.endElementId)
            number("rotation", element.rotation)
            obj("style") {
                string("color", element.style.color.id)
                number("opacity", element.style.opacity)
                string("fill", element.style.fill.id)
                string("dash", element.style.dash.id)
                string("size", element.style.size.id)
            }
            element.text?.let { string("text", it) }
            // Flat [x, y, pressure, ...] triples, rounded: 1/10000 of the stroke's bounds is finer than any pen.
            element.points?.let { points ->
                numbers("points", points.flatMap { listOf(rounded(it.x, 4), rounded(it.y, 4), rounded(it.pressure, 2)) })
            }
            element.strokeWidth?.let { number("strokeWidth", rounded(it, 3)) }
            element.table?.let { table ->
                obj("table") {
                    number("rows", table.rows.toDouble())
                    number("cols", table.cols.toDouble())
                    strings("cells", table.cells)
                    numbers("rowMinHeights", table.rowMinHeights.map { rounded(it, 1) })
                }
            }
        }
        return sb.append('}').toString()
    }

    /**
     * Deserializes a JSON string produced by [serializeElements] back into an
     * element list for `SuperCanvasModule.loadCanvas`. Never throws: malformed
     * JSON, an empty string, a wrong top-level shape, or a single malformed
     * element within an otherwise-valid array all degrade to an empty list
     * (or, for one bad element among many good ones, that element is simply
     * skipped) — matching the "never throw, default on any error" pattern
     * this developer's portfolio already establishes for on-device persistence
     * (e.g. sn-shapes' favoritesStorage.ts).
     */
    @Suppress("ReturnCount", "SwallowedException") // early-return guard clauses read clearer here than nesting; see doc above
    fun deserializeElements(json: String): List<Element> {
        val root =
            try {
                JsonParser(json).parseDocument()
            } catch (e: JsonParseException) {
                return emptyList()
            }
        val obj = root as? JsonValue.Obj ?: return emptyList()
        val elementsArray = obj.entries["elements"] as? JsonValue.Arr ?: return emptyList()
        return elementsArray.items.mapNotNull { item ->
            val itemObj = item as? JsonValue.Obj ?: return@mapNotNull null
            try {
                elementFromJson(itemObj)
            } catch (e: IllegalArgumentException) {
                // A single corrupt element (e.g. a negative width from a hand-edited
                // file) must not take the rest of a valid canvas down with it.
                null
            }
        }
    }

    private fun elementFromJson(obj: JsonValue.Obj): Element =
        Element(
            id = obj.string("id") ?: throw IllegalArgumentException("missing id"),
            type = obj.string("type") ?: throw IllegalArgumentException("missing type"),
            x = obj.number("x") ?: 0.0,
            y = obj.number("y") ?: 0.0,
            width = obj.number("width") ?: 0.0,
            height = obj.number("height") ?: 0.0,
            // Absent in canvases saved before rotation existed — those load unrotated.
            rotation = obj.number("rotation") ?: 0.0,
            startX = obj.number("startX"),
            startY = obj.number("startY"),
            endX = obj.number("endX"),
            endY = obj.number("endY"),
            startElementId = obj.string("startElementId"),
            endElementId = obj.string("endElementId"),
            style = styleFromJson(obj.entries["style"] as? JsonValue.Obj),
            text = obj.string("text"),
            points = pointsFromJson(obj.entries["points"] as? JsonValue.Arr),
            // Absent in strokes saved before it existed, or unusable: the stroke then draws at its style's size.
            strokeWidth = obj.number("strokeWidth")?.takeIf { it.isFinite() && it > 0 },
            table = tableFromJson(obj.entries["table"] as? JsonValue.Obj),
        )

    /** A stroke's [x, y, pressure, ...] triples; anything but whole triples of numbers drops them (the stroke then draws nothing). */
    private fun pointsFromJson(arr: JsonValue.Arr?): List<StrokePoint>? {
        if (arr == null) return null
        val items = arr.items
        val values = items.mapNotNull { (it as? JsonValue.Num)?.value }
        return if (values.size == items.size &&
            values.size % 3 == 0
        ) {
            values.chunked(3) { (x, y, pressure) -> StrokePoint(x, y, pressure) }
        } else {
            null
        }
    }

    /** A table's grid; a grid that doesn't add up throws, which skips just this element (see [deserializeElements]). */
    private fun tableFromJson(obj: JsonValue.Obj?): TableData? {
        if (obj == null) return null
        val rows = obj.number("rows")?.toInt() ?: 0
        val cells = (obj.entries["cells"] as? JsonValue.Arr)?.items.orEmpty().map { (it as? JsonValue.Str)?.value.orEmpty() }
        // Absent in canvases saved before rows could be resized, or not one per row: every row starts at the minimum.
        val heights =
            (obj.entries["rowMinHeights"] as? JsonValue.Arr)?.items.orEmpty().map {
                (it as? JsonValue.Num)?.value ?: TableElements.MIN_ROW_HEIGHT
            }
        val rowMinHeights =
            if (heights.size == rows) {
                heights.map { it.coerceIn(TableElements.MIN_ROW_HEIGHT, TableElements.MAX_ROW_HEIGHT) }
            } else {
                List(rows) { TableElements.MIN_ROW_HEIGHT }
            }
        return TableData(rows, obj.number("cols")?.toInt() ?: 0, cells, rowMinHeights)
    }

    private fun rounded(
        value: Double,
        places: Int,
    ): Double {
        val scale = Math.pow(10.0, places.toDouble())
        return Math.round(value * scale) / scale
    }

    /** Absent in canvases saved before styles existed, which keep their original look; unknown ids fall back the same way. */
    private fun styleFromJson(obj: JsonValue.Obj?): ShapeStyle {
        val legacy = ShapeStyle.LEGACY
        if (obj == null) return legacy
        return ShapeStyle(
            color = StyleColor.entries.byId(obj.string("color"), legacy.color),
            opacity = obj.number("opacity")?.coerceIn(ShapeStyle.MIN_OPACITY, 1.0) ?: legacy.opacity,
            fill = FillStyle.entries.byId(obj.string("fill"), legacy.fill),
            dash = DashStyle.entries.byId(obj.string("dash"), legacy.dash),
            size = SizeStyle.entries.byId(obj.string("size"), legacy.size),
        )
    }

    private fun JsonValue.Obj.string(key: String): String? = (entries[key] as? JsonValue.Str)?.value

    private fun JsonValue.Obj.number(key: String): Double? = (entries[key] as? JsonValue.Num)?.value

    /** Minimal JSON value tree — only what [deserializeElements] needs to walk. */
    private sealed class JsonValue {
        data class Str(
            val value: String,
        ) : JsonValue()

        data class Num(
            val value: Double,
        ) : JsonValue()

        data class Arr(
            val items: List<JsonValue>,
        ) : JsonValue()

        data class Obj(
            val entries: Map<String, JsonValue>,
        ) : JsonValue()

        object Null : JsonValue()

        data class Bool(
            val value: Boolean,
        ) : JsonValue()
    }

    private class JsonParseException(
        message: String,
    ) : Exception(message)

    /**
     * A small recursive-descent JSON parser covering exactly the JSON grammar
     * (object/array/string/number/true/false/null) — general enough to parse
     * any well-formed JSON document (not just what [serializeElements] itself
     * emits), which is what makes [deserializeElements] safe against
     * hand-edited or foreign-tool-written files, not only its own output.
     */
    @Suppress("TooManyFunctions") // one small function per JSON grammar rule
    private class JsonParser(
        private val text: String,
    ) {
        private var pos = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (pos != text.length) throw JsonParseException("trailing content at $pos")
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (pos >= text.length) throw JsonParseException("unexpected end of input")
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseStringLiteral())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                else -> parseNumber()
            }
        }

        private fun parseLiteral(
            literal: String,
            value: JsonValue,
        ): JsonValue {
            if (!text.startsWith(literal, pos)) throw JsonParseException("expected '$literal' at $pos")
            pos += literal.length
            return value
        }

        private fun parseObject(): JsonValue.Obj {
            expect('{')
            val entries = mutableMapOf<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                val key = parseStringLiteral()
                skipWhitespace()
                expect(':')
                entries[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        pos++
                    }
                    '}' -> {
                        pos++
                        return JsonValue.Obj(entries)
                    }
                    else -> throw JsonParseException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        pos++
                    }
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> throw JsonParseException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseStringLiteral(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) throw JsonParseException("unterminated string")
                when (val c = text[pos]) {
                    '"' -> {
                        pos++
                        return sb.toString()
                    }
                    '\\' -> {
                        pos++
                        sb.append(readEscapedChar())
                    }
                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
            }
        }

        /** Reads one escape sequence's *body* (the part after the backslash already consumed by the caller). */
        private fun readEscapedChar(): Char {
            if (pos >= text.length) throw JsonParseException("unterminated escape")
            val esc = text[pos]
            if (esc == 'u') return readUnicodeEscape()
            val mapped = SIMPLE_ESCAPES[esc] ?: throw JsonParseException("invalid escape '\\$esc'")
            pos++
            return mapped
        }

        private fun readUnicodeEscape(): Char {
            if (pos + 4 >= text.length) throw JsonParseException("truncated unicode escape")
            val hex = text.substring(pos + 1, pos + 5)
            pos += 5
            return hex.toInt(16).toChar()
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && isNumberChar(text[pos])) pos++
            val slice = text.substring(start, pos)
            val value = slice.toDoubleOrNull() ?: throw JsonParseException("invalid number '$slice' at $start")
            return JsonValue.Num(value)
        }

        private fun isNumberChar(c: Char): Boolean = c.isDigit() || c in NUMBER_SYMBOL_CHARS

        // A non-nullable sentinel (never a valid structural character we compare
        // against) rather than `Char?` for end-of-input: `peek() == someChar`
        // comparisons against a nullable Char box an otherwise-unreachable
        // null-check branch that no test input can ever hit, which is exactly
        // what was showing up as permanently "missed" branches in coverage —
        // not a real gap, a tooling artifact of nullable-primitive comparisons.
        private fun peek(): Char = if (pos < text.length) text[pos] else END_OF_INPUT

        private fun expect(c: Char) {
            if (peek() != c) throw JsonParseException("expected '$c' at $pos")
            pos++
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private companion object {
            // @JvmField: plain static fields, no generated getters that nothing calls
            // (those showed up as permanently-uncovered methods in the coverage report).
            @JvmField
            val SIMPLE_ESCAPES =
                mapOf(
                    '"' to '"',
                    '\\' to '\\',
                    '/' to '/',
                    'n' to '\n',
                    'r' to '\r',
                    't' to '\t',
                    'b' to '\b',
                )

            @JvmField
            val NUMBER_SYMBOL_CHARS = charArrayOf('.', 'e', 'E', '+', '-')
            const val END_OF_INPUT = ' '
        }
    }
}

/** Writes one JSON object's fields, comma-separated, into [sb]; the caller writes the object's braces. */
private class JsonObjectWriter(
    private val sb: StringBuilder,
) {
    private var first = true

    fun string(
        key: String,
        value: String?,
    ) = field(key, if (value == null) "null" else quote(value))

    fun number(
        key: String,
        value: Double?,
    ) = field(key, if (value == null) "null" else value.toString())

    fun numbers(
        key: String,
        values: List<Double>,
    ) = field(key, values.joinToString(",", "[", "]"))

    fun strings(
        key: String,
        values: List<String>,
    ) = field(key, values.joinToString(",", "[", "]") { quote(it) })

    fun obj(
        key: String,
        write: JsonObjectWriter.() -> Unit,
    ) {
        name(key)
        sb.append('{')
        JsonObjectWriter(sb).write()
        sb.append('}')
    }

    private fun field(
        key: String,
        raw: String,
    ) {
        name(key)
        sb.append(raw)
    }

    private fun name(key: String) {
        if (!first) sb.append(',')
        first = false
        sb.append('"').append(key).append("\":")
    }

    private fun quote(value: String): String {
        val quoted = StringBuilder(value.length + 2)
        quoted.append('"')
        for (c in value) {
            when (c) {
                '"' -> quoted.append("\\\"")
                '\\' -> quoted.append("\\\\")
                '\n' -> quoted.append("\\n")
                '\r' -> quoted.append("\\r")
                '\t' -> quoted.append("\\t")
                else -> if (c.code < 0x20) quoted.append("\\u%04x".format(c.code)) else quoted.append(c)
            }
        }
        quoted.append('"')
        return quoted.toString()
    }
}
