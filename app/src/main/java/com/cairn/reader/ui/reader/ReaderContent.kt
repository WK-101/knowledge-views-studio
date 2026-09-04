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
    data object Rule : ReaderBlock
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
