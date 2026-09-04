package com.cairn.reader.data.repo

import com.cairn.reader.data.blob.BlobStore
import com.cairn.reader.data.opml.Opml
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemEntity
import com.cairn.reader.data.db.ItemFtsEntity
import com.cairn.reader.data.db.SourceDao
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.db.SyncDao
import com.cairn.reader.domain.extract.ArticleExtractor
import com.cairn.reader.domain.feed.FeedDiscovery
import com.cairn.reader.domain.feed.FeedParser
import com.cairn.reader.domain.feed.ParsedItem
import com.cairn.reader.data.net.HttpFetcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max

/**
 * Owns capture and sync: discovering feeds from a URL, pulling new items with
 * conditional GET, saving arbitrary URLs (with on-device extraction), and running
 * Readability on demand. All content bodies are gzipped to the [BlobStore].
 */
@Singleton
class FeedRepository @Inject constructor(
    private val sourceDao: SourceDao,
    private val itemDao: ItemDao,
    private val syncDao: SyncDao,
    private val discovery: FeedDiscovery,
    private val parser: FeedParser,
    private val fetcher: HttpFetcher,
    private val extractor: ArticleExtractor,
    private val blobStore: BlobStore,
) {
    private val whitespace = Regex("\\s+")

    /** Discover and subscribe to a feed from any URL, importing its current items. */
    suspend fun addFeedByUrl(rawUrl: String): Result<String> {
        val result = when (val outcome = discovery.discover(rawUrl)) {
            is com.cairn.reader.domain.feed.Discovery.Found -> outcome.result
            is com.cairn.reader.domain.feed.Discovery.NotFound -> return Result.failure(IllegalStateException(outcome.reason))
        }
        val now = System.currentTimeMillis()
        val sourceId = deterministicId(result.feedUrl)
        val source = SourceEntity(
            id = sourceId,
            kind = "RSS",
            feedUrl = result.feedUrl,
            siteUrl = result.feed.siteUrl,
            title = result.feed.title?.takeIf { it.isNotBlank() } ?: hostOf(result.feedUrl),
        )
        sourceDao.upsert(source)
        result.feed.items.forEach { insertParsed(source, it, now) }
        return Result.success(sourceId)
    }

    /** On first run, subscribe to a few well-known feeds so the app has real content
     *  after the first sync. Returns true if it seeded (i.e. there were no sources). */
    suspend fun seedDefaultFeedsIfEmpty(): Boolean {
        if (sourceDao.getAll().isNotEmpty()) return false
        val defaults = listOf(
            "https://hnrss.org/frontpage" to "Hacker News",
            "https://www.theverge.com/rss/index.xml" to "The Verge",
            "https://feeds.arstechnica.com/arstechnica/index" to "Ars Technica",
        )
        defaults.forEachIndexed { index, (feedUrl, title) ->
            sourceDao.upsert(
                SourceEntity(
                    id = deterministicId(feedUrl),
                    kind = "RSS",
                    feedUrl = feedUrl,
                    title = title,
                    sortOrder = index,
                ),
            )
        }
        return true
    }

    /** Subscribe to every feed in an OPML document, preserving folders and skipping
     *  feeds already subscribed. Returns how many new sources were added. */
    suspend fun importOpml(xml: String): Int {
        val feeds = Opml.parse(xml)
        var added = 0
        feeds.forEachIndexed { index, f ->
            val existing = sourceDao.getByFeedUrl(f.xmlUrl)
            if (existing == null) {
                sourceDao.upsert(
                    SourceEntity(
                        id = deterministicId(f.xmlUrl),
                        kind = "RSS",
                        feedUrl = f.xmlUrl,
                        title = f.title.ifBlank { hostOf(f.xmlUrl) },
                        folder = f.folder,
                        sortOrder = index,
                    ),
                )
                added++
            } else if (existing.folder.isNullOrBlank() && !f.folder.isNullOrBlank()) {
                sourceDao.setFolder(existing.id, f.folder)
            }
        }
        return added
    }

    suspend fun exportOpml(): String = Opml.build(sourceDao.getAll())

    suspend fun syncAll() {
        val now = System.currentTimeMillis()
        sourceDao.getAll().forEach { source ->
            runCatching { syncSource(source, now) }
        }
    }

    private suspend fun syncSource(source: SourceEntity, now: Long) {
        val res = fetcher.fetch(source.feedUrl, source.etag, source.lastModified)
        if (res.notModified) {
            sourceDao.markSynced(source.id, source.etag, source.lastModified, now)
            return
        }
        val body = res.body
        val feed = body?.let { parser.parse(it, res.finalUrl) }
        if (feed == null) {
            sourceDao.markError(source.id, null)
            return
        }
        feed.items.forEach { insertParsed(source, it, now) }
        sourceDao.markSynced(source.id, res.etag, res.lastModified, now)
    }

    private suspend fun insertParsed(source: SourceEntity, p: ParsedItem, now: Long) {
        val key = p.guid ?: p.link ?: p.title ?: UUID.randomUUID().toString()
        val itemId = deterministicId("${source.id}|$key")
        if (syncDao.isTombstoned(itemId)) return
        val isNew = itemDao.getItem(itemId) == null

        val content = p.contentHtml ?: p.summary
        val plain = content?.let { runCatching { Jsoup.parse(it).text() }.getOrDefault("") } ?: ""
        val words = plain.split(whitespace).count { it.isNotBlank() }
        val minutes = if (words > 0) max(1, ceil(words / 220.0).toInt()) else 0
        val blobPath = content?.takeIf { it.isNotBlank() }?.let { blobStore.writeArticle(itemId, it) }
        val excerpt = (p.summary?.let { runCatching { Jsoup.parse(it).text() }.getOrNull() } ?: plain)
            .trim().take(300).ifBlank { null }
        val lead = p.imageUrl ?: content?.let { firstImage(it, source.siteUrl ?: source.feedUrl) }

        itemDao.insertItemWithState(
            ItemEntity(
                id = itemId,
                url = p.link ?: source.siteUrl ?: source.feedUrl,
                title = p.title?.takeIf { it.isNotBlank() } ?: "(untitled)",
                author = p.author,
                siteName = source.title,
                publishedAt = p.publishedAt,
                savedAt = now,
                sourceId = source.id,
                type = "ARTICLE",
                excerpt = excerpt,
                leadImage = lead,
                wordCount = words,
                readingMinutes = minutes,
                blobPath = blobPath,
                extractStatus = "NONE",
                contentSource = "FEED",
                guid = p.guid ?: p.link,
            ),
            now,
        )
        itemDao.indexItem(
            ItemFtsEntity(itemId = itemId, title = p.title ?: "", author = p.author, body = plain.take(20_000)),
        )
        // Per-feed "full text on sync": fetch the whole article for new items so they're
        // complete and offline before they're ever opened. Opt-in, so most feeds stay cheap.
        if (isNew && source.fullTextByDefault) {
            p.link?.takeIf { it.isNotBlank() }?.let { runCatching { extractInto(itemId, it) } }
        }
    }

    /** Save an arbitrary URL to the library and extract a clean, offline copy. */
    suspend fun saveUrl(rawUrl: String): Result<String> {
        val url = normalize(rawUrl) ?: return Result.failure(IllegalArgumentException("Invalid URL"))
        val now = System.currentTimeMillis()
        val itemId = deterministicId("save|$url")
        itemDao.insertItemWithState(
            ItemEntity(
                id = itemId,
                url = url,
                title = hostOf(url),
                savedAt = now,
                type = "LINK",
                extractStatus = "PENDING",
                contentSource = "READABLE",
            ),
            now,
        )
        itemDao.setReadLater(itemId, true, now)
        extractInto(itemId, url)
        return Result.success(itemId)
    }

    /** Run Readability for an already-saved item (the reader's "load full article"). */
    suspend fun extractFull(itemId: String) {
        val item = itemDao.getItem(itemId) ?: return
        extractInto(itemId, item.url)
    }

    private suspend fun extractInto(itemId: String, url: String) {
        val res = runCatching { fetcher.fetch(url) }.getOrNull()
        val extracted = res?.body?.let { extractor.extract(res.finalUrl, it) }
        if (extracted == null) {
            // Keep whatever content we already have (e.g. the feed body); just record the failure.
            itemDao.setExtractStatus(itemId, "FAILED")
            return
        }
        val blob = blobStore.writeArticle(itemId, extracted.contentHtml)
        extracted.title?.let { itemDao.updateMeta(itemId, it, extracted.byline, hostOf(url)) }
        itemDao.setExtracted(
            id = itemId,
            blobPath = blob,
            excerpt = extracted.excerpt,
            wordCount = extracted.wordCount,
            minutes = extracted.readingMinutes,
            leadImage = extracted.leadImage,
            status = "OK",
            contentSource = "READABLE",
        )
        itemDao.indexItem(
            ItemFtsEntity(itemId, extracted.title ?: "", extracted.byline, extracted.plainText.take(20_000)),
        )
    }

    private fun firstImage(html: String, baseUrl: String): String? = runCatching {
        val img = Jsoup.parse(html, baseUrl).selectFirst("img") ?: return null
        img.absUrl("src").takeIf { it.isNotBlank() } ?: img.attr("src").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url

    private fun normalize(raw: String): String? {
        val t = raw.trim()
        if (t.isBlank()) return null
        val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        return withScheme.toHttpUrlOrNull()?.toString()
    }

    private fun deterministicId(key: String): String =
        UUID.nameUUIDFromBytes(key.toByteArray()).toString()
}
