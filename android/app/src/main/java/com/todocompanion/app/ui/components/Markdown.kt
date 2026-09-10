package com.todocompanion.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as CmText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * Wave G — a CommonMark (GFM) note renderer built on the pure-Java `commonmark-java` parser. Fully
 * offline: no network, no HTML execution. Renders headings, paragraphs, nested bullet/ordered lists,
 * task checkboxes (tappable, round-tripped to the Markdown source by line index), blockquotes,
 * GitHub-style callouts (`> [!NOTE]`), tables, thematic breaks, and fenced/indented code with a
 * dependency-free syntax highlighter, plus inline bold/italic/strikethrough/code, real links and
 * Kairo's [[wiki-links]]. The parse is cached per source string; a malformed input falls back to plain
 * text rather than crashing the editor.
 */

/** The shared, extension-configured parser (tables · strikethrough · task-list-items · source spans). */
object MarkdownDoc {
    val parser: Parser = Parser.builder()
        .extensions(listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
        ))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .build()
    fun parse(src: String): Node = parser.parse(src)
}

private data class MdPalette(
    val onSurface: Color, val muted: Color, val accent: Color, val outline: Color,
    val codeBg: Color, val codeText: Color, val kw: Color, val str: Color, val cmt: Color, val num: Color,
    val calloutBg: Color, val hair: Color,
)

@Composable
private fun palette(): MdPalette {
    val cs = MaterialTheme.colorScheme
    return MdPalette(
        onSurface = cs.onSurface, muted = cs.onSurfaceVariant, accent = cs.primary, outline = cs.outline,
        codeBg = cs.surfaceVariant, codeText = cs.onSurfaceVariant,
        kw = cs.primary, str = cs.tertiary, cmt = cs.outline, num = cs.secondary,
        calloutBg = cs.surfaceVariant.copy(alpha = .45f), hair = cs.outlineVariant,
    )
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    onToggleCheckbox: ((sourceLineIndex: Int) -> Unit)? = null,
) {
    val pal = palette()
    val doc = remember(text) { runCatching { MarkdownDoc.parse(text) }.getOrNull() }
    if (doc == null) {
        Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = pal.onSurface)
        return
    }
    Column(modifier) { MdBlocks(doc, 0, pal, onToggleCheckbox) }
}

@Composable
private fun MdBlocks(parent: Node, depth: Int, pal: MdPalette, onToggle: ((Int) -> Unit)?) {
    var child = parent.firstChild
    while (child != null) { MdBlock(child, depth, pal, onToggle); child = child.next }
}

