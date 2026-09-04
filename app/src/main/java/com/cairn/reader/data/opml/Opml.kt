package com.cairn.reader.data.opml

import com.cairn.reader.data.db.SourceEntity
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** One subscription parsed from an OPML file. */
data class OpmlFeed(val title: String, val xmlUrl: String, val folder: String?)

/** Import/export of OPML — the portable format every RSS reader speaks, so a user can
 *  bring their Inoreader/Feedly subscriptions in and take Cairn's out. */
object Opml {

    fun parse(xml: String): List<OpmlFeed> {
        val doc = runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull() ?: return emptyList()
        return doc.select("outline").mapNotNull { el ->
            val xmlUrl = el.attr("xmlUrl").ifBlank { el.attr("xmlurl") }
            if (xmlUrl.isBlank()) return@mapNotNull null
            val title = el.attr("title").ifBlank { el.attr("text") }.ifBlank { xmlUrl }
            val parent = el.parent()
            val folder = if (parent != null && parent.tagName().equals("outline", ignoreCase = true)) {
                parent.attr("title").ifBlank { parent.attr("text") }.trim().ifBlank { null }
            } else {
                null
            }
            OpmlFeed(title = title.trim(), xmlUrl = xmlUrl.trim(), folder = folder)
        }.distinctBy { it.xmlUrl }
    }

    fun build(sources: List<SourceEntity>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<opml version=\"2.0\">\n")
        append("  <head><title>Cairn subscriptions</title></head>\n")
        append("  <body>\n")
        val (foldered, loose) = sources.partition { !it.folder.isNullOrBlank() }
        loose.forEach { append(feedLine(it, indent = "    ")) }
        foldered.groupBy { it.folder!!.trim() }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (folder, feeds) ->
            append("    <outline text=\"").append(esc(folder)).append("\" title=\"").append(esc(folder)).append("\">\n")
            feeds.forEach { append(feedLine(it, indent = "      ")) }
            append("    </outline>\n")
        }
        append("  </body>\n")
        append("</opml>\n")
    }

    private fun feedLine(s: SourceEntity, indent: String): String =
        "$indent<outline text=\"${esc(s.title)}\" title=\"${esc(s.title)}\" type=\"rss\" xmlUrl=\"${esc(s.feedUrl)}\"" +
            (s.siteUrl?.let { " htmlUrl=\"${esc(it)}\"" } ?: "") + "/>\n"

    private fun esc(v: String): String = v
        .replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")
}
