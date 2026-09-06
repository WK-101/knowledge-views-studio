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
        val prepared = runCatching { promoteLazyImages(rawHtml, url) }.getOrDefault(rawHtml)
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
        )
    }

    private fun promoteLazyImages(html: String, baseUrl: String): String {
        val doc = Jsoup.parse(html, baseUrl)
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
        return doc.html()
    }

    private fun firstImage(contentHtml: String, baseUrl: String): String? {
        val doc = runCatching { Jsoup.parse(contentHtml, baseUrl) }.getOrNull() ?: return null
        val img = doc.selectFirst("img") ?: return null
        return img.absUrl("src").takeIf { it.isNotBlank() } ?: img.attr("src").takeIf { it.isNotBlank() }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val LAZY_ATTRS = listOf("data-src", "data-original", "data-lazy-src", "data-src-large", "data-hi-res-src")
    }
}
