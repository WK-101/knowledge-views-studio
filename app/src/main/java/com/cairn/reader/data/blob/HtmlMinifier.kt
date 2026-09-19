package com.cairn.reader.data.blob

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment

/**
 * Shrinks stored article HTML before it is gzipped, without changing how it reads.
 *
 * The native reader ([com.cairn.reader.ui.reader.HtmlLinearizer]) walks only tag structure plus
 * `img@src` / `img@alt` / `a@href` — it never looks at `class`, `id`, `style`, `data-*`, width/height,
 * ARIA, tracking attributes, or HTML comments. Those are pure dead weight in the blob (and inflate the
 * gzip input), so we drop every attribute except the tiny allowlist the renderer actually uses, plus
 * comment nodes and pretty-print whitespace. This typically removes a large fraction of Readability's
 * output on class-heavy sites, for zero rendering difference. Fully offline; parse failures fall back
 * to the original string so a pathological page is never lost.
 */
internal object HtmlMinifier {

    // The only attributes the reader consumes. `src` may be relative — it is kept verbatim so the
    // reader can still resolve it against the article's base URL at render time.
    private val KEEP: Map<String, Set<String>> = mapOf(
        "a" to setOf("href"),
        "img" to setOf("src", "alt"),
    )

    fun minify(html: String): String = runCatching {
        val doc = Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)
        for (el in doc.getAllElements()) {
            val keep = KEEP[el.normalName()].orEmpty()
            // Snapshot keys first: removeAttr mutates the live attribute list.
            for (key in el.attributes().map { it.key }) {
                if (key.lowercase() !in keep) el.removeAttr(key)
            }
        }
        // Drop comment nodes anywhere in the tree.
        for (el in doc.getAllElements()) {
            el.childNodes().filterIsInstance<Comment>().toList().forEach { it.remove() }
        }
        doc.body().html()
    }.getOrDefault(html)
}
