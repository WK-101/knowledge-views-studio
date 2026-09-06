package com.cairn.reader.domain.export

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a saved article — its metadata, cleaned HTML body, tags, and highlights — into a portable
 * Markdown document with YAML frontmatter, the format Obsidian, Logseq, and every plain-text vault
 * understands. Deterministic and fully on-device (jsoup only, no network, no model). This is Cairn's
 * anti-shutdown guarantee made concrete: everything you save can leave as files you own forever.
 */
object MarkdownExporter {

    /** One exportable document: a vault-safe filename and its full Markdown content. */
    data class Doc(val filename: String, val content: String)

    /** Minimal metadata needed to build the frontmatter — a view over an item row. */
    data class Meta(
        val title: String,
        val url: String,
        val author: String? = null,
        val siteName: String? = null,
        val publishedAt: Long? = null,
        val savedAt: Long = 0L,
        val tags: List<String> = emptyList(),
    )

    /** One highlight to append: the quoted passage plus an optional note. */
    data class Highlight(val quote: String, val note: String?, val createdAt: Long)

    private val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val isoStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)

    /** Build the complete Markdown document for one article. */
    fun document(meta: Meta, html: String?, highlights: List<Highlight> = emptyList()): Doc {
        val body = buildString {
            append(frontmatter(meta))
            append('\n')
            append("# ").append(meta.title.trim().ifBlank { "Untitled" }).append("\n\n")

            // A compact source line under the title so the file stands on its own outside Cairn.
            val bits = buildList {
                meta.siteName?.takeIf { it.isNotBlank() }?.let { add(it) }
                meta.author?.takeIf { it.isNotBlank() }?.let { add("by $it") }
                meta.publishedAt?.takeIf { it > 0 }?.let { add(isoDate.format(Date(it))) }
            }
            if (bits.isNotEmpty()) append("*").append(bits.joinToString(" · ")).append("*\n\n")
            if (meta.url.isNotBlank()) append("[Read the original](").append(meta.url).append(")\n\n")
            append("---\n\n")

            val md = html?.let { runCatching { htmlToMarkdown(it) }.getOrNull() }?.trim().orEmpty()
            if (md.isNotBlank()) append(md).append('\n')
            else append("*No saved article text — open the original link above.*\n")

            if (highlights.isNotEmpty()) {
                append("\n## Highlights\n\n")
                highlights.sortedBy { it.createdAt }.forEach { h ->
                    h.quote.trim().split("\n").forEach { line -> append("> ").append(line.trim()).append('\n') }
                    h.note?.takeIf { it.isNotBlank() }?.let { append("\n").append(it.trim()).append('\n') }
                    append('\n')
                }
            }
        }
        return Doc(filename(meta), body.trimEnd() + "\n")
    }

    /** YAML frontmatter block, the header every vault reads for metadata and tags. */
    fun frontmatter(meta: Meta): String = buildString {
        append("---\n")
        append("title: ").append(yaml(meta.title)).append('\n')
        if (!meta.author.isNullOrBlank()) append("author: ").append(yaml(meta.author)).append('\n')
        if (!meta.siteName.isNullOrBlank()) append("source: ").append(yaml(meta.siteName)).append('\n')
        if (meta.url.isNotBlank()) append("url: ").append(yaml(meta.url)).append('\n')
        meta.publishedAt?.takeIf { it > 0 }?.let { append("published: ").append(isoDate.format(Date(it))).append('\n') }
        meta.savedAt.takeIf { it > 0 }?.let { append("saved: ").append(isoStamp.format(Date(it))).append('\n') }
        if (meta.tags.isNotEmpty()) {
            append("tags:\n")
            meta.tags.forEach { append("  - ").append(yaml(tagSlug(it))).append('\n') }
        }
        append("cairn: article\n")
        append("---\n")
    }

    /** A safe, unique-ish vault filename derived from the title. */
    fun filename(meta: Meta): String {
        val base = meta.title.trim()
            .replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), " ") // characters that break files or Obsidian links
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .ifBlank { "Untitled" }
        return "$base.md"
    }

    // -- HTML -> Markdown ------------------------------------------------------

    /** Convert cleaned article HTML into Markdown. A pragmatic subset: headings, emphasis, links,
     *  images, lists, blockquotes, code, and rules — the structure that survives round-trips well. */
    fun htmlToMarkdown(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script, style, noscript, iframe, form, button, svg").remove()
        val root = doc.body()
        val sb = StringBuilder()
        root.childNodes().forEach { renderBlock(it, sb) }
        // Collapse 3+ blank lines the block walker can leave behind.
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun renderBlock(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> {
                val t = node.text().trim()
                if (t.isNotEmpty()) sb.append(t).append("\n\n")
            }
            is Element -> when (node.tagName().lowercase()) {
                "h1" -> heading(node, 1, sb)
                "h2" -> heading(node, 2, sb)
                "h3" -> heading(node, 3, sb)
                "h4" -> heading(node, 4, sb)
                "h5" -> heading(node, 5, sb)
                "h6" -> heading(node, 6, sb)
                "p" -> {
                    val t = inline(node).trim()
                    if (t.isNotEmpty()) sb.append(t).append("\n\n")
                }
                "br" -> sb.append("\n")
                "hr" -> sb.append("---\n\n")
                "ul" -> { list(node, ordered = false, sb); sb.append('\n') }
                "ol" -> { list(node, ordered = true, sb); sb.append('\n') }
                "blockquote" -> {
                    val inner = StringBuilder()
                    node.childNodes().forEach { renderBlock(it, inner) }
                    inner.toString().trim().split("\n").forEach { line ->
                        sb.append("> ").append(line).append('\n')
                    }
                    sb.append('\n')
                }
                "pre" -> {
                    val code = node.wholeText().trimEnd()
                    sb.append("```\n").append(code).append("\n```\n\n")
                }
                "figure", "picture" -> node.childNodes().forEach { renderBlock(it, sb) }
                "img" -> imageBlock(node, sb)
                "table" -> table(node, sb)
                "div", "section", "article", "main", "header", "footer", "aside" ->
                    node.childNodes().forEach { renderBlock(it, sb) }
                else -> {
                    // Unknown inline-ish wrapper: render as a paragraph if it has text.
                    val t = inline(node).trim()
                    if (t.isNotEmpty()) sb.append(t).append("\n\n")
                }
            }
        }
    }

    private fun heading(node: Element, level: Int, sb: StringBuilder) {
        val t = inline(node).trim()
        if (t.isNotEmpty()) sb.append("#".repeat(level)).append(' ').append(t).append("\n\n")
    }

    private fun imageBlock(node: Element, sb: StringBuilder) {
        val src = node.attr("src").ifBlank { node.attr("data-src") }
        if (src.isNotBlank()) {
            val alt = node.attr("alt").trim()
            sb.append("![").append(alt).append("](").append(src).append(")\n\n")
        }
    }

    private fun list(node: Element, ordered: Boolean, sb: StringBuilder, depth: Int = 0) {
        var i = 1
        node.children().filter { it.tagName().equals("li", true) }.forEach { li ->
            val marker = if (ordered) "${i++}. " else "- "
            val indent = "  ".repeat(depth)
            // The li's own inline text (excluding nested lists).
            val text = inline(li, skipLists = true).trim()
            sb.append(indent).append(marker).append(text).append('\n')
            // Nested lists indented one level deeper.
            li.children().filter { it.tagName().equals("ul", true) || it.tagName().equals("ol", true) }
                .forEach { list(it, it.tagName().equals("ol", true), sb, depth + 1) }
        }
    }

    private fun table(node: Element, sb: StringBuilder) {
        val rows = node.select("tr")
        if (rows.isEmpty()) return
        rows.forEachIndexed { idx, tr ->
            val cells = tr.select("th, td").map { inline(it).trim().replace("|", "\\|") }
            if (cells.isEmpty()) return@forEachIndexed
            sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
            if (idx == 0) sb.append("| ").append(cells.joinToString(" | ") { "---" }).append(" |\n")
        }
        sb.append('\n')
    }

    /** Render inline content (emphasis, links, code, images) of an element to a single line. */
    private fun inline(node: Node, skipLists: Boolean = false): String {
        val sb = StringBuilder()
        when (node) {
            is TextNode -> sb.append(node.text())
            is Element -> when (node.tagName().lowercase()) {
                "a" -> {
                    val text = node.childNodes().joinToString("") { inline(it) }.trim()
                    val href = node.attr("href")
                    if (href.isBlank() || text.isBlank()) sb.append(text)
                    else sb.append('[').append(text).append("](").append(href).append(')')
                }
                "strong", "b" -> sb.append("**").append(node.childNodes().joinToString("") { inline(it) }.trim()).append("**")
                "em", "i" -> sb.append('*').append(node.childNodes().joinToString("") { inline(it) }.trim()).append('*')
                "code" -> sb.append('`').append(node.text()).append('`')
                "br" -> sb.append("  \n")
                "img" -> {
                    val src = node.attr("src").ifBlank { node.attr("data-src") }
                    if (src.isNotBlank()) sb.append("![").append(node.attr("alt").trim()).append("](").append(src).append(")")
                }
                "ul", "ol" -> if (!skipLists) node.childNodes().forEach { sb.append(inline(it)) }
                else -> node.childNodes().forEach { sb.append(inline(it, skipLists)) }
            }
        }
        return sb.toString()
    }

    // -- helpers ---------------------------------------------------------------

    /** Quote a scalar for YAML when it could otherwise be misparsed. */
    private fun yaml(value: String): String {
        val v = value.trim().replace("\n", " ")
        return if (v.isEmpty()) "\"\""
        else if (v.any { it in ":#{}[],&*!|>'\"%@`" } || v.first().isWhitespace() || v.last().isWhitespace())
            "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        else v
    }

    /** Obsidian-friendly tag: no spaces (they'd split the tag), keep nesting slashes. */
    private fun tagSlug(tag: String): String =
        tag.trim().replace(Regex("\\s+"), "-").replace(Regex("[^\\p{L}\\p{N}/_-]"), "")
}
