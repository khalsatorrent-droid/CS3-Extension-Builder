package com.khalsatorrent.cs3builder.utils

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/** Small regex-based highlighter for Kotlin / Gradle KTS / Markdown on a dark background. */
object CodeHighlighter {

    private val KEYWORD = Color.parseColor("#C586C0")
    private val DECL = Color.parseColor("#569CD6")
    private val STRING = Color.parseColor("#CE9178")
    private val COMMENT = Color.parseColor("#6A9955")
    private val NUMBER = Color.parseColor("#B5CEA8")
    private val ANNOTATION = Color.parseColor("#DCDCAA")
    private val TYPE = Color.parseColor("#4EC9B0")
    private val HEADING = Color.parseColor("#569CD6")

    private val keywordRx = Regex("\\b(if|else|when|return|for|while|in|is|as|try|catch|throw|null|true|false|this|super|break|continue)\\b")
    private val declRx = Regex("\\b(package|import|class|data|object|companion|fun|val|var|override|private|suspend|open|abstract|const|internal|lateinit)\\b")
    private val typeRx = Regex("\\b([A-Z][A-Za-z0-9_]*)\\b")
    private val numberRx = Regex("\\b\\d+(\\.\\d+)?\\b")
    private val annotationRx = Regex("@[A-Za-z]+")
    private val stringRx = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"")
    private val commentRx = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")

    fun highlight(code: String, fileName: String): CharSequence {
        if (code.length > 250_000) return code
        val sb = SpannableStringBuilder(code)
        if (fileName.endsWith(".md")) {
            Regex("(?m)^#+ .*$").findAll(code).forEach { color(sb, it.range, HEADING) }
            Regex("(?m)^✓.*$").findAll(code).forEach { color(sb, it.range, COMMENT) }
            Regex("(?m)^✗.*$").findAll(code).forEach { color(sb, it.range, STRING) }
            return sb
        }
        if (fileName.endsWith(".xml")) {
            stringRx.findAll(code).forEach { color(sb, it.range, STRING) }
            return sb
        }
        typeRx.findAll(code).forEach { color(sb, it.range, TYPE) }
        numberRx.findAll(code).forEach { color(sb, it.range, NUMBER) }
        declRx.findAll(code).forEach { color(sb, it.range, DECL) }
        keywordRx.findAll(code).forEach { color(sb, it.range, KEYWORD) }
        annotationRx.findAll(code).forEach { color(sb, it.range, ANNOTATION) }
        // strings and comments last so they win over everything inside them
        stringRx.findAll(code).forEach { color(sb, it.range, STRING) }
        commentRx.findAll(code).forEach { m ->
            // ignore "//" that sits inside a string literal (URLs)
            val lineStart = code.lastIndexOf('\n', m.range.first) + 1
            val before = code.substring(lineStart, m.range.first)
            if (before.count { it == '"' } % 2 == 0) color(sb, m.range, COMMENT)
        }
        return sb
    }

    private fun color(sb: SpannableStringBuilder, range: IntRange, c: Int) {
        sb.setSpan(ForegroundColorSpan(c), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
