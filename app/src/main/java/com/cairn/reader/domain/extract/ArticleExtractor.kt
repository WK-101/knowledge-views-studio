package com.cairn.reader.domain.extract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.extended.Readability4JExtended
import org.jsoup.Jsoup
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.max

data class ExtractedArticle(
    val contentHtml: String,
    val title: String?,
    val excerpt: String?,
    val leadImage: String?,
    val plainText: String,
    val wordCount: Int,
    val readingMinutes: Int,
    val byline: String?,
    /** Original publish date parsed from the page (meta tags / JSON-LD / <time>), epoch millis, or
     *  null when the page carries no usable date. Lets a saved page show its real date, not the save
     *  time. */
    val publishedAt: Long? = null,
)

/**
 * On-device readable-content extraction via Readability4J (Mozilla's algorithm) with a
 * lazy-image repair pass first, so images that sites defer behind `data-src`/`srcset`
 * survive into the clean article. Pure JVM — no WebView.
 */
class ArticleExtractor @Inject constructor() {

    /**
     * Extract readable content. This does a full DOM build + Readability scoring, which is
     * CPU-heavy on long articles, so it runs on [Dispatchers.Default] — callers may invoke it
     * straight from a UI-scoped coroutine without blocking the main thread.
     */
    suspend fun extract(url: String, rawHtml: String): ExtractedArticle? = withContext(Dispatchers.Default) {
        // Parse the FULL document once (it still has <head>) so we can read the original publish date
        // from its metadata before Readability throws the head away, and reuse it for image repair.
        val fullDoc = runCatching { Jsoup.parse(rawHtml, url) }.getOrNull()
        val publishedAt = fullDoc?.let { runCatching { parsePublishedDate(it) }.getOrNull() }
        val prepared = fullDoc?.let { runCatching { promoteLazyImages(it); it.html() }.getOrNull() } ?: rawHtml
        val article = runCatching { Readability4JExtended(url, prepared).parse() }.getOrNull() ?: return@withContext null
        val contentHtml = article.content?.takeIf { it.isNotBlank() } ?: return@withContext null
        val plain = article.textContent?.takeIf { it.isNotBlank() }
            ?: runCatching { Jsoup.parse(contentHtml).text() }.getOrDefault("")
        val words = plain.split(WHITESPACE).count { it.isNotBlank() }
        val minutes = max(1, ceil(words / 220.0).toInt())
        val excerpt = article.excerpt?.takeIf { it.isNotBlank() }
            ?: plain.take(280).ifBlank { null }
        ExtractedArticle(
            contentHtml = contentHtml,
            title = article.title?.takeIf { it.isNotBlank() },
            excerpt = excerpt,
            leadImage = firstImage(contentHtml, url),
            plainText = plain,
            wordCount = words,
            readingMinutes = minutes,
            byline = article.byline?.takeIf { it.isNotBlank() },
            publishedAt = publishedAt,
        )
    }

    /**
     * Best-effort original publish date, in preference order: OpenGraph / schema meta tags, then
     * JSON-LD `datePublished`, then a `<time>` element. Only dates in a sane window (past ~1990,
     * not future) are accepted, so a template's placeholder can't poison the value.
     */
    private fun parsePublishedDate(doc: org.jsoup.nodes.Document): Long? {
        for (sel in META_DATE_SELECTORS) {
            val content = doc.selectFirst(sel)?.attr("content")?.trim()
            parseDateString(content)?.let { return it }
        }
        // JSON-LD: pull the first "datePublished": "..." (covers NewsArticle/Article/BlogPosting).
        for (script in doc.select("script[type=application/ld+json]")) {
            val m = JSONLD_DATE.find(script.data())
            parseDateString(m?.groupValues?.getOrNull(1))?.let { return it }
        }
        doc.selectFirst("time[itemprop=datePublished][datetime], time[pubdate][datetime], time[datetime]")
            ?.attr("datetime")?.let { parseDateString(it)?.let { d -> return d } }
        return null
    }

    /** Parse a date string in any of the common web formats (ISO-8601 with/without zone, date-only,
     *  RFC-1123) to epoch millis, or null. Rejects values outside a plausible article-date window. */
    private fun parseDateString(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val attempts: List<(String) -> Long> = listOf(
            { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() },
            { java.time.Instant.parse(it).toEpochMilli() },
            { java.time.ZonedDateTime.parse(it).toInstant().toEpochMilli() },
            { java.time.LocalDateTime.parse(it).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() },
            { java.time.LocalDate.parse(it).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() },
            { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() },
        )
        val now = System.currentTimeMillis()
        for (a in attempts) {
            val ms = runCatching { a(s) }.getOrNull()
            if (ms != null && ms in MIN_DATE_MS..(now + 2 * 86_400_000L)) return ms
        }
        return null
    }

    private fun promoteLazyImages(doc: org.jsoup.nodes.Document) {
        for (img in doc.select("img")) {
            val src = img.attr("src")
            if (src.isBlank() || src.startsWith("data:")) {
                val lazy = LAZY_ATTRS.map { img.attr(it) }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                if (lazy != null) img.attr("src", lazy)
            }
            if (img.attr("srcset").isBlank()) {
                val lazySet = img.attr("data-srcset")
                if (lazySet.isNotBlank()) img.attr("srcset", lazySet)
            }
        }
    }

    private fun firstImage(contentHtml: String, baseUrl: String): String? {
        val doc = runCatching { Jsoup.parse(contentHtml, baseUrl) }.getOrNull() ?: return null
        val img = doc.selectFirst("img") ?: return null
        return img.absUrl("src").takeIf { it.isNotBlank() } ?: img.attr("src").takeIf { it.isNotBlank() }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val LAZY_ATTRS = listOf("data-src", "data-original", "data-lazy-src", "data-src-large", "data-hi-res-src")
        // Meta tags that carry a publish date, in descending trust order (published before modified).
        val META_DATE_SELECTORS = listOf(
            "meta[property=article:published_time]",
            "meta[name=article:published_time]",
            "meta[property=og:published_time]",
            "meta[itemprop=datePublished]",
            "meta[name=datePublished]",
            "meta[name=parsely-pub-date]",
            "meta[name=dc.date.issued]",
            "meta[name=dcterms.date]",
            "meta[name=dc.date]",
            "meta[name=publish-date]",
            "meta[name=publishdate]",
            "meta[name=pubdate]",
            "meta[name=date]",
            "meta[property=article:modified_time]",
            "meta[itemprop=dateModified]",
        )
        val JSONLD_DATE = Regex("\"datePublished\"\\s*:\\s*\"([^\"]+)\"")
        // Reject anything before 1990-01-01 — placeholder/garbage dates ("0001-01-01", epoch 0).
        const val MIN_DATE_MS = 631_152_000_000L
    }
}
