package com.khalsatorrent.cs3builder.codegen

/** Helpers for emitting Kotlin source safely. */
internal object KotlinText {

    private val KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
        "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
        "typeof", "val", "var", "when", "while"
    )

    /** Escapes [s] for use inside a Kotlin "double-quoted" string literal (no surrounding quotes). */
    fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '§' -> append("\\u00A7")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }

    /** A complete Kotlin string literal. */
    fun lit(s: String) = "\"" + esc(s) + "\""

    /** Turns a JSON key / host / label into a lowerCamelCase identifier. */
    fun ident(raw: String, fallback: String = "field"): String {
        val parts = raw.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return fallback
        val camel = parts.mapIndexed { i, p ->
            if (i == 0) p.replaceFirstChar { it.lowercaseChar() } else p.replaceFirstChar { it.uppercaseChar() }
        }.joinToString("")
        val safe = if (camel.first().isDigit()) "f$camel" else camel
        return if (safe in KEYWORDS) "${safe}Value" else safe
    }

    /** UpperCamelCase type name. */
    fun typeName(raw: String, fallback: String = "Item"): String =
        ident(raw, fallback).replaceFirstChar { it.uppercaseChar() }.removeSuffix("Value").ifBlank { fallback }

    /**
     * Fills a template written in a raw string: `{{key}}` placeholders are replaced first, then
     * every `§` becomes `$`, so templates can contain Kotlin string templates without escaping.
     */
    fun fill(template: String, vararg values: Pair<String, String>): String {
        var out = template
        for ((k, v) in values) out = out.replace("{{$k}}", v)
        return out.replace('§', '$')
    }

    fun indent(block: String, spaces: Int): String {
        val pad = " ".repeat(spaces)
        return block.lines().joinToString("\n") { if (it.isBlank()) "" else pad + it }
    }
}
