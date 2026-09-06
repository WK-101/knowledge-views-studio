package com.cairn.reader.domain.export

import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wraps a saved article into a single, self-contained, readable HTML file — a preservation snapshot
 * that opens in any browser, forever, with no app required. Deterministic, on-device (jsoup only).
 * Images keep their original URLs so a page saved with a permanent offline copy (cached file:// URIs)
 * stays fully self-contained, while a freshly saved one still reads with its remote images.
 */
object HtmlSnapshotExporter {

    private val date = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())

    data class Meta(
        val title: String,
        val url: String,
        val author: String? = null,
        val siteName: String? = null,
        val publishedAt: Long? = null,
        val savedAt: Long = 0L,
    )

    fun snapshot(meta: Meta, html: String?): String {
        val body = cleanBody(html)
        val bits = buildList {
            meta.siteName?.takeIf { it.isNotBlank() }?.let { add(esc(it)) }
            meta.author?.takeIf { it.isNotBlank() }?.let { add("by " + esc(it)) }
            meta.publishedAt?.takeIf { it > 0 }?.let { add(date.format(Date(it))) }
        }
        val mebits = if (bits.isNotEmpty()) """<p class="meta">${bits.joinToString(" · ")}</p>""" else ""
        val savedLine = if (meta.savedAt > 0) "Saved from Cairn on ${date.format(Date(meta.savedAt))}" else "Saved from Cairn"
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>${esc(meta.title.ifBlank { "Untitled" })}</title>
<style>
  :root { color-scheme: light dark; }
  body { max-width: 42rem; margin: 2rem auto; padding: 0 1.25rem;
         font-family: Georgia, 'Times New Roman', serif; line-height: 1.65; font-size: 1.1rem; }
  h1 { line-height: 1.2; font-size: 1.9rem; }
  .meta, .source { color: #6b7280; font-size: 0.9rem; }
  .source { margin-top: 2.5rem; padding-top: 1rem; border-top: 1px solid #e5e7eb; }
  a { color: #2563eb; }
  img { max-width: 100%; height: auto; }
  blockquote { border-left: 3px solid #d1d5db; margin: 1em 0; padding-left: 1em; color: #374151; }
  pre { white-space: pre-wrap; word-wrap: break-word; background: rgba(127,127,127,0.12); padding: 0.6em; border-radius: 6px; }
</style>
</head>
<body>
<article>
<h1>${esc(meta.title.ifBlank { "Untitled" })}</h1>
$mebits
$body
</article>
<p class="source">$savedLine · <a href="${esc(meta.url)}">${esc(meta.url)}</a></p>
</body>
</html>"""
    }

    /** Strip scripts and interactive chrome; keep the article's structure and images intact. */
    private fun cleanBody(html: String?): String {
        if (html.isNullOrBlank()) return "<p><em>No saved article text — see the source link below.</em></p>"
        val doc = Jsoup.parse(html)
        doc.select("script, style, noscript, iframe, form, button, svg").remove()
        return doc.body().html().trim().ifBlank { "<p><em>No saved article text.</em></p>" }
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