@Composable
private fun MdBlock(node: Node, depth: Int, pal: MdPalette, onToggle: ((Int) -> Unit)?) {
    when (node) {
        is Heading -> {
            val style = when (node.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(buildInline(node, pal), style = style.copy(fontWeight = FontWeight.Bold),
                color = pal.onSurface, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
        }
        is Paragraph -> Text(buildInline(node, pal), style = MaterialTheme.typography.bodyMedium, color = pal.onSurface,
            modifier = Modifier.padding(vertical = 1.dp))
        is BulletList -> MdList(node, ordered = false, depth = depth, pal = pal, onToggle = onToggle)
        is OrderedList -> MdList(node, ordered = true, depth = depth, pal = pal, onToggle = onToggle)
        is BlockQuote -> MdBlockQuote(node, depth, pal, onToggle)
        is FencedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'), node.info ?: "", pal)
        is IndentedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'), "", pal)
        is ThematicBreak -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(pal.hair))
        is TableBlock -> MdTable(node, pal)
        is HtmlBlock -> Text(node.literal.trim(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = pal.muted)
        else -> { // unknown container — descend so nothing is silently dropped
            if (node.firstChild != null) MdBlocks(node, depth, pal, onToggle)
        }
    }
}

@Composable
private fun MdList(list: Node, ordered: Boolean, depth: Int, pal: MdPalette, onToggle: ((Int) -> Unit)?) {
    var item = list.firstChild
    var number = 1
    while (item != null) {
        if (item is ListItem) {
            MdListItem(item, ordered, number, depth, pal, onToggle)
            number++
        }
        item = item.next
    }
}

@Composable
private fun MdListItem(item: ListItem, ordered: Boolean, number: Int, depth: Int, pal: MdPalette, onToggle: ((Int) -> Unit)?) {
    // A GFM task item = first block is a Paragraph whose first inline child is a TaskListItemMarker.
    val firstPara = item.firstChild as? Paragraph
    val marker = firstPara?.firstChild as? TaskListItemMarker
    val srcLine = item.sourceSpans.firstOrNull()?.lineIndex
    Row(Modifier.padding(start = (4 + depth * 14).dp, top = 1.dp, bottom = 1.dp)) {
        when {
            marker != null -> {
                val glyph = if (marker.isChecked) "☑ " else "☐ "
                if (onToggle != null && srcLine != null) {
                    Text(glyph, color = pal.accent, modifier = Modifier.clickable { onToggle(srcLine) })
                } else Text(glyph, color = pal.accent)
            }
            ordered -> Text("$number. ", style = MaterialTheme.typography.bodyMedium, color = pal.muted)
            else -> Text("•  ", style = MaterialTheme.typography.bodyMedium, color = pal.accent)
        }
        Column {
            var block = item.firstChild
            while (block != null) {
                when (block) {
                    is Paragraph -> {
                        val checked = marker?.isChecked == true && block === firstPara
                        Text(buildInline(block, pal, strike = checked),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (checked) pal.outline else pal.onSurface)
                    }
                    is BulletList -> MdList(block, ordered = false, depth = depth + 1, pal = pal, onToggle = onToggle)
                    is OrderedList -> MdList(block, ordered = true, depth = depth + 1, pal = pal, onToggle = onToggle)
                    else -> MdBlock(block, depth + 1, pal, onToggle)
                }
                block = block.next
            }
        }
    }
}

private val CALLOUTS = mapOf(
    "NOTE" to ("ℹ" to "Note"), "TIP" to ("💡" to "Tip"), "IMPORTANT" to ("❗" to "Important"),
    "WARNING" to ("⚠" to "Warning"), "CAUTION" to ("🛑" to "Caution"),
)
private val CALLOUT_RE = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\\s*", RegexOption.IGNORE_CASE)

@Composable
private fun MdBlockQuote(node: BlockQuote, depth: Int, pal: MdPalette, onToggle: ((Int) -> Unit)?) {
    // GitHub callout: a blockquote whose first paragraph's first text is "[!TYPE]".
    val firstPara = node.firstChild as? Paragraph
    val firstText = firstPara?.firstChild as? CmText
    val m = firstText?.let { CALLOUT_RE.find(it.literal) }
    if (m != null) {
        val type = m.groupValues[1].uppercase()
        val (emoji, label) = CALLOUTS[type] ?: ("ℹ" to "Note")
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(pal.calloutBg)) {
            Row(Modifier.padding(10.dp)) {
                Box(Modifier.width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(pal.accent))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("$emoji  $label", style = MaterialTheme.typography.labelLarge, color = pal.accent,
                        modifier = Modifier.padding(bottom = 2.dp))
                    // Hide the "[!TYPE]" marker for display only — mutate the cached node then restore it,
                    // so a recomposition still sees the marker and re-detects the callout (no permanent edit).
                    val saved = firstText.literal
                    firstText.literal = firstText.literal.substring(m.value.length.coerceAtMost(firstText.literal.length))
                    MdBlocks(node, depth, pal, onToggle)
                    firstText.literal = saved
                }
            }
        }
        return
    }
    Row(Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(pal.accent.copy(alpha = .5f)))
        Spacer(Modifier.width(8.dp))
        Column { MdBlocks(node, depth, pal, onToggle) }
    }
}

