package com.cairn.reader.data.repo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.cairn.reader.data.blob.BlobStore
import com.cairn.reader.data.opml.Opml
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemEntity
import com.cairn.reader.data.db.ItemFtsEntity
import com.cairn.reader.data.db.SourceDao
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.db.SyncDao
import com.cairn.reader.data.db.TombstoneEntity
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.domain.extract.ArticleExtractor
import com.cairn.reader.domain.feed.FeedDiscovery
import com.cairn.reader.domain.feed.FeedParser
import com.cairn.reader.domain.feed.ParsedItem
import com.cairn.reader.data.net.HttpFetcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
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
    private val siteFeedBuilder: com.cairn.reader.domain.feed.SiteFeedBuilder,
    private val parser: FeedParser,
    private val fetcher: HttpFetcher,
    private val extractor: ArticleExtractor,
    private val blobStore: BlobStore,
    private val preferencesRepository: PreferencesRepository,
    @ApplicationContext private val context: Context,
) {
    private val whitespace = Regex("\\s+")

    /** Discover and subscribe to a feed from any URL, importing its current items. */
    suspend fun addFeedByUrl(rawUrl: String): Result<String> {
        val result = when (val outcome = discovery.discover(rawUrl)) {
            is com.cairn.reader.domain.feed.Discovery.Found -> outcome.result
            // No declared feed — fall back to building one from the site's sitemap (P0 collector).
            is com.cairn.reader.domain.feed.Discovery.NotFound ->
                return followViaSitemap(rawUrl, outcome.reason)
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

    /** Follow a site that publishes no feed of its own by subscribing to a Google News
     *  "site:" search, which returns a real RSS feed of that site's recent articles.
     *  It's just an HTTP fetch of a public feed — no account — so it stays within the
     *  privacy model while making almost any site followable. */
    suspend fun followViaGoogleNews(rawInput: String): Result<String> {
        val host = normalize(rawInput)?.toHttpUrlOrNull()?.host?.removePrefix("www.")
            ?: return Result.failure(IllegalStateException("Enter a website address first."))
        val query = java.net.URLEncoder.encode("site:$host", "UTF-8")
        val gUrl = "https://news.google.com/rss/search?q=$query&hl=en-US&gl=US&ceid=US:en"
        val res = runCatching { fetcher.fetch(gUrl) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Couldn't reach Google News."))
        val feed = res.body?.let { parser.parse(it, res.finalUrl) }
            ?: return Result.failure(IllegalStateException("Google News has no articles for $host yet."))
        val now = System.currentTimeMillis()
        val sourceId = deterministicId(gUrl)
        val source = SourceEntity(
            id = sourceId,
            kind = "RSS",
            feedUrl = gUrl,
            siteUrl = "https://$host",
            title = "$host · via Google News",
        )
        sourceDao.upsert(source)
        feed.items.forEach { insertParsed(source, it, now) }
        return Result.success(sourceId)
    }

    /** Build and subscribe to a synthetic feed from a site's sitemap (P0 collector fallback).
     *  [reason] is the discovery failure to report if even the sitemap yields nothing. */
    suspend fun followViaSitemap(rawUrl: String, reason: String = "No feed found there."): Result<String> {
        val url = normalize(rawUrl) ?: return Result.failure(IllegalStateException("That doesn't look like a valid web address."))
        val feed = runCatching { siteFeedBuilder.build(url) }.getOrNull()
            ?: return Result.failure(IllegalStateException(reason))
        val now = System.currentTimeMillis()
        val origin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: url
        // Store the exact page the user pointed at, so a scraped index re-scrapes that page on sync.
        val sourceId = deterministicId("collector|$url")
        val source = SourceEntity(
            id = sourceId,
            kind = "SITEMAP",
            feedUrl = url,
            siteUrl = feed.siteUrl ?: origin,
            title = (feed.title?.takeIf { it.isNotBlank() } ?: hostOf(origin)) + " · via site",
        )
        sourceDao.upsert(source)
        feed.items.forEach { insertParsed(source, it, now) }
        return Result.success(sourceId)
    }

    /** Search far beyond what's stored locally: a Google News RSS query returns matching
     *  articles from across the web (well past a feed's short recent window). Just a public
     *  fetch — no account — so it stays within the privacy model. Empty on any failure. */
    suspend fun webSearch(query: String): List<com.cairn.reader.domain.feed.ParsedItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val url = "https://news.google.com/rss/search?q=" +
            java.net.URLEncoder.encode(q, "UTF-8") + "&hl=en-US&gl=US&ceid=US:en"
        val res = runCatching { fetcher.fetch(url) }.getOrNull() ?: return emptyList()
        val feed = res.body?.let { parser.parse(it, res.finalUrl) } ?: return emptyList()
        return feed.items
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

    /** Sync every feed. Returns the new items from notify-enabled feeds, so a background
     *  sync can raise notifications; foreground callers can ignore the result. */
    suspend fun syncAll(): List<com.cairn.reader.notifications.NewArticle> {
        val now = System.currentTimeMillis()
        val prefs = runCatching { preferencesRepository.preferences.first() }.getOrNull()
        val limit = prefs?.maxItemsPerFeed ?: 0
        val maxAgeDays = prefs?.maxAgeDays ?: 0
        val keepUnread = if (prefs?.keepUnread == true) 1 else 0
        val fresh = mutableListOf<com.cairn.reader.notifications.NewArticle>()
        sourceDao.getAll().forEach { source ->
            runCatching { syncSource(source, now, if (source.notify) fresh else null) }
            // Per-feed override wins: null → global cap, 0 → keep everything, N → keep newest N.
            val effLimit = source.maxItems ?: limit
            if (effLimit > 0) runCatching { pruneSource(source.id, effLimit, keepUnread) }
        }
        if (maxAgeDays > 0) runCatching { pruneOlderThan(now - maxAgeDays * 86_400_000L, keepUnread) }
        return fresh
    }

    /** Enforce the per-feed retention cap: drop the oldest items the user never engaged with,
     *  freeing their offline blobs and tombstoning them so a re-sync won't resurrect them. */
    private suspend fun pruneSource(sourceId: String, limit: Int, keepUnread: Int) {
        val over = itemDao.countBySource(sourceId) - limit
        if (over <= 0) return
        itemDao.prunableOldestFirst(sourceId, keepUnread).take(over).forEach { deleteItemFully(it) }
    }

    /** Age-based retention: drop un-engaged items older than [cutoff] across all feeds. */
    private suspend fun pruneOlderThan(cutoff: Long, keepUnread: Int) {
        itemDao.prunableOlderThan(cutoff, keepUnread).forEach { deleteItemFully(it) }
    }

    private suspend fun deleteItemFully(id: String) {
        val e = itemDao.getItem(id)
        blobStore.deleteAllFor(id, e?.blobPath)
        itemDao.deleteFts(id)
        itemDao.deleteItem(id)
        syncDao.tombstone(TombstoneEntity(itemId = id, deletedAt = System.currentTimeMillis()))
    }

    /** True when the active network is un-metered (Wi-Fi/Ethernet). Defaults to true if unknown,
     *  so an unclear network never silently blocks a save the user asked for. */
    private fun isUnmetered(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(true)

    private suspend fun syncSource(
        source: SourceEntity,
        now: Long,
        newItems: MutableList<com.cairn.reader.notifications.NewArticle>? = null,
    ) {
        // Sitemap-derived feeds are rebuilt from the site's sitemap each sync (no RSS to poll).
        if (source.kind == "SITEMAP") {
            val feed = runCatching { siteFeedBuilder.build(source.feedUrl) }.getOrNull()
            if (feed == null) { sourceDao.markError(source.id, null); return }
            feed.items.forEach { insertParsed(source, it, now, newItems) }
            sourceDao.markSynced(source.id, null, null, now)
            return
        }
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
        feed.items.forEach { insertParsed(source, it, now, newItems) }
        sourceDao.markSynced(source.id, res.etag, res.lastModified, now)
    }

    private suspend fun insertParsed(
        source: SourceEntity,
        p: ParsedItem,
        now: Long,
        newItems: MutableList<com.cairn.reader.notifications.NewArticle>? = null,
    ) {
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
                type = if (!p.audioUrl.isNullOrBlank() || source.isPodcast) "AUDIO" else detectType(p.link, hasBody = !content.isNullOrBlank()),
                excerpt = excerpt,
                leadImage = lead,
                wordCount = words,
                readingMinutes = minutes,
                blobPath = blobPath,
                extractStatus = "NONE",
                contentSource = "FEED",
                guid = p.guid ?: p.link,
                enclosureUrl = p.audioUrl,
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
        // Collect genuinely-new items for notification (only when the caller asked, i.e. a
        // background sync of a notify-enabled feed).
        if (isNew && newItems != null) {
            newItems += com.cairn.reader.notifications.NewArticle(
                id = itemId,
                title = p.title?.takeIf { it.isNotBlank() } ?: "(untitled)",
                source = source.title,
                excerpt = excerpt,
            )
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
                type = detectType(url, hasBody = false),
                extractStatus = "PENDING",
                contentSource = "READABLE",
            ),
            now,
        )
        itemDao.setReadLater(itemId, true, now)
        extractInto(itemId, url)
        return Result.success(itemId)
    }

    /** Import a PDF into the library: store the file verbatim and create a PDF-type item
     *  the reader opens with the on-device page renderer. Fully local — nothing is uploaded. */
    suspend fun importPdf(displayName: String, bytes: ByteArray): Result<String> {
        if (bytes.isEmpty()) return Result.failure(IllegalArgumentException("Empty file"))
        val now = System.currentTimeMillis()
        val title = displayName.removeSuffix(".pdf").removeSuffix(".PDF").trim().ifBlank { "Imported PDF" }
        val itemId = deterministicId("pdf|$title|$now")
        val path = runCatching { blobStore.writePdf(itemId, bytes) }.getOrElse {
            return Result.failure(it)
        }
        val thumb = renderPdfThumbnail(itemId, path)
        itemDao.insertItemWithState(
            ItemEntity(
                id = itemId,
                url = "file://$path",
                title = title,
                siteName = "PDF",
                savedAt = now,
                type = "PDF",
                excerpt = "Imported PDF",
                leadImage = thumb,
                blobPath = path,
                extractStatus = "OK",
                contentSource = "PDF",
                cacheStatus = "PERMANENT",
            ),
            now,
        )
        itemDao.setReadLater(itemId, true, now)
        itemDao.indexItem(ItemFtsEntity(itemId = itemId, title = title, author = null, body = null))
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
        // A saved bare link that turned out to have a real article body is promoted to ARTICLE,
        // so it filters and reads like one. Video/image classifications are left untouched.
        if (extracted.wordCount >= 200 && itemDao.getItem(itemId)?.type == "LINK") {
            itemDao.setType(itemId, "ARTICLE")
        }
    }

    /**
     * Make a permanent, self-contained offline copy of an item: ensure the full article is
     * extracted, download every image it references into the local blob store, rewrite the
     * HTML to point at those local files, and mark the item PERMANENT. The article then reads
     * fully offline and survives the source editing or deleting it. Returns the number of
     * images cached. Best-effort per image — a failed image keeps its remote URL rather than
     * aborting the whole save.
     */
    suspend fun saveOffline(itemId: String): Result<Int> {
        val item = itemDao.getItem(itemId) ?: return Result.failure(IllegalStateException("Item not found"))
        // Ensure we have the full readable body first (a summary-only item gets promoted).
        if (item.extractStatus != "OK" || item.blobPath.isNullOrBlank()) {
            runCatching { extractInto(itemId, item.url) }
        }
        val fresh = itemDao.getItem(itemId) ?: return Result.failure(IllegalStateException("Item not found"))
        val html = blobStore.readArticle(fresh.blobPath)
            ?: return Result.failure(IllegalStateException("No article content to save"))
        val doc = runCatching { Jsoup.parse(html, fresh.url) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Couldn't read the article"))

        // Honour the offline-image policy: images are optional, and may be restricted to Wi-Fi.
        val prefs = runCatching { preferencesRepository.preferences.first() }.getOrNull()
        val downloadImages = (prefs?.cacheImagesOffline ?: true) && (!(prefs?.imagesWifiOnly ?: true) || isUnmetered())

        var index = 0
        var cached = 0
        val seen = HashMap<String, String>() // remote URL → local file URI, deduped within the article
        val maxImages = 60

        suspend fun localize(remote: String): String? {
            if (!downloadImages) return null
            if (remote.isBlank() || remote.startsWith("file:") || remote.startsWith("data:")) return null
            seen[remote]?.let { return it }
            if (cached >= maxImages) return null
            val (bytes, contentType) = fetcher.fetchBytes(remote) ?: return null
            val local = runCatching { blobStore.writeImage(itemId, index++, bytes, imageExtension(contentType, remote)) }
                .getOrNull() ?: return null
            seen[remote] = local
            cached++
            return local
        }

        doc.select("img").forEach { img ->
            val remote = img.absUrl("src").takeIf { it.isNotBlank() } ?: img.attr("src")
            val local = if (remote.isNotBlank()) localize(remote) else null
            if (local != null) {
                img.attr("src", local)
                img.removeAttr("srcset"); img.removeAttr("data-src"); img.removeAttr("data-srcset"); img.removeAttr("loading")
            }
        }

        blobStore.writeArticle(itemId, doc.body().html()) // overwrites the same item-keyed blob
        // Cache the lead image too, so list thumbnails and the reader header survive offline.
        fresh.leadImage?.let { lead ->
            if (!lead.startsWith("file:")) localize(lead)?.let { itemDao.setLeadImage(itemId, it) }
        }
        itemDao.setCacheStatus(itemId, "PERMANENT")
        return Result.success(cached)
    }

    /** Render a PDF's first page to a small cover image so it has a real thumbnail in lists. */
    private fun renderPdfThumbnail(itemId: String, pdfPath: String): String? = runCatching {
        android.os.ParcelFileDescriptor.open(java.io.File(pdfPath), android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val targetW = 600
                    val scale = targetW.toFloat() / page.width.coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(targetW, h, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    blobStore.writeImage(itemId, 0, out.toByteArray(), "jpg")
                }
            }
        }
    }.getOrNull()

    /** Pick a sensible file extension for a downloaded image from its content type, then URL. */
    private fun imageExtension(contentType: String?, url: String): String = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        "image/bmp" -> "bmp"
        "image/avif" -> "avif"
        else -> url.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "img"
    }

    /** Classify an item into a Raindrop-style type from its URL and whether it carries an
     *  article body. Video and image are detected by host/extension; a feed item with real
     *  content is an ARTICLE; a bare saved link with no body is a LINK. */
    private fun detectType(url: String?, hasBody: Boolean): String {
        val u = (url ?: "").substringBefore('?').lowercase()
        val host = url?.toHttpUrlOrNull()?.host?.removePrefix("www.").orEmpty()
        val videoHosts = listOf("youtube.com", "youtu.be", "vimeo.com", "dailymotion.com", "twitch.tv")
        val videoExt = listOf(".mp4", ".webm", ".mov", ".m4v", ".mkv")
        val imageExt = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".bmp")
        return when {
            videoHosts.any { host == it || host.endsWith(".$it") } || videoExt.any { u.endsWith(it) } -> "VIDEO"
            imageExt.any { u.endsWith(it) } -> "IMAGE"
            hasBody -> "ARTICLE"
            else -> "LINK"
        }
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
