package ui.a2ui

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Dynamic values: a literal, {"path": pointer} into the data model (relative
 * pointers inside a template's item), or {"call": name, "args": {...}}, a
 * function of the basic catalog. Reading [Surface.data] here is what makes a
 * bound component follow the data.
 */
internal class Values(val surface: Surface, val scope: String) {
    fun resolve(v: Json?): Json? {
        val o = v as? Json.Obj ?: return v
        o["path"].string?.let { if (o.fields.size == 1) return surface.get(resolvePath(it, scope)) }
        o["call"].string?.let { return call(it, o["args"].obj ?: Json.Obj(emptyMap())) }
        return v
    }

    fun string(v: Json?): String? = when (val r = resolve(v)) {
        is Json.Arr -> r.items.joinToString(", ") { it.string ?: "" }
        else -> r.string
    }

    fun double(v: Json?) = resolve(v).double
    fun bool(v: Json?) = resolve(v).bool

    /** Where a two-way bound property writes ({"path": ...}), or null for a literal. */
    fun pointer(v: Json?): String? = (v as? Json.Obj)?.get("path").string?.let { resolvePath(it, scope) }

    /** All of a Button's or field's "checks" pass. */
    fun checksPass(checks: Json?): Boolean = checks.arr?.all { bool(it) != false } ?: true

    private fun arg(args: Json.Obj, vararg names: String): Json? {
        for (n in names) args[n]?.let { return resolve(it) }
        return null
    }

    private fun call(name: String, args: Json.Obj): Json? = when (name) {
        "formatString" -> Json.Str(interpolate(arg(args, "value", "format", "template").string ?: ""))
        "formatNumber" -> {
            val n = arg(args, "value").double
            val digits = arg(args, "decimals", "maximumFractionDigits", "fractionDigits").double?.toInt()
            n?.let { Json.Str(formatNumber(it, digits, arg(args, "grouping", "useGrouping").bool != false)) }
        }
        "formatCurrency" -> {
            val n = arg(args, "value", "amount").double
            val code = arg(args, "currency", "currencyCode").string ?: "USD"
            n?.let { Json.Str(currency(code) + formatNumber(it, if (code == "JPY") 0 else 2, true)) }
        }
        "formatDate" -> arg(args, "value", "date")?.let { Json.Str(it.string ?: "") }
        "pluralize" -> {
            val n = arg(args, "value", "count").double ?: 0.0
            val word = if (n == 1.0) arg(args, "one", "singular").string else arg(args, "other", "plural").string
            Json.Str(word ?: "")
        }
        "required" -> Json.Bool(arg(args, "value").let { it != null && it != Json.Null && it.string?.isNotBlank() != false && it.arr?.isNotEmpty() != false })
        "email" -> Json.Bool(arg(args, "value").string?.let { Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(it) } ?: false)
        "regex" -> Json.Bool(runCatching { Regex(arg(args, "pattern").string ?: "").matches(arg(args, "value").string ?: "") }.getOrDefault(true))
        "length" -> {
            val len = arg(args, "value").let { it.arr?.size ?: it.string?.length ?: 0 }
            val min = arg(args, "min").double ?: 0.0
            val max = arg(args, "max").double ?: Double.MAX_VALUE
            Json.Bool(len >= min && len <= max)
        }
        "numeric" -> {
            val n = arg(args, "value").double
            Json.Bool(n != null && n >= (arg(args, "min").double ?: -Double.MAX_VALUE) && n <= (arg(args, "max").double ?: Double.MAX_VALUE))
        }
        "and" -> Json.Bool(arg(args, "values").arr?.all { resolve(it).bool == true } ?: true)
        "or" -> Json.Bool(arg(args, "values").arr?.any { resolve(it).bool == true } ?: false)
        "not" -> Json.Bool(arg(args, "value").bool != true)
        "now" -> Json.Str(isoNow())
        else -> null
    }

    /** "${/pointer}", "${relative}" and "${now()}"; "\${" is a literal "${". */
    private fun interpolate(template: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            if (template.startsWith("\\\${", i)) { out.append("\${"); i += 3; continue }
            if (template.startsWith("\${", i)) {
                val end = template.indexOf('}', i)
                if (end > 0) {
                    val expr = template.substring(i + 2, end).trim()
                    out.append(
                        if (expr.endsWith("()")) call(expr.dropLast(2), Json.Obj(emptyMap())).string ?: ""
                        else surface.get(resolvePath(expr, scope)).string ?: ""
                    )
                    i = end + 1
                    continue
                }
            }
            out.append(template[i++])
        }
        return out.toString()
    }
}

private fun currency(code: String) = when (code.uppercase()) {
    "USD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    "JPY" -> "¥"
    "INR" -> "₹"
    else -> "$code "
}

internal fun formatNumber(n: Double, digits: Int?, grouping: Boolean): String {
    val d = digits ?: if (n == kotlin.math.floor(n)) 0 else 2
    var scale = 1L
    repeat(d) { scale *= 10 }
    val total = abs(n * scale).roundToLong()
    var whole = (total / scale).toString()
    if (grouping) whole = whole.reversed().chunked(3).joinToString(",").reversed()
    val frac = if (d > 0) "." + (total % scale).toString().padStart(d, '0') else ""
    return (if (n < 0 && total != 0L) "-" else "") + whole + frac
}
