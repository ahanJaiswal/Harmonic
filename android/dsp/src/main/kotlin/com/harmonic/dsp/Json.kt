package com.harmonic.dsp

/** Minimal JSON writer/reader for the feature and golden documents. Numbers stay Double. */
object Json {
    fun write(value: Any?, pretty: Boolean = false, indent: Int = 0): String {
        val pad = if (pretty) "  ".repeat(indent) else ""
        val nl = if (pretty) "\n" else ""
        return when (value) {
            null -> "null"
            is String -> quote(value)
            is Boolean -> if (value) "true" else "false"
            is Int -> value.toString()
            is Long -> value.toString()
            is Double -> formatNumber(value)
            is Float -> formatNumber(value.toDouble())
            is Map<*, *> -> {
                if (value.isEmpty()) return "{}"
                val body = value.entries.joinToString(if (pretty) ",\n" else ",") { (k, v) ->
                    val child = write(v, pretty, indent + 1)
                    if (pretty) "  ".repeat(indent + 1) + quote(k.toString()) + ": " + child
                    else quote(k.toString()) + ":" + child
                }
                "{$nl$body$nl$pad}"
            }
            is List<*> -> {
                if (value.isEmpty()) return "[]"
                val body = value.joinToString(if (pretty) ",\n" else ",") {
                    val child = write(it, pretty, indent + 1)
                    if (pretty) "  ".repeat(indent + 1) + child else child
                }
                "[$nl$body$nl$pad]"
            }
            is DoubleArray -> write(value.toList(), pretty, indent)
            is IntArray -> write(value.toList(), pretty, indent)
            else -> quote(value.toString())
        }
    }

    fun parse(text: String): Any? = Parser(text).parseValue()

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun list(value: Any?): List<Any?> = value as List<Any?>

    fun num(value: Any?): Double = when (value) {
        is Double -> value
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is String -> value.toDouble()
        else -> throw IllegalArgumentException("Not a number: $value")
    }

    fun str(value: Any?): String = value as String

    private fun formatNumber(v: Double): String {
        if (v.isNaN()) return "null"
        if (v.isInfinite()) return if (v > 0) "1e309" else "-1e309"
        val asLong = v.toLong()
        if (v == asLong.toDouble() && absFinite(v) < 1e15) return asLong.toString()
        val s = String.format(java.util.Locale.US, "%.12g", v)
        return s
    }

    private fun absFinite(v: Double) = if (v < 0) -v else v

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private class Parser(val s: String) {
        var i = 0
        fun parseValue(): Any? {
            skip()
            if (i >= s.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): LinkedHashMap<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skip()
            if (peek('}')) { i++; return map }
            while (true) {
                skip()
                val key = parseString()
                skip(); expect(':')
                map[key] = parseValue()
                skip()
                if (peek('}')) { i++; break }
                expect(',')
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skip()
            if (peek(']')) { i++; return list }
            while (true) {
                list.add(parseValue())
                skip()
                if (peek(']')) { i++; break }
                expect(',')
            }
            return list
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        sb.append(when (e) {
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                val hex = s.substring(i, i + 4)
                                i += 4
                                hex.toInt(16).toChar()
                            }
                            else -> e
                        })
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            return s.substring(start, i).toDouble()
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
        private fun peek(c: Char) = i < s.length && s[i] == c
        private fun expect(c: Char) {
            skip()
            if (i >= s.length || s[i] != c) {
                throw IllegalArgumentException("Expected '$c' at $i")
            }
            i++
        }
    }
}
