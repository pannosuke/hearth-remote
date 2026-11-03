package net.pcg.hearth.remote.protocol

internal sealed interface Json {
    data class Obj(val map: Map<String, Json>) : Json
    data class Arr(val items: List<Json>) : Json
    data class Text(val value: String) : Json
    data class Num(val raw: String) : Json
    data class Bool(val value: Boolean) : Json
    data object Null : Json
}

internal fun parseJson(raw: String): Json = JsonParser(raw).parse()

internal fun Json.obj(): Map<String, Json> = (this as Json.Obj).map

internal fun Json.field(name: String): Json? = (this as? Json.Obj)?.map?.get(name)

internal fun Json.textOrNull(): String? = (this as? Json.Text)?.value

internal fun Json.boolOrNull(): Boolean? = (this as? Json.Bool)?.value

internal fun Json.arrayOrEmpty(): List<Json> = (this as? Json.Arr)?.items.orEmpty()

internal fun Json.longOrNull(): Long? {
    val raw = (this as? Json.Num)?.raw ?: return null
    val whole = raw.substringBefore('.').substringBefore('e').substringBefore('E')
    return whole.toLongOrNull()
}

internal fun Json.intOrNull(): Int? = longOrNull()?.toInt()

internal fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (ch.code < 0x20) {
                out.append("\\u")
                out.append(ch.code.toString(16).padStart(4, '0'))
            } else {
                out.append(ch)
            }
        }
    }
    out.append('"')
    return out.toString()
}

private class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): Json {
        skip()
        val value = value()
        skip()
        return value
    }

    private fun value(): Json {
        skip()
        if (index >= text.length) fail("end of json")
        return when (val ch = text[index]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> Json.Text(stringValue())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (ch == '-' || ch.isDigit()) numberValue() else fail("value")
        }
    }

    private fun objectValue(): Json.Obj {
        expect('{')
        val map = LinkedHashMap<String, Json>()
        skip()
        if (peek('}')) {
            index++
            return Json.Obj(map)
        }
        while (true) {
            skip()
            val key = stringValue()
            skip()
            expect(':')
            map[key] = value()
            skip()
            when {
                peek(',') -> index++
                peek('}') -> {
                    index++
                    return Json.Obj(map)
                }
                else -> fail("object")
            }
        }
    }

    private fun arrayValue(): Json.Arr {
        expect('[')
        val items = ArrayList<Json>()
        skip()
        if (peek(']')) {
            index++
            return Json.Arr(items)
        }
        while (true) {
            items.add(value())
            skip()
            when {
                peek(',') -> index++
                peek(']') -> {
                    index++
                    return Json.Arr(items)
                }
                else -> fail("array")
            }
        }
    }

    private fun stringValue(): String {
        expect('"')
        val out = StringBuilder()
        while (index < text.length) {
            when (val ch = text[index++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (index >= text.length) fail("escape")
                    when (val esc = text[index++]) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) fail("unicode")
                            val hex = text.substring(index, index + 4)
                            out.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> fail("escape")
                    }
                }
                else -> out.append(ch)
            }
        }
        fail("string")
    }

    private fun numberValue(): Json.Num {
        val start = index
        if (peek('-')) index++
        if (index >= text.length || !text[index].isDigit()) fail("number")
        while (index < text.length && text[index].isDigit()) index++
        if (peek('.')) {
            index++
            while (index < text.length && text[index].isDigit()) index++
        }
        if (peek('e') || peek('E')) {
            index++
            if (peek('+') || peek('-')) index++
            while (index < text.length && text[index].isDigit()) index++
        }
        return Json.Num(text.substring(start, index))
    }

    private fun literal(word: String, value: Json): Json {
        if (!text.startsWith(word, index)) fail(word)
        index += word.length
        return value
    }

    private fun expect(ch: Char) {
        if (index >= text.length || text[index] != ch) fail("'$ch'")
        index++
    }

    private fun peek(ch: Char): Boolean = index < text.length && text[index] == ch

    private fun skip() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun fail(what: String): Nothing {
        throw IllegalArgumentException("Invalid JSON near $what at $index")
    }
}
