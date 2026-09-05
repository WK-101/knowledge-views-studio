package com.cairn.reader.domain.feed

/** A parsed feed and its entries, normalized across RSS/Atom/RDF. */
data class ParsedFeed(
    val title: String?,
    val siteUrl: String?,
    val items: List<ParsedItem>,
    /** WebSub (PubSubHubbub) hub URL, if the feed declares one via <link rel="hub">. */
    val hubUrl: String? = null,
)

data class ParsedItem(
    val guid: String?,
    val title: String?,
    val link: String?,
    val author: String?,
    val publishedAt: Long?,
    val contentHtml: String?,
    val summary: String?,
    val imageUrl: String?,
    val audioUrl: String? = null,
    val commentsUrl: String? = null,
)

/** Pluggable feed parser. In-house [XmlFeedParser] is the default; Rome/RSSParser could
 *  slot in behind this interface without touching callers. */
interface FeedParser {
    /** Returns null if [xml] isn't a recognizable feed. */
    fun parse(xml: String, feedUrl: String): ParsedFeed?
}
