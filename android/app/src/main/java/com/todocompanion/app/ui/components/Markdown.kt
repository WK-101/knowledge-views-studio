package com.todocompanion.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A tiny, dependency-free Markdown renderer for task notes. Fully offline — no
 * network, no HTML. Supports headings, bullet/numbered lists, task checkboxes,
 * blockquotes, horizontal rules, fenced/inline code, and inline bold/italic/
 * strikethrough/code/links. Links are shown styled but inert (offline app).
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val lines = text.replace("\r\n", "\n").split("\n")
    Column(modifier) {
        var i = 0
        var inFence = false
        val fenceBuf = StringBuilder()
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val trimmed = line.trimStart()

            if (trimmed.startsWith("```")) {
                if (inFence) {
                    CodeBlock(fenceBuf.toString().trimEnd('\n'))
                    fenceBuf.clear(); inFence = false
                } else inFence = true
                i++; continue
            }
            if (inFence) { fenceBuf.append(raw).append('\n'); i++; continue }

            when {
                line.isBlank() -> Spacer(Modifier.height(6.dp))

                trimmed == "---" || trimmed == "***" || trimmed == "___" ->
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant))

                trimmed.startsWith("### ") -> Heading(trimmed.removePrefix("### "), 3)
                trimmed.startsWith("## ") -> Heading(trimmed.removePrefix("## "), 2)
                trimmed.startsWith("# ") -> Heading(trimmed.removePrefix("# "), 1)

                trimmed.startsWith("> ") -> Blockquote(trimmed.removePrefix("> "))

                isTaskItem(trimmed) -> {
                    val checked = trimmed.startsWith("- [x]", true) || trimmed.startsWith("* [x]", true)
                    val body = trimmed.replaceFirst(Regex("^[-*] \\[[ xX]] ?"), "")
                    Row(Modifier.padding(start = 4.dp, top = 1.dp, bottom = 1.dp)) {
                        Text(if (checked) "☑ " else "☐ ", color = MaterialTheme.colorScheme.primary)
                        Text(inline(body, strike = checked, linkColor = MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyMedium,
                            color = if (checked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                    }
                }

                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Bullet(inline(trimmed.substring(2), linkColor = MaterialTheme.colorScheme.primary))

                Regex("^\\d+\\. ").containsMatchIn(trimmed) -> {
                    val marker = trimmed.takeWhile { it != ' ' }
                    Row(Modifier.padding(start = 4.dp, top = 1.dp, bottom = 1.dp)) {
                        Text("$marker ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(inline(trimmed.substringAfter(' '), linkColor = MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                else -> Text(inline(line, linkColor = MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            i++
        }
        if (inFence && fenceBuf.isNotEmpty()) CodeBlock(fenceBuf.toString().trimEnd('\n'))
    }
}

private fun isTaskItem(s: String) =
    Regex("^[-*] \\[[ xX]]").containsMatchIn(s)

@Composable
private fun Heading(text: String, level: Int) {
    val style = when (level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    Text(inline(text, linkColor = MaterialTheme.colorScheme.primary), style = style.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}

@Composable
private fun Bullet(text: AnnotatedString) {
    Row(Modifier.padding(start = 4.dp, top = 1.dp, bottom = 1.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Blockquote(text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .5f)))
        Spacer(Modifier.width(8.dp))
        Text(inline(text, linkColor = MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CodeBlock(code: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
        Text(code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Parse inline spans: bold, italic (asterisk or underscore), strikethrough, code, and links.
private fun inline(src: String, strike: Boolean = false, linkColor: androidx.compose.ui.graphics.Color = LinkFallback): AnnotatedString = buildAnnotatedString {
    if (strike) pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
    var i = 0
    val n = src.length
    while (i < n) {
        val c = src[i]
        val two = if (i + 1 < n) src.substring(i, i + 2) else ""
        when {
            two == "**" || two == "__" -> {
                val end = src.indexOf(two, i + 2)
                if (end > i) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(src.substring(i + 2, end)) }; i = end + 2 }
                else { append(c); i++ }
            }
            two == "~~" -> {
                val end = src.indexOf("~~", i + 2)
                if (end > i) { withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(src.substring(i + 2, end)) }; i = end + 2 }
                else { append(c); i++ }
            }
            c == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(src.substring(i + 1, end)) }; i = end + 1 }
                else { append(c); i++ }
            }
            (c == '*' || c == '_') -> {
                val end = src.indexOf(c, i + 1)
                if (end > i) { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(src.substring(i + 1, end)) }; i = end + 1 }
                else { append(c); i++ }
            }
            c == '[' -> {
                val close = src.indexOf(']', i + 1)
                if (close > i && close + 1 < n && src[close + 1] == '(') {
                    val paren = src.indexOf(')', close + 2)
                    if (paren > close) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(src.substring(i + 1, close)) }
                        i = paren + 1
                    } else { append(c); i++ }
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
    if (strike) pop()
}

private val codeBg = androidx.compose.ui.graphics.Color(0x22808080)
// Fallback link colour for non-composable callers; composable callers pass the live accent (primary).
private val LinkFallback = androidx.compose.ui.graphics.Color(0xFF4C6FFF)
