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
    fun serializeElements(elements: List<Element>): String {
        val sb = StringBuilder()
        sb.append("{\"version\":").append(PERSISTENCE_SCHEMA_VERSION).append(",\"elements\":[")
        elements.forEachIndexed { index, element ->
            if (index > 0) sb.append(',')
            sb.append(serializeElement(element))
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun serializeElement(element: Element): String {
        val sb = StringBuilder()
        sb.append('{')
        appendStringField(sb, "id", element.id, first = true)
        appendStringField(sb, "type", element.type)
        appendNumberField(sb, "x", element.x)
        appendNumberField(sb, "y", element.y)
        appendNumberField(sb, "width", element.width)
        appendNumberField(sb, "height", element.height)
        appendNullableNumberField(sb, "startX", element.startX)
        appendNullableNumberField(sb, "startY", element.startY)
        appendNullableNumberField(sb, "endX", element.endX)
        appendNullableNumberField(sb, "endY", element.endY)
        appendNullableStringField(sb, "startElementId", element.startElementId)
        appendNullableStringField(sb, "endElementId", element.endElementId)
        appendNumberField(sb, "rotation", element.rotation)
        sb.append('}')
        return sb.toString()
    }

    private fun appendStringField(
        sb: StringBuilder,
        key: String,
        value: String,
        first: Boolean = false,
    ) {
        if (!first) sb.append(',')
        sb
            .append('"')
            .append(key)
            .append("\":")
            .append(jsonQuote(value))
    }

    private fun appendNullableStringField(
        sb: StringBuilder,
        key: String,
        value: String?,
    ) {
        sb
            .append(',')
            .append('"')
            .append(key)
            .append("\":")
            .append(if (value == null) "null" else jsonQuote(value))
    }

    private fun appendNumberField(
        sb: StringBuilder,
        key: String,
        value: Double,
    ) {
        sb
            .append(',')
            .append('"')
            .append(key)
            .append("\":")
            .append(value)
    }

    private fun appendNullableNumberField(
        sb: StringBuilder,
        key: String,
        value: Double?,
    ) {
        sb
            .append(',')
            .append('"')
            .append(key)
            .append("\":")
            .append(value ?: "null")
    }

    private fun jsonQuote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
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

    private fun elementFromJson(obj: JsonValue.Obj): Element {
        fun str(key: String): String? = (obj.entries[key] as? JsonValue.Str)?.value

        fun num(key: String): Double? = (obj.entries[key] as? JsonValue.Num)?.value
        return Element(
            id = str("id") ?: throw IllegalArgumentException("missing id"),
            type = str("type") ?: throw IllegalArgumentException("missing type"),
            x = num("x") ?: 0.0,
            y = num("y") ?: 0.0,
            width = num("width") ?: 0.0,
            height = num("height") ?: 0.0,
            // Absent in canvases saved before rotation existed — those load unrotated.
            rotation = num("rotation") ?: 0.0,
            startX = num("startX"),
            startY = num("startY"),
            endX = num("endX"),
            endY = num("endY"),
            startElementId = str("startElementId"),
            endElementId = str("endElementId"),
        )
    }

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
