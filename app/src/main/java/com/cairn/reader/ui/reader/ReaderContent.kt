package com.cairn.reader.ui.reader

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
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** A block of rendered reader content. Rendered natively in Compose — no WebView. */
sealed interface ReaderBlock {
    data class Heading(val level: Int, val text: AnnotatedString) : ReaderBlock
    data class Paragraph(val text: AnnotatedString) : ReaderBlock
    data class Image(val url: String, val caption: String?) : ReaderBlock
    data class Quote(val text: AnnotatedString) : ReaderBlock
    data class Code(val text: String) : ReaderBlock
    data class BulletList(val items: List<AnnotatedString>, val ordered: Boolean) : ReaderBlock
    /** A table, row-major. [headerRow] is true when the first row is a header (`<th>`), so it can
     *  be styled distinctly. Rows are ragged-tolerant — the renderer pads short rows. */
    data class Table(val rows: List<List<AnnotatedString>>, val headerRow: Boolean) : ReaderBlock
    data object Rule : ReaderBlock
}

/** The plain text a highlight can anchor to; "" for blocks that don't host selectable text
 *  (only Heading/Paragraph/Quote wire text selection). Used by [HighlightAnchoring]. */
fun ReaderBlock.highlightText(): String = when (this) {
    is ReaderBlock.Heading -> text.text
    is ReaderBlock.Paragraph -> text.text
    is ReaderBlock.Quote -> text.text
    else -> ""
}

/**
 * Converts extracted article HTML into a flat list of [ReaderBlock]s for native
 * rendering. Handles headings, paragraphs with inline bold/italic/code/links, images
 * (incl. figures), blockquotes, lists, code, and rules — the shape of most articles.
 */
object HtmlLinearizer {

    private val whitespace = Regex("\\s+")

    fun linearize(html: String, baseUrl: String, linkColor: Color): List<ReaderBlock> {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ReaderBlock>()
        walk(doc.body(), out, linkColor)
        return out
    }

    private fun walk(parent: Element, out: MutableList<ReaderBlock>, link: Color) {
        for (el in parent.children()) {
            when (el.tagName().lowercase()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = el.tagName().substring(1).toIntOrNull() ?: 3
                    inlineOrNull(el, link)?.let { out += ReaderBlock.Heading(level, it) }
                }
                "p" -> emitParagraphOrImages(el, out, link)
                "figure" -> emitFigure(el, out)
                "img" -> emitImage(el, out, caption = null)
                "ul", "ol" -> {
                    val items = el.select("> li").mapNotNull { inlineOrNull(it, link) }
                    if (items.isNotEmpty()) out += ReaderBlock.BulletList(items, ordered = el.tagName() == "ol")
                }
                "blockquote" -> inlineOrNull(el, link)?.let { out += ReaderBlock.Quote(it) }
                "pre" -> {
                    val code = el.wholeText().trimEnd()
                    if (code.isNotBlank()) out += ReaderBlock.Code(code)
                }
                "table" -> emitTable(el, out, link)
                "hr" -> out += ReaderBlock.Rule
                "figcaption", "script", "style", "noscript" -> Unit
                "div", "section", "article", "main", "header", "footer", "aside" -> walk(el, out, link)
                else -> {
                    // Unknown container: recurse if it has element children, else treat as text.
                    if (el.children().isNotEmpty()) walk(el, out, link)
                    else inlineOrNull(el, link)?.let { out += ReaderBlock.Paragraph(it) }
                }
            }
        }
    }

    private fun emitParagraphOrImages(el: Element, out: MutableList<ReaderBlock>, link: Color) {
        val img = el.selectFirst("img")
        if (img != null && el.text().isBlank()) {
            emitImage(img, out, caption = null)
            return
        }
        inlineOrNull(el, link)?.let { out += ReaderBlock.Paragraph(it) }
    }

    private fun emitTable(table: Element, out: MutableList<ReaderBlock>, link: Color) {
        // Row-major extraction; each <tr> maps to a list of cell strings (th or td). Empty cells are
        // kept as blank AnnotatedStrings so columns stay aligned across rows.
        val trs = table.select("tr")
        if (trs.isEmpty()) return
        val rows = trs.map { tr ->
            tr.select("> th, > td").map { cell -> inlineOrNull(cell, link) ?: AnnotatedString("") }
        }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return
        // A degenerate 1x1 table is just a paragraph — don't wrap a single value in table chrome.
        if (rows.size == 1 && rows[0].size == 1) {
            rows[0][0].takeIf { it.text.isNotBlank() }?.let { out += ReaderBlock.Paragraph(it) }
            return
        }
        val headerRow = trs.first().select("> th").isNotEmpty()
        out += ReaderBlock.Table(rows, headerRow)
    }

    private fun emitFigure(el: Element, out: MutableList<ReaderBlock>) {
        val img = el.selectFirst("img") ?: return
        val caption = el.selectFirst("figcaption")?.text()?.trim()?.ifBlank { null }
        emitImage(img, out, caption)
    }

    private fun emitImage(img: Element, out: MutableList<ReaderBlock>, caption: String?) {
        val url = img.absUrl("src").ifBlank { img.attr("src") }
        if (url.isNotBlank() && !url.startsWith("data:")) {
            out += ReaderBlock.Image(url, caption ?: img.attr("alt").trim().ifBlank { null })
        }
    }

    private fun inlineOrNull(el: Element, link: Color): AnnotatedString? {
        val built = buildAnnotatedString {
            el.childNodes().forEach { appendInline(it, link) }
        }
        return built.takeIf { it.text.isNotBlank() }
    }

    private fun AnnotatedString.Builder.appendInline(node: Node, link: Color) {
        when (node) {
            is TextNode -> {
                val text = node.wholeText.replace(whitespace, " ")
                if (text.isNotEmpty()) append(text)
            }
            is Element -> when (node.tagName().lowercase()) {
                "b", "strong" -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    node.childNodes().forEach { appendInline(it, link) }
                }
                "i", "em", "cite" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    node.childNodes().forEach { appendInline(it, link) }
                }
                "code" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    node.childNodes().forEach { appendInline(it, link) }
                }
                "a" -> {
                    val href = node.absUrl("href").ifBlank { node.attr("href") }
                    if (href.isNotBlank()) {
                        withLink(
                            LinkAnnotation.Url(
                                url = href,
                                styles = TextLinkStyles(
                                    SpanStyle(color = link, textDecoration = TextDecoration.Underline),
                                ),
                            ),
                        ) { node.childNodes().forEach { appendInline(it, link) } }
                    } else {
                        node.childNodes().forEach { appendInline(it, link) }
                    }
                }
                "br" -> append("\n")
                "script", "style", "noscript" -> Unit
                else -> node.childNodes().forEach { appendInline(it, link) }
            }
            else -> Unit
        }
    }
}
