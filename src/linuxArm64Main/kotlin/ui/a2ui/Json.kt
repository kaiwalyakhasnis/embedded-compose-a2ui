package ui.a2ui

/** A JSON value: the messages of A2UI and its data model. */
sealed interface Json {
    data class Str(val value: String) : Json
    data class Num(val value: Double) : Json
    data class Bool(val value: Boolean) : Json
    data object Null : Json
    data class Arr(val items: List<Json>) : Json
    /** Keys keep their order. [complete] is false for an object cut off by [parsePrefix]. */
    data class Obj(val fields: Map<String, Json>, val complete: Boolean = true) : Json {
        operator fun get(key: String) = fields[key]
    }

    override fun toString(): String

    companion object {
        /** Parses a whole document; throws [IllegalArgumentException] on bad JSON. */
        fun parse(text: String): Json = Parser(text, partial = false).document()

        /**
         * Parses the beginning of a document that is still arriving (a model's
         * streamed reply): what is complete, plus the open objects and arrays
         * around it ([Obj.complete] false). Unfinished strings, numbers and
         * members are left out. Null when nothing is complete yet.
         */
        fun parsePrefix(text: String): Json? = try {
            Parser(text, partial = true).value()
        } catch (e: IllegalArgumentException) {
            null
        }

        fun obj(vararg fields: Pair<String, Json?>) =
            Obj(LinkedHashMap<String, Json>().apply { for ((k, v) in fields) if (v != null) put(k, v) })

        fun str(s: String) = Str(s)
        fun num(n: Number) = Num(n.toDouble())
    }
}

val Json?.string: String? get() = when (this) {
    is Json.Str -> value
    is Json.Num -> number(value)
    is Json.Bool -> value.toString()
    else -> null
}
val Json?.double: Double? get() = when (this) {
    is Json.Num -> value
    is Json.Str -> value.trim().toDoubleOrNull()
    else -> null
}
val Json?.bool: Boolean? get() = when (this) {
    is Json.Bool -> value
    is Json.Str -> value == "true"
    is Json.Num -> value != 0.0
    else -> null
}
val Json?.obj: Json.Obj? get() = this as? Json.Obj
val Json?.arr: List<Json>? get() = (this as? Json.Arr)?.items

/** Whole numbers without ".0". */
internal fun number(d: Double): String =
    if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

/** Compact JSON text. */
fun Json.encode(): String = StringBuilder().also { write(it) }.toString()

private fun Json.write(out: StringBuilder) {
    when (this) {
        is Json.Str -> quote(value, out)
        is Json.Num -> out.append(if (value.isFinite()) number(value) else "null")
        is Json.Bool -> out.append(value)
        Json.Null -> out.append("null")
        is Json.Arr -> {
            out.append('[')
            items.forEachIndexed { i, v -> if (i > 0) out.append(','); v.write(out) }
            out.append(']')
        }
        is Json.Obj -> {
            out.append('{')
            var first = true
            for ((k, v) in fields) {
                if (!first) out.append(',')
                first = false
                quote(k, out)
                out.append(':')
                v.write(out)
            }
            out.append('}')
        }
    }
}

private fun quote(s: String, out: StringBuilder) {
    out.append('"')
    for (c in s) when {
        c == '"' -> out.append("\\\"")
        c == '\\' -> out.append("\\\\")
        c == '\n' -> out.append("\\n")
        c == '\r' -> out.append("\\r")
        c == '\t' -> out.append("\\t")
        c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
        else -> out.append(c)
    }
    out.append('"')
}

/** Thrown inside [Parser] when a partial document ends in the middle of a value. */
private object Cut : RuntimeException()

private class Parser(private val s: String, private val partial: Boolean) {
    private var i = 0

    fun document(): Json {
        val v = value()
        space()
        require(i == s.length) { "extra text at $i" }
        return v
    }

    private fun space() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun end(): Nothing = if (partial) throw Cut else throw IllegalArgumentException("unexpected end")

    fun value(): Json {
        space()
        if (i >= s.length) end()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> Json.Str(string())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c' at $i")
        }
    }

    private fun literal(word: String, v: Json): Json {
        if (s.length - i < word.length) { if (word.startsWith(s.substring(i))) end() }
        require(s.startsWith(word, i)) { "bad literal at $i" }
        i += word.length
        return v
    }

    private fun num(): Json {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        if (i >= s.length && partial) throw Cut  // more digits may follow
        return Json.Num(s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $start"))
    }

    private fun string(): String {
        i++  // "
        val out = StringBuilder()
        while (true) {
            if (i >= s.length) end()
            when (val c = s[i++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i >= s.length) end()
                    when (val e = s[i++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'u' -> {
                            if (i + 4 > s.length) end()
                            out.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(e)
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun obj(): Json.Obj {
        i++  // {
        val fields = LinkedHashMap<String, Json>()
        while (true) {
            space()
            if (i >= s.length) return cut(fields)
            if (s[i] == '}') { i++; return Json.Obj(fields) }
            if (s[i] == ',') { i++; continue }
            require(s[i] == '"') { "expected a key at $i" }
            val key = try { string() } catch (e: Cut) { return cut(fields) }
            space()
            if (i >= s.length) return cut(fields)
            require(s[i] == ':') { "expected ':' at $i" }
            i++
            try {
                fields[key] = value()
            } catch (e: Cut) {
                return cut(fields)
            }
        }
    }

    private fun cut(fields: Map<String, Json>): Json.Obj = if (partial) Json.Obj(fields, complete = false) else end()

    private fun arr(): Json.Arr {
        i++  // [
        val items = ArrayList<Json>()
        while (true) {
            space()
            if (i >= s.length) { if (partial) return Json.Arr(items) else end() }
            if (s[i] == ']') { i++; return Json.Arr(items) }
            if (s[i] == ',') { i++; continue }
            try {
                items += value()
            } catch (e: Cut) {
                return Json.Arr(items)
            }
        }
    }
}