@Composable
private fun MdTable(table: TableBlock, pal: MdPalette) {
    // Collect rows as List<List<AnnotatedString>> with a header flag.
    data class Cell(val text: AnnotatedString)
    val header = mutableListOf<AnnotatedString>()
    val body = mutableListOf<List<AnnotatedString>>()
    var section = table.firstChild
    while (section != null) {
        when (section) {
            is TableHead -> {
                (section.firstChild as? TableRow)?.let { row ->
                    var cell = row.firstChild
                    while (cell != null) { if (cell is TableCell) header.add(buildInline(cell, pal)); cell = cell.next }
                }
            }
            is TableBody -> {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        val cells = mutableListOf<AnnotatedString>()
                        var cell = row.firstChild
                        while (cell != null) { if (cell is TableCell) cells.add(buildInline(cell, pal)); cell = cell.next }
                        body.add(cells)
                    }
                    row = row.next
                }
            }
        }
        section = section.next
    }
    val cols = maxOf(header.size, body.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).horizontalScroll(rememberScrollState())) {
        Column(Modifier.clip(RoundedCornerShape(8.dp)).background(pal.calloutBg)) {
            if (header.isNotEmpty()) {
                Row {
                    for (c in 0 until cols) MdTableCell(header.getOrNull(c) ?: AnnotatedString(""), pal, headerRow = true)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(pal.hair))
            }
            body.forEachIndexed { idx, row ->
                Row { for (c in 0 until cols) MdTableCell(row.getOrNull(c) ?: AnnotatedString(""), pal, headerRow = false) }
                if (idx < body.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.hair.copy(alpha = .5f)))
            }
        }
    }
}

@Composable
private fun MdTableCell(text: AnnotatedString, pal: MdPalette, headerRow: Boolean) {
    Text(text, modifier = Modifier.width(140.dp).padding(horizontal = 10.dp, vertical = 7.dp),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (headerRow) FontWeight.SemiBold else FontWeight.Normal,
        color = if (headerRow) pal.onSurface else pal.muted)
}

@Composable
private fun CodeBlock(code: String, lang: String, pal: MdPalette) {
    val highlighted = remember(code, lang) {
        runCatching {
            CodeHighlighter.highlight(code, lang, CodeHighlighter.Palette(pal.codeText, pal.kw, pal.str, pal.cmt, pal.num))
        }.getOrDefault(AnnotatedString(code))
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(pal.codeBg)) {
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)) {
            Text(highlighted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = pal.codeText)
        }
    }
}

// ---------------- inline ----------------

private fun buildInline(node: Node, pal: MdPalette, strike: Boolean = false): AnnotatedString = buildAnnotatedString {
    if (strike) pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
    appendInline(node, pal)
    if (strike) pop()
}

private fun AnnotatedString.Builder.appendInline(node: Node, pal: MdPalette) {
    var child = node.firstChild
    while (child != null) {
        when (val c = child) {
            is CmText -> appendText(c.literal, pal)
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(c, pal) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(c, pal) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(c, pal) }
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = pal.codeBg)) { append(c.literal) }
            is Link -> {
                val dest = c.destination ?: ""
                if (isRealUrl(dest)) {
                    withLink(LinkAnnotation.Url(dest, TextLinkStyles(SpanStyle(color = pal.accent, textDecoration = TextDecoration.Underline)))) {
                        appendInline(c, pal)
                    }
                } else {
                    withStyle(SpanStyle(color = pal.accent, textDecoration = TextDecoration.Underline)) { appendInline(c, pal) }
                }
            }
            is Image -> withStyle(SpanStyle(color = pal.muted, fontStyle = FontStyle.Italic)) {
                append("🖼 "); appendInline(c, pal)
            }
            is SoftLineBreak -> append(" ")
            is HardLineBreak -> append("\n")
            is HtmlInline -> append(c.literal)
            is TaskListItemMarker -> {} // rendered as a checkbox by the list-item composable
            else -> appendInline(c, pal) // unknown wrapper — descend
        }
        child = child.next
    }
}

// Kairo [[wiki-links]] aren't CommonMark; style them inline (navigation is via the editor's Links chips).
private val WIKI_RE = Regex("\\[\\[([^\\]]+)]]")
private fun AnnotatedString.Builder.appendText(literal: String, pal: MdPalette) {
    if (!literal.contains("[[")) { append(literal); return }
    var last = 0
    for (m in WIKI_RE.findAll(literal)) {
        if (m.range.first > last) append(literal.substring(last, m.range.first))
        withStyle(SpanStyle(color = pal.accent)) { append(m.groupValues[1]) }
        last = m.range.last + 1
    }
    if (last < literal.length) append(literal.substring(last))
}

private fun isRealUrl(dest: String): Boolean =
    dest.startsWith("http://") || dest.startsWith("https://") ||
        dest.startsWith("mailto:") || dest.startsWith("tel:")
