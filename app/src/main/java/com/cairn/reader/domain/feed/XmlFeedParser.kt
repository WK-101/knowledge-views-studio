package com.cairn.reader.domain.feed

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

/**
 * A dependency-light RSS 2.0 / Atom / RDF (RSS 1.0) parser built on the platform
 * [XmlPullParser]. Namespace-unaware: tag names are matched by their local part, so
 * `content:encoded`, `dc:creator`, and `media:*` are handled without namespace setup.
 */
class XmlFeedParser @Inject constructor() : FeedParser {

    override fun parse(xml: String, feedUrl: String): ParsedFeed? {
        val trimmed = xml.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (!trimmed.startsWith("<")) return null
        return runCatching { parseInternal(trimmed) }.getOrNull()
            ?.takeIf { it.items.isNotEmpty() || it.title != null }
    }

    private fun parseInternal(xml: String): ParsedFeed {
        val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }
        var feedTitle: String? = null
        var siteUrl: String? = null
        val items = mutableListOf<ParsedItem>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (local(parser.name)) {
                    "item", "entry" -> items += readItem(parser, local(parser.name))
                    "title" -> if (feedTitle == null) feedTitle = safeText(parser)
                    "link" -> if (siteUrl == null) {
                        val href = attr(parser, "href")
                        siteUrl = if (!href.isNullOrBlank()) href else safeText(parser)
                    }
                }
            }
            event = parser.next()
        }
        return ParsedFeed(feedTitle?.trim(), siteUrl?.trim(), items)
    }

    private fun readItem(parser: XmlPullParser, endTag: String): ParsedItem {
        var guid: String? = null
        var title: String? = null
        var link: String? = null
        var author: String? = null
        var publishedAt: Long? = null
        var contentHtml: String? = null
        var summary: String? = null
        var imageUrl: String? = null
        var audioUrl: String? = null
        var commentsUrl: String? = null

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && local(parser.name) == endTag) break
            if (event != XmlPullParser.START_TAG) continue

            when (local(parser.name)) {
                "title" -> title = safeText(parser)
                "link" -> {
                    val rel = attr(parser, "rel")
                    val href = attr(parser, "href")
                    if (!href.isNullOrBlank()) {
                        // Atom marks a discussion link with rel="replies"; keep it as the comments URL.
                        if (rel == "replies") commentsUrl = commentsUrl ?: href
                        else if (link == null || rel == null || rel == "alternate") link = href
                    } else {
                        val t = safeText(parser)
                        if (!t.isNullOrBlank()) link = t
                    }
                }
                // Standard RSS discussion link (Hacker News, Reddit, Lobsters, WordPress).
                "comments" -> commentsUrl = commentsUrl ?: safeText(parser)
                "guid", "id" -> guid = guid ?: safeText(parser)
                "pubdate", "published", "updated", "date" ->
                    publishedAt = publishedAt ?: parseDate(safeText(parser))
                "encoded" -> contentHtml = safeText(parser) ?: contentHtml
                "content" -> {
                    val url = attr(parser, "url")
                    if (url != null) {
                        if (imageUrl == null && isImage(attr(parser, "type"), attr(parser, "medium"), url)) imageUrl = url
                    } else {
                        contentHtml = safeText(parser) ?: contentHtml
                    }
                }
                "description", "summary" -> summary = summary ?: safeText(parser)
                "creator" -> author = author ?: safeText(parser)
                "author" -> author = author ?: readAuthor(parser)
                "enclosure" -> {
                    val encType = attr(parser, "type")
                    val encUrl = attr(parser, "url")
                    if (imageUrl == null && encType?.startsWith("image") == true) imageUrl = encUrl
                    if (audioUrl == null && encUrl != null && (encType?.startsWith("audio") == true || isAudioUrl(encUrl))) {
                        audioUrl = encUrl
                    }
                }
                "thumbnail" -> if (imageUrl == null) imageUrl = attr(parser, "url")
            }
        }
        return ParsedItem(
            guid = guid?.trim(),
            title = title?.trim(),
            link = link?.trim(),
            author = author?.trim(),
            publishedAt = publishedAt,
            contentHtml = contentHtml,
            summary = summary?.trim(),
            imageUrl = imageUrl?.trim(),
            audioUrl = audioUrl?.trim(),
            commentsUrl = commentsUrl?.trim(),
        )
    }

    private fun isAudioUrl(url: String): Boolean {
        val u = url.substringBefore('?').lowercase()
        return u.endsWith(".mp3") || u.endsWith(".m4a") || u.endsWith(".aac") ||
            u.endsWith(".ogg") || u.endsWith(".oga") || u.endsWith(".wav") || u.endsWith(".opus")
    }

    /** Handles both RSS `<author>text</author>` and Atom `<author><name>..</name></author>`. */
    private fun readAuthor(parser: XmlPullParser): String? {
        var name: String? = null
        var fallback: String? = null
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && local(parser.name) == "author") break
            if (event == XmlPullParser.TEXT) {
                val t = parser.text?.trim()
                if (!t.isNullOrBlank()) fallback = t
            } else if (event == XmlPullParser.START_TAG && local(parser.name) == "name") {
                name = safeText(parser)
            }
        }
        return (name ?: fallback)?.takeIf { it.isNotBlank() }
    }

    private fun safeText(parser: XmlPullParser): String? =
        runCatching { parser.nextText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun attr(parser: XmlPullParser, name: String): String? =
        parser.getAttributeValue(null, name)

    private fun local(name: String?): String =
        (name ?: "").substringAfterLast(':').lowercase(Locale.ROOT)

    private fun isImage(type: String?, medium: String?, url: String): Boolean =
        type?.startsWith("image") == true ||
            medium == "image" ||
            url.substringBefore('?').substringAfterLast('.').lowercase(Locale.ROOT) in imageExts

    private fun parseDate(value: String?): Long? {
        val s = value?.trim().orEmpty()
        if (s.isEmpty()) return null
        for (fmt in dateFormats) {
            val result = runCatching { fmt.parse(s)?.time }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private companion object {
        val factory: XmlPullParserFactory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif")
        val dateFormats: List<SimpleDateFormat> = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        ).map { SimpleDateFormat(it, Locale.ENGLISH).apply { isLenient = true } }
    }
}
