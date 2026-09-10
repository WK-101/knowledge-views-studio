package com.todocompanion.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Wave G — a tiny, dependency-free syntax highlighter for fenced code blocks. A single left-to-right
 * tokenizer (never overlapping regexes) recognises line/block comments, strings, numbers and a
 * per-language keyword set, so a keyword inside a string is never mis-coloured. Fully offline, pure
 * Kotlin; colours are supplied by the caller from the live Material theme so both themes read well.
 */
object CodeHighlighter {

    data class Palette(
        val text: Color,
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
    )

    private val KOTLIN = setOf("val","var","fun","class","object","interface","data","sealed","enum","when","if","else","for","while","do","return","null","true","false","is","as","in","import","package","private","public","protected","internal","override","open","abstract","companion","init","by","lazy","suspend","const","this","super","try","catch","finally","throw","typealias","operator","vararg","inline","reified","out","where")
    private val JAVA = setOf("public","private","protected","class","interface","enum","extends","implements","void","int","long","double","float","boolean","char","byte","short","new","return","if","else","for","while","do","switch","case","default","break","continue","null","true","false","this","super","static","final","abstract","import","package","try","catch","finally","throw","throws","synchronized","volatile","transient","instanceof")
    private val JS = setOf("var","let","const","function","return","if","else","for","while","do","switch","case","default","break","continue","null","undefined","true","false","this","new","class","extends","super","import","export","from","async","await","try","catch","finally","throw","typeof","instanceof","of","in","yield","static","get","set","type","interface","enum","namespace","as","readonly","public","private","protected")
    private val PY = setOf("def","class","return","if","elif","else","for","while","break","continue","pass","import","from","as","None","True","False","and","or","not","in","is","lambda","with","yield","try","except","finally","raise","global","nonlocal","assert","del","async","await","self")
    private val BASH = setOf("if","then","else","elif","fi","for","while","do","done","case","esac","function","return","in","select","until","echo","export","local","read","set","unset","exit","cd","source","alias","test")
    private val SQL = setOf("select","from","where","insert","into","values","update","set","delete","create","table","drop","alter","add","column","index","join","left","right","inner","outer","on","group","by","order","having","limit","offset","and","or","not","null","as","distinct","union","primary","key","foreign","references","default","null","integer","text","real","blob")

    private fun keywordsFor(lang: String): Set<String> = when (lang.lowercase().trim()) {
        "kotlin","kt","kts" -> KOTLIN
        "java" -> JAVA
        "js","javascript","ts","typescript","jsx","tsx","json" -> JS
        "py","python" -> PY
        "sh","bash","shell","zsh" -> BASH
        "sql" -> SQL
        else -> KOTLIN + JAVA + JS   // a reasonable generic superset for unknown langs
    }

    private fun lineCommentPrefix(lang: String): String? = when (lang.lowercase().trim()) {
        "py","python","sh","bash","shell","zsh","yaml","yml","ruby","rb","r","toml" -> "#"
        "sql" -> "--"
        else -> "//"
    }

    fun highlight(code: String, lang: String, pal: Palette): AnnotatedString {
        val kws = keywordsFor(lang)
        val lineComment = lineCommentPrefix(lang)
        val allowBlockComment = lineComment == "//"   // C-family
        return buildAnnotatedString {
            var i = 0
            val n = code.length
            fun span(color: Color, s: Int, e: Int) { withStyle(SpanStyle(color = color)) { append(code.substring(s, e)) } }
            while (i < n) {
                val c = code[i]
                // block comment
                if (allowBlockComment && c == '/' && i + 1 < n && code[i + 1] == '*') {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    span(pal.comment, i, end); i = end; continue
                }
                // line comment
                if (lineComment != null && code.startsWith(lineComment, i)) {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    span(pal.comment, i, end); i = end; continue
                }
                // string
                if (c == '"' || c == '\'' || c == '`') {
                    var j = i + 1
                    while (j < n && code[j] != c) { if (code[j] == '\\') j++; j++ }
                    val end = (j + 1).coerceAtMost(n)
                    span(pal.string, i, end); i = end; continue
                }
                // number
                if (c.isDigit()) {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    span(pal.number, i, j); i = j; continue
                }
                // identifier / keyword
                if (c.isLetter() || c == '_' || c == '@' || c == '$') {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    val word = code.substring(i, j)
                    if (word in kws) span(pal.keyword, i, j) else withStyle(SpanStyle(color = pal.text)) { append(word) }
                    i = j; continue
                }
                // anything else
                withStyle(SpanStyle(color = pal.text)) { append(c.toString()) }
                i++
            }
        }
    }
}
