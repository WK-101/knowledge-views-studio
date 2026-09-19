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
import com.cairn.reader.util.AppLog
import com.cairn.reader.util.coRunCatching
import com.cairn.reader.util.orLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max
import com.cairn.reader.data.db.CacheStatus
import com.cairn.reader.data.db.ContentSource
import com.cairn.reader.data.db.ExtractStatus
import com.cairn.reader.data.db.ItemType

/**
 * v3.99.15 (Content Engine P2): search is now truly full-text. We index the WHOLE plain-text body
 * (not the old ~600-word window) so a phrase buried deep in a long article is findable offline. This
 * value is only a safety ceiling for pathological pages (~30k words); real articles index in full.
 * The one-time [reindexFullText] pass rebuilds existing rows to this depth; new items index in full
 * on sync/extract. The archive crawler (P3) then makes every historical article searchable this way.
 */
private const val FTS_BODY_CHARS = 200_000

/**
 * Owns capture and sync: discovering feeds from a URL, pulling new items with
 * conditional GET, saving arbitrary URLs (with on-device extraction), and running
 * Readability on demand. All content bodies are gzipped to the [BlobStore].
 */
/** Result of a one-feed freshness check ([FeedRepository.verifyFeed]): whether it fetched and parsed
 *  cleanly right now, how many items it holds, the newest item's date, and any error to show. */
data class FeedVerification(
    val ok: Boolean,
    val itemCount: Int,
    val newestItemAt: Long?,
    val checkedAt: Long,
    val error: String? = null,
)

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
    private val webViewRenderer: com.cairn.reader.domain.render.WebViewRenderer,
    private val preferencesRepository: PreferencesRepository,
    private val sanitizer: com.cairn.reader.domain.privacy.ContentSanitizer,
    private val ruleEngine: com.cairn.reader.domain.rules.RuleEngine,
    private val youtubeProxy: com.cairn.reader.domain.transcript.YouTubeProxyResolver,
    private val captionFetcher: com.cairn.reader.domain.transcript.CaptionFetcher,
    @ApplicationContext private val context: Context,
) {
    private val whitespace = Regex("\\s+")

    /** Apply the user's global new-feed defaults (folder / full-text / notify) to a freshly built
     *  source before it's saved. Only fills a folder the source doesn't already have, and only turns
     *  options on — so a subscribe path that set its own value is never overridden. */
    private suspend fun withFeedDefaults(source: SourceEntity): SourceEntity {
        val p = preferencesRepository.preferences.first()
        // Apply the global default acquisition mode only when the source hasn't already chosen one
        // (i.e. it's still the plain FEED default); an explicit per-subscribe choice is never overridden.
        val acquisition = if (source.acquisitionMode == com.cairn.reader.data.db.AcquisitionMode.FEED.raw) {
            p.defaultAcquisitionMode
        } else {
            source.acquisitionMode
        }
        return source.copy(
            folder = source.folder ?: p.defaultFeedFolder.trim().ifBlank { null },
            fullTextByDefault = source.fullTextByDefault || p.defaultFeedFullText,
            notify = source.notify || p.defaultFeedNotify,
            acquisitionMode = acquisition,
        )
    }

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
            hubUrl = result.feed.hubUrl,
        )
        sourceDao.upsert(withFeedDefaults(source))
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
        val res = coRunCatching { fetcher.fetch(gUrl) }.getOrNull()
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
        sourceDao.upsert(withFeedDefaults(source))
        feed.items.forEach { insertParsed(source, it, now) }
        return Result.success(sourceId)
    }

    /** Build and subscribe to a synthetic feed from a site's sitemap (P0 collector fallback).
     *  [reason] is the discovery failure to report if even the sitemap yields nothing. */
    suspend fun followViaSitemap(rawUrl: String, reason: String = "No feed found there."): Result<String> {
        val url = normalize(rawUrl) ?: return Result.failure(IllegalStateException("That doesn't look like a valid web address."))
        val feed = coRunCatching { siteFeedBuilder.build(url) }.getOrNull()
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
        sourceDao.upsert(withFeedDefaults(source))
        feed.items.forEach { insertParsed(source, it, now) }
        return Result.success(sourceId)
    }

    /** Teach-by-example (P3 collector): subscribe to a feed built from a CSS selector the user
     *  chose by tapping a headline on the page. Re-scraped with that selector each sync. */
    suspend fun followViaSelector(rawUrl: String, selector: String): Result<String> {
        val url = normalize(rawUrl) ?: return Result.failure(IllegalStateException("That doesn't look like a valid web address."))
        val feed = coRunCatching { siteFeedBuilder.buildWithSelector(url, selector) }.getOrNull()
            ?: return Result.failure(IllegalStateException("That selection didn't match any links."))
        val now = System.currentTimeMillis()
        val origin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: url
        val sourceId = deterministicId("taught|$url|$selector")
        val source = SourceEntity(
            id = sourceId, kind = "SITEMAP", feedUrl = url, siteUrl = feed.siteUrl ?: origin,
            title = (feed.title?.takeIf { it.isNotBlank() } ?: hostOf(origin)) + " · custom",
            scrapeSelector = selector,
        )
        sourceDao.upsert(withFeedDefaults(source))
        feed.items.forEach { insertParsed(source, it, now) }
        return Result.success(sourceId)
    }

    /** Watch any page for changes (P3 collector): each time its main text changes, a new item
     *  appears linking to the page. Great for release notes, job boards, or list pages with no feed. */
    suspend fun watchPage(rawUrl: String): Result<String> {
        val url = normalize(rawUrl) ?: return Result.failure(IllegalStateException("That doesn't look like a valid web address."))
        val res = coRunCatching { fetcher.fetch(url) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Couldn't reach ${hostOf(url)}."))
        val body = res.body ?: return Result.failure(IllegalStateException("That page returned no content."))
        val host = hostOf(url)
        val sourceId = deterministicId("watch|$url")
        val source = SourceEntity(id = sourceId, kind = "WATCH", feedUrl = url, siteUrl = url, title = "$host · watched")
        sourceDao.upsert(withFeedDefaults(source))
        val now = System.currentTimeMillis()
        val hash = pageTextHash(body)
        insertWatchSnapshot(source, url, now)
        sourceDao.setContentHash(sourceId, hash, now)
        return Result.success(sourceId)
    }

    private fun pageTextHash(html: String): String =
        coRunCatching { Jsoup.parse(html).body().text() }.getOrDefault(html).hashCode().toString()

    private suspend fun insertWatchSnapshot(
        source: SourceEntity,
        url: String,
        now: Long,
        newItems: MutableList<com.cairn.reader.notifications.NewArticle>? = null,
    ) {
        insertParsed(
            source,
            ParsedItem(
                guid = "$url#${now / 60000}", // per-minute guid so each detected change is a new item
                title = "${source.title.removeSuffix(" · watched")} updated",
                link = url, author = null, publishedAt = now,
                contentHtml = null, summary = "This watched page changed.", imageUrl = null,
            ),
            now,
            newItems,
        )
    }

    /** Search far beyond what's stored locally: a Google News RSS query returns matching
     *  articles from across the web (well past a feed's short recent window). Just a public
     *  fetch — no account — so it stays within the privacy model. Empty on any failure. */
    suspend fun webSearch(query: String): List<com.cairn.reader.domain.feed.ParsedItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val url = "https://news.google.com/rss/search?q=" +
            java.net.URLEncoder.encode(q, "UTF-8") + "&hl=en-US&gl=US&ceid=US:en"
        val res = coRunCatching { fetcher.fetch(url) }.getOrNull() ?: return emptyList()
        val feed = res.body?.let { parser.parse(it, res.finalUrl) } ?: return emptyList()
        return feed.items
    }

    /**
     * Search a single site's entire published archive — every article it ever put out, not just
     * the recent feed window. WordPress sites answer this exactly via their REST `search` API; for
     * everything else we fall back to a site-scoped Google News search, which still reaches far
     * beyond the live feed. [siteUrl] is any URL on the site (its feed or home page).
     */
    suspend fun searchArchive(siteUrl: String, query: String): List<com.cairn.reader.domain.feed.ParsedItem> {
        val q = query.trim()
        if (q.isBlank() || siteUrl.isBlank()) return emptyList()
        val wp = coRunCatching { siteFeedBuilder.searchWordPressArchive(siteUrl, q) }.getOrDefault(emptyList())
        if (wp.isNotEmpty()) return wp
        val host = coRunCatching { java.net.URI(siteUrl).host?.removePrefix("www.") }.getOrNull()
        return if (host != null) webSearch("$q site:$host") else emptyList()
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
    // Runs on Default so the per-feed XML parse + per-item Jsoup.parse never execute on the caller's
    // thread. WorkManager's CoroutineWorker is already off-main, but "Sync now" / pull-to-refresh are
    // launched from viewModelScope (Main); this keeps their heavy parsing off the UI thread too.
    suspend fun syncAll(force: Boolean = false): List<com.cairn.reader.notifications.NewArticle> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val fresh = mutableListOf<com.cairn.reader.notifications.NewArticle>()
        // No network → don't even try: a whole offline pass would otherwise throw a connect error
        // per feed and bury the log in one warning per feed (making every feed look broken). Local
        // upkeep still runs so retention/trash purge happen offline too.
        if (!isOnline()) {
            AppLog.w("sync skipped: no network")
            runMaintenance()
            return@withContext fresh
        }
        // WebSub-aware ordering: feeds that declare a real-time hub sync first, so "live" sources
        // are the freshest even though a serverless client can't hold a push callback.
        // Paused feeds are excluded from sync entirely (they stay visible with their items readable);
        // only the sync loop skips them — retention/pruning in runMaintenance still covers them.
        val sources = sourceDao.getAll().filterNot { it.syncPaused }.sortedByDescending { it.hubUrl != null }
        val failures = ArrayList<Pair<String, Throwable>>()
        sources.forEach { source ->
            coroutineContext.ensureActive()  // honor cancellation between feeds
            coRunCatching { syncSource(source, now, if (source.notify) fresh else null, force) }
                .onFailure { failures.add(source.feedUrl to it) }
        }
        // Log compactly: a total outage (every feed failed) is one line — almost always the network
        // dropped mid-sync, not the feeds. A partial failure logs each feed with its cause so a
        // genuinely broken feed is diagnosable without 70 lines of noise drowning it.
        if (failures.isNotEmpty()) {
            if (failures.size == sources.size && sources.size > 3) {
                AppLog.w("sync: all ${sources.size} feeds failed this pass — likely a network drop (${failures.first().second.javaClass.simpleName})")
            } else {
                failures.forEach { (url, e) -> AppLog.w("sync failed for $url — ${e.javaClass.simpleName}: ${e.message?.take(140)}") }
            }
        }
        // Retention + trash auto-purge, unchanged from when it lived inline here. Pruning each feed
        // only reads/deletes its own items, so running it after all feeds have synced yields the
        // same final state as the old interleaved pass.
        runMaintenance()
        fresh
    }

    /**
     * Verify ONE feed on demand: force-fetch it (no conditional-GET, so we always see the publisher's
     * current items), ingest anything new, and report what we found — item count, newest item date,
     * and whether it fetched+parsed cleanly. Powers the per-feed "Verify now" action and the freshness
     * badge, so a user can confirm a feed is up to date with the latest posts.
     */
    suspend fun verifyFeed(sourceId: String): FeedVerification = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val source = sourceDao.getById(sourceId)
            ?: return@withContext FeedVerification(ok = false, itemCount = 0, newestItemAt = null, checkedAt = now, error = "Feed not found")
        if (!isOnline()) {
            return@withContext FeedVerification(ok = false, itemCount = itemDao.countForSource(sourceId), newestItemAt = source.latestItemAt, checkedAt = now, error = "No network")
        }
        val outcome = coRunCatching { syncSource(source, now, null, force = true) }
        val after = sourceDao.getById(sourceId)
        val count = itemDao.countForSource(sourceId)
        outcome.fold(
            onSuccess = {
                val healthy = (after?.consecutiveErrors ?: 0) == 0
                FeedVerification(
                    ok = healthy,
                    itemCount = count,
                    newestItemAt = after?.latestItemAt,
                    checkedAt = now,
                    error = if (healthy) null else "Reached the feed but found no readable items",
                )
            },
            onFailure = { e ->
                FeedVerification(ok = false, itemCount = count, newestItemAt = after?.latestItemAt, checkedAt = now, error = "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            },
        )
    }

    /**
     * Local upkeep that must happen whether or not feeds sync: enforce each feed's retention cap
     * ([pruneSource]), age-prune un-engaged items across all feeds ([pruneOlderThan], honoring
     * maxAgeDays / keepUnread), and empty the Trash of anything past its grace period
     * ([purgeExpiredTrash]). Extracted from [syncAll] (which still calls it) so a periodic
     * maintenance worker can run it for users who have no feeds or have sync turned off — otherwise
     * those users would never get age pruning or trash auto-purge. Fully local (DB + on-disk blobs);
     * no network.
     */
    suspend fun runMaintenance() = withContext(Dispatchers.Default) {
        val prefs = coRunCatching { preferencesRepository.preferences.first() }.getOrNull()
        val limit = prefs?.maxItemsPerFeed ?: 0
        val maxAgeDays = prefs?.maxAgeDays ?: 0
        val keepUnread = if (prefs?.keepUnread == true) 1 else 0
        val now = System.currentTimeMillis()
        sourceDao.getAll().forEach { source ->
            coroutineContext.ensureActive()  // honor cancellation between feeds
            // Per-feed override wins: null → global cap, 0 → keep everything, N → keep newest N.
            val effLimit = source.maxItems ?: limit
            if (effLimit > 0) coRunCatching { pruneSource(source.id, effLimit, keepUnread) }
        }
        if (maxAgeDays > 0) coRunCatching { pruneOlderThan(now - maxAgeDays * 86_400_000L, keepUnread) }
        // Empty out anything that has sat in the Trash past the grace period.
        coRunCatching { purgeExpiredTrash() }
        // One-time (Content Engine P2): rebuild the FTS index of already-stored items at full depth,
        // so historic articles become fully searchable, not just their first ~600 words.
        if (prefs?.ftsFullReindexed != true) coRunCatching { reindexFullText() }
        // One-time (Content Engine P4): fingerprint already-stored bodies for near-dup collapse.
        if (prefs?.simHashBackfilled != true) coRunCatching { backfillSimHashes() }
    }

    /**
     * Re-index every stored item's full body text into FTS (Content Engine P2). Items were previously
     * indexed with only a ~600-word body window; this rebuilds them from their on-disk article so the
     * whole text is searchable offline. One-time and idempotent — a device-local pref flag marks it
     * done. Runs in small batches on [Dispatchers.Default], honouring cancellation.
     */
    suspend fun reindexFullText() = withContext(Dispatchers.Default) {
        val ids = coRunCatching { itemDao.idsWithBody() }.getOrDefault(emptyList())
        for (id in ids) {
            coroutineContext.ensureActive()
            val e = coRunCatching { itemDao.getItem(id) }.getOrNull() ?: continue
            val html = coRunCatching { blobStore.readArticle(e.blobPath) }.getOrNull() ?: continue
            val plain = coRunCatching { Jsoup.parse(html).text() }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            coRunCatching {
                itemDao.indexItem(ItemFtsEntity(itemId = id, title = e.title, author = e.author, body = plain.take(FTS_BODY_CHARS)))
            }
            // Backfill the near-dup fingerprint for pre-P4 rows while we already have the body in hand.
            if (e.simHash == 0L) coRunCatching { itemDao.setSimHash(id, com.cairn.reader.domain.dedupe.SimHash.compute(plain)) }
        }
        coRunCatching { preferencesRepository.setFtsFullReindexed(true) }
        Unit
    }

    /**
     * One-time (Content Engine P4): compute the [SimHash] near-duplicate fingerprint for every stored
     * item that still has none (simHash == 0) but does have an on-disk body — the rows saved before
     * P4 existed. Idempotent and device-local; a pref flag marks it done so it runs at most once. Small
     * batches on [Dispatchers.Default], cancellation-honouring. (When the P2 full re-index runs on this
     * same install it already fingerprints in-line, so on most devices this pass finds nothing to do.)
     */
    suspend fun backfillSimHashes() = withContext(Dispatchers.Default) {
        val ids = coRunCatching { itemDao.idsWithBody() }.getOrDefault(emptyList())
        for (id in ids) {
            coroutineContext.ensureActive()
            val e = coRunCatching { itemDao.getItem(id) }.getOrNull() ?: continue
            if (e.simHash != 0L) continue
            val html = coRunCatching { blobStore.readArticle(e.blobPath) }.getOrNull() ?: continue
            val plain = coRunCatching { Jsoup.parse(html).text() }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            coRunCatching { itemDao.setSimHash(id, com.cairn.reader.domain.dedupe.SimHash.compute(plain)) }
        }
        coRunCatching { preferencesRepository.setSimHashBackfilled(true) }
        Unit
    }

    /** Enforce the per-feed retention cap: drop the oldest items the user never engaged with,
     *  freeing their offline blobs and tombstoning them so a re-sync won't resurrect them. */
    private suspend fun pruneSource(sourceId: String, limit: Int, keepUnread: Int) {
        val over = itemDao.countBySource(sourceId) - limit
        if (over <= 0) return
        deleteItemsFully(itemDao.prunableOldestFirst(sourceId, keepUnread).take(over))
    }

    /** Age-based retention: drop un-engaged items older than [cutoff] across all feeds. */
    private suspend fun pruneOlderThan(cutoff: Long, keepUnread: Int) {
        deleteItemsFully(itemDao.prunableOlderThan(cutoff, keepUnread))
    }

    private suspend fun deleteItemFully(id: String) {
        val e = itemDao.getItem(id)
        blobStore.deleteAllFor(id, e?.blobPath)
        itemDao.deleteFts(id)
        itemDao.deleteItem(id)
        syncDao.tombstone(TombstoneEntity(itemId = id, deletedAt = System.currentTimeMillis()))
    }

    /** Chunk size for bulk deletes. Kept small enough that the batched tombstone insert — which binds
     *  two variables per row — stays well under SQLite's bound-variable limit. */
    private val deleteChunk = 450

    /**
     * Bulk equivalent of [deleteItemFully] for a whole id set. Reproduces the identical single-item
     * semantics (free on-disk blobs + drop the FTS row + delete the item, whose FK cascades clear
     * states / tags / collections / highlights + tombstone the id) without N×(getItem + 3 writes):
     *   1. read every item's blobPath up front, before any row is deleted;
     *   2. per chunk, delete the item + FTS rows and tombstone the ids in one transaction;
     *   3. free the on-disk blobs in a single pass afterwards (blobs live on disk, not in the DB).
     * Every input id gets a blob cleanup pass (a missing row → null path), mirroring the single path
     * which still clears an item's media images even when its row is already gone.
     */
    private suspend fun deleteItemsFully(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val idList = ids.toList()
        val blobPaths = HashMap<String, String?>(idList.size)
        idList.chunked(deleteChunk).forEach { chunk ->
            itemDao.blobPathsFor(chunk).forEach { blobPaths[it.id] = it.blobPath }
        }
        val now = System.currentTimeMillis()
        idList.chunked(deleteChunk).forEach { chunk -> itemDao.deleteItemsWithFtsAndTombstones(chunk, now) }
        idList.forEach { id -> blobStore.deleteAllFor(id, blobPaths[id]) }
    }

    /**
     * User-initiated permanent delete of a single item. Removes its offline copy and index and
     * tombstones it, so the next sync won't resurrect it from the feed. This is the only thing
     * that removes an item — feeds otherwise keep accumulating (retention defaults to "keep
     * everything"), so nothing disappears unless the user deletes it or sets a retention cap.
     *
     * Returns the item's pre-delete row so the caller can offer a brief Undo; pass it back to
     * [restoreItem]. (The offline blob is gone, so a restored item re-extracts on next open.)
     */
    suspend fun deleteItem(id: String): ItemEntity? {
        val snapshot = itemDao.getItem(id)
        deleteItemFully(id)
        return snapshot
    }

    /** Delete several items at once (bulk multi-select). */
    suspend fun deleteItems(ids: Collection<String>) = deleteItemsFully(ids)

    /** Undo a [deleteItem]: lift the tombstone and re-insert the item (content re-extracts on open). */
    suspend fun restoreItem(snapshot: ItemEntity) {
        syncDao.clearTombstone(snapshot.id)
        itemDao.insertItemWithState(
            snapshot.copy(blobPath = null, extractStatus = if (snapshot.extractStatus == ExtractStatus.OK.raw) ExtractStatus.NONE.raw else snapshot.extractStatus),
            System.currentTimeMillis(),
        )
    }

    // -- Trash (soft-delete) ---------------------------------------------------
    //
    // The default delete now moves an item to the Trash instead of erasing it: the row and its
    // offline copy are kept, the item is hidden everywhere but the Trash screen, and it can be
    // restored intact. Items sit in the Trash until the user empties it or the grace period
    // ([trashRetentionDays]) elapses, at which point [purgeTrashOlderThan] erases them for good.

    /** How long a trashed item is kept before auto-purge. Kept generous so nothing is lost by surprise. */
    val trashRetentionDays: Int = 30

    /** Move an item to the Trash (reversible). */
    suspend fun trashItem(id: String) = itemDao.setTrashed(id, System.currentTimeMillis())

    /** Move several items to the Trash at once (bulk multi-select) — one UPDATE per id chunk. */
    suspend fun trashItems(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        // chunked to stay under SQLite's bound-variable limit on a large "select all".
        ids.chunked(500).forEach { itemDao.setTrashedMany(it, now) }
    }

    /** Restore an item from the Trash, intact (offline copy and all). */
    suspend fun restoreFromTrash(id: String) = itemDao.setTrashed(id, null)

    /** Restore several items from the Trash — one UPDATE per id chunk. */
    suspend fun restoreFromTrash(ids: Collection<String>) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { itemDao.setTrashedMany(it, null) }
    }

    /** Permanently erase a single item that is in the Trash (blob + index + tombstone). */
    suspend fun deleteForever(id: String) = deleteItemFully(id)

    /** Permanently erase several items from the Trash. */
    suspend fun deleteForever(ids: Collection<String>) = deleteItemsFully(ids)

    /** Empty the Trash: permanently erase everything currently in it. */
    suspend fun emptyTrash() = deleteItemsFully(itemDao.allTrashedIds())

    /** Auto-purge: permanently erase items that have been in the Trash past the grace period.
     *  The window is user-configurable; 0 means "never auto-purge — keep until emptied by hand". */
    suspend fun purgeExpiredTrash() {
        val days = coRunCatching { preferencesRepository.preferences.first().trashRetentionDays }.getOrDefault(trashRetentionDays)
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        deleteItemsFully(itemDao.trashedOlderThan(cutoff))
    }

    fun observeTrash() = itemDao.observeTrash()
    fun observeTrashCount() = itemDao.observeTrashCount()

    // -- Offline copies --------------------------------------------------------

    /** Everything readable offline (permanent saves + auto-cached bodies), for the Offline surface. */
    fun observeCached() = itemDao.observeCached()
    fun observeCachedCount() = itemDao.observeCachedCount()

    /** Remove an item's offline copy (its cached body and any downloaded images) while KEEPING the
     *  item itself — its metadata stays and it re-fetches on next open. "Delete cache", not the entry. */
    suspend fun removeOfflineCopy(id: String) {
        val item = itemDao.getItem(id)
        blobStore.deleteAllFor(id, item?.blobPath)
        itemDao.setExtracted(
            id = id, blobPath = null, excerpt = item?.excerpt, wordCount = item?.wordCount ?: 0,
            minutes = item?.readingMinutes ?: 0, leadImage = null, status = "NONE", contentSource = item?.contentSource ?: ContentSource.FEED.raw,
        )
        itemDao.setCacheStatus(id, null)
    }

    /** True when the active network is un-metered (Wi-Fi/Ethernet). Defaults to true if unknown,
     *  so an unclear network never silently blocks a save the user asked for. */
    private fun isUnmetered(): Boolean = coRunCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(true)

    /** True when an internet-capable network is active. Used to skip a whole sync pass when the
     *  device is offline — otherwise every feed throws a connect error and the log fills with one
     *  warning per feed (and it looks like every feed is broken when it's just the network).
     *  Defaults to true if the state can't be read, so an unclear network never blocks a sync. */
    private fun isOnline(): Boolean = coRunCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    private suspend fun syncSource(
        source: SourceEntity,
        now: Long,
        newItems: MutableList<com.cairn.reader.notifications.NewArticle>? = null,
        // force = true drops the conditional-GET validators (ETag / If-Modified-Since) so the server
        // must return the full current feed. Used by user-initiated refresh so "pull to refresh" and
        // "Sync now" always pull the latest, never a sticky 304 or a months-old local snapshot.
        force: Boolean = false,
    ) {
        // Watched pages: fetch, hash the text, and emit an item only when it changed.
        if (source.kind == "WATCH") {
            val body = coRunCatching { fetcher.fetch(source.feedUrl).body }
                .orLog("watch fetch ${source.feedUrl}")
            if (body == null) { sourceDao.markError(source.id, null); return }
            val hash = pageTextHash(body)
            if (hash != source.contentHash) insertWatchSnapshot(source, source.feedUrl, now, newItems)
            sourceDao.setContentHash(source.id, hash, now)
            return
        }
        // Sitemap / scraped / taught feeds are rebuilt from the site each sync (no RSS to poll).
        if (source.kind == "SITEMAP") {
            val feed = coRunCatching { siteFeedBuilder.build(source.feedUrl, source.scrapeSelector) }
                .orLog("sitemap build ${source.feedUrl}")
            if (feed == null) { sourceDao.markError(source.id, null); return }
            feed.items.forEach { insertParsed(source, it, now, newItems) }
            sourceDao.markSynced(source.id, null, null, now)
            return
        }
        val res = fetcher.fetch(
            source.feedUrl,
            etag = if (force) null else source.etag,
            lastModified = if (force) null else source.lastModified,
        )
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
        // Freshness verification: record the newest item's publish time so the UI can show "verified
        // up to date as of <lastSyncedAt>, newest post <latestItemAt>". Only advances (see the DAO).
        feed.items.mapNotNull { it.publishedAt }.maxOrNull()?.let { newest ->
            coRunCatching { sourceDao.setLatestItemAt(source.id, newest) }
        }
        // Self-heal a permanently-moved feed URL (http→https, or a moved domain the feed 301s to)
        // so future syncs skip the redirect hop and the stored URL stays correct. Guard the unique
        // feedUrl index by only claiming a URL no other source already holds.
        res.permanentUrl?.takeIf { it != source.feedUrl }?.let { moved ->
            if (sourceDao.getByFeedUrl(moved) == null) coRunCatching { sourceDao.setFeedUrl(source.id, moved) }
        }
        // Learn / refresh the WebSub hub declaration so the feed is marked real-time-aware.
        if (!feed.hubUrl.isNullOrBlank() && feed.hubUrl != source.hubUrl) {
            coRunCatching { sourceDao.setHubUrl(source.id, feed.hubUrl) }
        }
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
        val existing = itemDao.getItem(itemId)
        // A trashed item stays in the Trash until restored or purged — never let a re-sync
        // resurrect it by upserting a fresh row (which would reset trashedAt back to null).
        if (existing?.trashedAt != null) return
        val isNew = existing == null

        val rawContent = p.contentHtml ?: p.summary
        // Privacy pass: strip trackers/beacons/campaign params from the stored body (opt-out).
        val content = rawContent?.takeIf { it.isNotBlank() }?.let { html ->
            if (sanitizeEnabled()) coRunCatching { sanitizer.sanitize(html, p.link ?: source.siteUrl ?: source.feedUrl).html }.getOrDefault(html) else html
        } ?: rawContent
        val plain = content?.let { coRunCatching { Jsoup.parse(it).text() }.getOrDefault("") } ?: ""
        val words = plain.split(whitespace).count { it.isNotBlank() }
        val minutes = if (words > 0) max(1, ceil(words / 220.0).toInt()) else 0
        val blobPath = content?.takeIf { it.isNotBlank() }?.let { blobStore.writeArticle(itemId, it) }
        val excerpt = (p.summary?.let { coRunCatching { Jsoup.parse(it).text() }.getOrNull() } ?: plain)
            .trim().take(300).ifBlank { null }
        val lead = p.imageUrl ?: content?.let { firstImage(it, source.siteUrl ?: source.feedUrl) }
        // Clean the stored/display URL of tracking params, but keep the raw link for the dedup
        // guid/key so an existing item's identity never shifts.
        val rawUrl = p.link ?: source.siteUrl ?: source.feedUrl
        val displayUrl = if (stripTrackingEnabled()) com.cairn.reader.data.net.UrlCleaner.strip(rawUrl) else rawUrl

        val canonical = com.cairn.reader.data.net.UrlCanonicalizer.canonicalize(displayUrl)
        val entity = ItemEntity(
            id = itemId,
            url = displayUrl,
            canonicalUrl = canonical,
            title = p.title?.takeIf { it.isNotBlank() } ?: "(untitled)",
            author = p.author,
            siteName = source.title,
            publishedAt = p.publishedAt,
            savedAt = now,
            effectiveDate = p.publishedAt ?: now,
            dedupeKey = (canonical.takeIf { it.isNotBlank() } ?: displayUrl).lowercase(),
            simHash = com.cairn.reader.domain.dedupe.SimHash.compute(plain),
            sourceId = source.id,
            type = if (!p.audioUrl.isNullOrBlank() || source.isPodcast) ItemType.AUDIO.name else detectType(p.link, hasBody = !content.isNullOrBlank()),
            excerpt = excerpt,
            leadImage = lead,
            wordCount = words,
            readingMinutes = minutes,
            blobPath = blobPath,
            extractStatus = ExtractStatus.NONE.raw,
            contentSource = ContentSource.FEED.raw,
            guid = p.guid ?: p.link,
            enclosureUrl = p.audioUrl,
            commentsUrl = p.commentsUrl?.let { if (stripTrackingEnabled()) com.cairn.reader.data.net.UrlCleaner.strip(it) else it },
            transcriptUrl = p.transcriptUrl,
        )
        itemDao.insertItemWithState(entity, now)
        // On-device automation: run the user's rules against each genuinely-new item.
        if (isNew) coRunCatching { ruleEngine.apply(entity, source, plain) }
        itemDao.indexItem(
            ItemFtsEntity(itemId = itemId, title = p.title ?: "", author = p.author, body = plain.take(FTS_BODY_CHARS)),
        )
        // Per-feed full text on sync: fetch the whole article for new items so they're complete and
        // offline before they're ever opened. Triggered by the legacy "full text on sync" switch OR
        // an acquisition mode of EXTRACT/BOTH (which always wants the full page, not the feed summary).
        val wantsFullText = source.fullTextByDefault ||
            com.cairn.reader.data.db.AcquisitionMode.from(source.acquisitionMode).wantsFullText
        if (isNew && wantsFullText) {
            p.link?.takeIf { it.isNotBlank() }?.let { coRunCatching { extractInto(itemId, it) } }
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

    /** Whether to strip tracking params, read once (cheap in-memory DataStore lookup). */
    private suspend fun stripTrackingEnabled(): Boolean =
        coRunCatching { preferencesRepository.preferences.first().stripTrackingParams }.getOrDefault(true)

    /** Whether to sanitize article bodies (strip trackers/beacons). Privacy-first default: on. */
    private suspend fun sanitizeEnabled(): Boolean =
        coRunCatching { preferencesRepository.preferences.first().sanitizeArticles }.getOrDefault(true)

    /** Save an arbitrary URL to the library and extract a clean, offline copy. */
    /** One-time backfill of [ItemEntity.canonicalUrl] for items captured before canonicalization
     *  existed, so the Duplicates view keys uniformly (canonical for all rows, not a mix of
     *  canonical + raw url). Bounded batches; no-ops once every item is populated. Cancellation-safe. */
    suspend fun backfillCanonicalUrls() {
        while (true) {
            val batch = itemDao.itemsMissingCanonical(500)
            if (batch.isEmpty()) break
            batch.forEach { itemDao.setCanonicalUrl(it.id, com.cairn.reader.data.net.UrlCanonicalizer.canonicalize(it.url)) }
            if (batch.size < 500) break
            coroutineContext.ensureActive()
        }
    }

    suspend fun saveUrl(rawUrl: String): Result<String> {
        val normalized = normalize(rawUrl) ?: return Result.failure(IllegalArgumentException("Invalid URL"))
        val url = if (stripTrackingEnabled()) com.cairn.reader.data.net.UrlCleaner.strip(normalized) else normalized
        val now = System.currentTimeMillis()
        val itemId = deterministicId("save|$url")
        val canonical = com.cairn.reader.data.net.UrlCanonicalizer.canonicalize(url)
        itemDao.insertItemWithState(
            ItemEntity(
                id = itemId,
                url = url,
                canonicalUrl = canonical,
                title = hostOf(url),
                savedAt = now,
                effectiveDate = now,
                dedupeKey = (canonical.takeIf { it.isNotBlank() } ?: url).lowercase(),
                type = detectType(url, hasBody = false),
                extractStatus = ExtractStatus.PENDING.raw,
                contentSource = ContentSource.READABLE.raw,
            ),
            now,
        )
        itemDao.setReadLater(itemId, true, now)
        extractInto(itemId, url)
        return Result.success(itemId)
    }

    /**
     * Store one archive-crawled URL as an item under [source] (Content Engine P3). It's de-duplicated
     * against everything already stored (so an article the live feed already brought is skipped), filed
     * under the source, marked READ (a backfilled historic article isn't "new/unread"), then extracted
     * so its full text lands in the offline search index. Returns true when a new item was created.
     */
    suspend fun saveArchivedUrl(source: SourceEntity, rawUrl: String, lastmod: Long?, mirror: Boolean = false): Boolean {
        val normalized = normalize(rawUrl) ?: return false
        val url = if (stripTrackingEnabled()) com.cairn.reader.data.net.UrlCleaner.strip(normalized) else normalized
        val canonical = com.cairn.reader.data.net.UrlCanonicalizer.canonicalize(url)
        val dedupeKey = (canonical.takeIf { it.isNotBlank() } ?: url).lowercase()
        if (itemDao.existsByDedupeKey(dedupeKey)) return false          // cross-channel dedupe
        val itemId = deterministicId("archive|${source.id}|$url")
        if (syncDao.isTombstoned(itemId)) return false
        if (itemDao.getItem(itemId)?.trashedAt != null) return false
        val now = System.currentTimeMillis()
        itemDao.insertItemWithState(
            ItemEntity(
                id = itemId,
                url = url,
                canonicalUrl = canonical,
                title = hostOf(url),
                siteName = source.title,
                sourceId = source.id,
                publishedAt = lastmod,
                savedAt = now,
                effectiveDate = lastmod ?: now,
                dedupeKey = dedupeKey,
                type = detectType(url, hasBody = false),
                extractStatus = ExtractStatus.PENDING.raw,
                contentSource = ContentSource.READABLE.raw,
            ),
            now,
        )
        coRunCatching { itemDao.setRead(itemId, true, now) }
        coRunCatching { extractInto(itemId, url) }
        // Full Offline Mirror tier (Content Engine P4): a MIRROR-tier site archives the whole article —
        // text *and* its images — as a permanent, self-contained offline copy, not just a searchable
        // index entry. INDEX-tier sites keep the on-demand body extractInto already stored.
        if (mirror) coRunCatching { saveOffline(itemId) }
        return true
    }

    /** Save shared text (e.g. a forwarded newsletter) as a readable Read Later item. When the
     *  text carries a URL we prefer saving that; otherwise the text itself becomes the article. */
    suspend fun saveText(subject: String?, text: String): Result<String> {
        val clean = text.trim()
        if (clean.length < 20) return Result.failure(IllegalArgumentException("Not enough text to save"))
        val now = System.currentTimeMillis()
        val title = (subject?.trim()?.takeIf { it.isNotBlank() }
            ?: clean.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: "Saved text").take(140)
        val itemId = deterministicId("text|$title|$now")
        val html = clean.split(Regex("\\n{2,}"))
            .filter { it.isNotBlank() }
            .joinToString("") { "<p>" + it.trim().replace("\n", "<br>") + "</p>" }
        val blobPath = coRunCatching { blobStore.writeArticle(itemId, html) }.getOrNull()
        val words = clean.split(whitespace).count { it.isNotBlank() }
        itemDao.insertItemWithState(
            ItemEntity(
                id = itemId,
                url = "cairn://saved/$itemId",
                title = title,
                siteName = "Saved",
                savedAt = now,
                effectiveDate = now,
                dedupeKey = "cairn://saved/$itemId".lowercase(),
                type = ItemType.ARTICLE.name,
                excerpt = clean.take(300),
                wordCount = words,
                readingMinutes = max(1, ceil(words / 220.0).toInt()),
                blobPath = blobPath,
                extractStatus = ExtractStatus.OK.raw,
                contentSource = ContentSource.SHARED.raw,
                simHash = com.cairn.reader.domain.dedupe.SimHash.compute(clean),
            ),
            now,
        )
        itemDao.setReadLater(itemId, true, now)
        itemDao.indexItem(ItemFtsEntity(itemId = itemId, title = title, author = null, body = clean.take(FTS_BODY_CHARS)))
        return Result.success(itemId)
    }

    /** Import a PDF into the library: store the file verbatim and create a PDF-type item
     *  the reader opens with the on-device page renderer. Fully local — nothing is uploaded. */
    suspend fun importPdf(displayName: String, bytes: ByteArray): Result<String> {
        if (bytes.isEmpty()) return Result.failure(IllegalArgumentException("Empty file"))
        val now = System.currentTimeMillis()
        val title = displayName.removeSuffix(".pdf").removeSuffix(".PDF").trim().ifBlank { "Imported PDF" }
        val itemId = deterministicId("pdf|$title|$now")
        val path = coRunCatching { blobStore.writePdf(itemId, bytes) }.getOrElse {
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
                effectiveDate = now,
                dedupeKey = "file://$path".lowercase(),
                type = ItemType.PDF.name,
                excerpt = "Imported PDF",
                leadImage = thumb,
                blobPath = path,
                extractStatus = ExtractStatus.OK.raw,
                contentSource = ContentSource.PDF.raw,
                cacheStatus = CacheStatus.PERMANENT.raw,
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

    /**
     * JS-render fallback (collector P5): re-render the item's page with a headless WebView so
     * client-side-rendered content becomes real HTML, then re-run Readability on the rendered
     * DOM. For single-page-app articles where the static fetch returns an empty shell, this is
     * often the only way to get readable text. Returns true when it produced a real article.
     * Entirely on-device — the WebView fetches over the network but nothing is uploaded.
     */
    suspend fun extractWithJs(itemId: String): Boolean {
        val item = itemDao.getItem(itemId) ?: return false
        val url = item.url.takeIf { it.startsWith("http", ignoreCase = true) } ?: return false
        val rendered = webViewRenderer.render(url)
        if (rendered == null) {
            itemDao.setExtractStatus(itemId, "FAILED")
            return false
        }
        val extracted = extractor.extract(rendered.finalUrl, rendered.html)
        if (extracted == null || extracted.wordCount < 60) {
            itemDao.setExtractStatus(itemId, "FAILED")
            return false
        }
        val cleanHtml = if (sanitizeEnabled()) coRunCatching { sanitizer.sanitize(extracted.contentHtml, rendered.finalUrl).html }.getOrDefault(extracted.contentHtml) else extracted.contentHtml
        val blob = blobStore.writeArticle(itemId, cleanHtml)
        extracted.title?.let { itemDao.updateMeta(itemId, it, extracted.byline, hostOf(url)) }
        itemDao.setExtracted(
            id = itemId,
            blobPath = blob,
            excerpt = extracted.excerpt,
            wordCount = extracted.wordCount,
            minutes = extracted.readingMinutes,
            leadImage = extracted.leadImage,
            status = "OK",
            contentSource = ContentSource.READABLE.raw,
        )
        itemDao.indexItem(
            ItemFtsEntity(itemId, extracted.title ?: "", extracted.byline, extracted.plainText.take(FTS_BODY_CHARS)),
        )
        coRunCatching { itemDao.setSimHash(itemId, com.cairn.reader.domain.dedupe.SimHash.compute(extracted.plainText)) }
        if (extracted.wordCount >= 200 && itemDao.getItem(itemId)?.type == ItemType.LINK.name) {
            itemDao.setType(itemId, ItemType.ARTICLE.name)
        }
        return true
    }

    private suspend fun extractInto(itemId: String, url: String) {
        val res = coRunCatching { fetcher.fetch(url) }.getOrNull()
        val extracted = res?.body?.let { extractor.extract(res.finalUrl, it) }
        if (extracted == null) {
            // Keep whatever content we already have (e.g. the feed body); just record the failure.
            itemDao.setExtractStatus(itemId, "FAILED")
            return
        }
        val cleanHtml = if (sanitizeEnabled()) coRunCatching { sanitizer.sanitize(extracted.contentHtml, res.finalUrl).html }.getOrDefault(extracted.contentHtml) else extracted.contentHtml
        val blob = blobStore.writeArticle(itemId, cleanHtml)
        extracted.title?.let { itemDao.updateMeta(itemId, it, extracted.byline, hostOf(url)) }
        itemDao.setExtracted(
            id = itemId,
            blobPath = blob,
            excerpt = extracted.excerpt,
            wordCount = extracted.wordCount,
            minutes = extracted.readingMinutes,
            leadImage = extracted.leadImage,
            status = "OK",
            contentSource = ContentSource.READABLE.raw,
        )
        itemDao.indexItem(
            ItemFtsEntity(itemId, extracted.title ?: "", extracted.byline, extracted.plainText.take(FTS_BODY_CHARS)),
        )
        coRunCatching { itemDao.setSimHash(itemId, com.cairn.reader.domain.dedupe.SimHash.compute(extracted.plainText)) }
        // A saved bare link that turned out to have a real article body is promoted to ARTICLE,
        // so it filters and reads like one. Video/image classifications are left untouched.
        if (extracted.wordCount >= 200 && itemDao.getItem(itemId)?.type == ItemType.LINK.name) {
            itemDao.setType(itemId, ItemType.ARTICLE.name)
        }
    }

    /**
     * Enrich a saved YouTube [VIDEO][ItemType.VIDEO] item with real metadata — title, channel/author,
     * published date, duration and the video description as readable body — fetched through the same
     * Piped/Invidious privacy front-ends used for captions (nothing hits YouTube directly, no account
     * or tracker involved). Idempotent: a already-enriched item ([ContentSource.MEDIA]) is skipped, so
     * this is safe to call on every reader open. Returns true when it wrote anything.
     */
    suspend fun enrichVideoMetadata(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val item = itemDao.getItem(itemId) ?: return@withContext false
        if (item.type != ItemType.VIDEO.name) return@withContext false
        if (item.contentSource == ContentSource.MEDIA.raw) return@withContext false // already enriched
        val videoId = captionFetcher.youtubeVideoId(item.url) ?: return@withContext false
        val meta = coRunCatching { youtubeProxy.metadata(videoId) }.getOrNull() ?: return@withContext false

        // Description → readable HTML body: Piped already serves HTML; Invidious serves plain text.
        val descHtml: String? = meta.description?.let { d -> if (meta.descriptionIsHtml) d else plainTextToHtml(d) }
        val plain: String = descHtml?.let { coRunCatching { Jsoup.parse(it).text() }.getOrDefault("") }.orEmpty()

        meta.thumbnailUrl?.let { itemDao.setLeadImage(itemId, it) }
        itemDao.updateVideoMeta(
            id = itemId,
            title = meta.title,
            author = meta.uploader,
            publishedAt = meta.publishedAtMs,
            durationSeconds = meta.durationSeconds,
        )
        if (!descHtml.isNullOrBlank()) {
            val blob = coRunCatching { blobStore.writeArticle(itemId, descHtml) }.getOrNull()
            itemDao.setExtracted(
                id = itemId,
                blobPath = blob,
                excerpt = plain.take(300).ifBlank { null },
                wordCount = if (plain.isBlank()) 0 else plain.trim().split(whitespace).size,
                minutes = 0,
                leadImage = meta.thumbnailUrl,
                status = ExtractStatus.OK.raw,
                contentSource = ContentSource.MEDIA.raw,
            )
            coRunCatching {
                itemDao.indexItem(ItemFtsEntity(itemId, meta.title ?: item.title, meta.uploader, plain.take(FTS_BODY_CHARS)))
            }
        } else {
            // No description available, but we still learned title/channel/date/duration — mark the item
            // enriched so a re-open doesn't refetch, without clobbering any body it already had.
            itemDao.setContentSource(itemId, ContentSource.MEDIA.raw)
        }
        AppLog.diag("yt-meta: enriched $itemId dur=${meta.durationSeconds} pub=${meta.publishedAtMs}")
        true
    }

    /** Turn a plain-text video description (Invidious) into simple, safe paragraph HTML: blank lines
     *  split paragraphs, single newlines become <br>, and HTML metacharacters are escaped. */
    private fun plainTextToHtml(text: String): String =
        text.replace("\r\n", "\n").split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("") { p -> "<p>" + escapeHtml(p).replace("\n", "<br>") + "</p>" }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Paywall recovery (Content Engine P5): fetch a public-archive snapshot of the item's URL —
     * archive.today first, then the Wayback Machine — run readability on it, and if that yields a
     * real article body, store it as the item's offline body (marked [ContentSource.ARCHIVE]). This
     * is how a paywalled or blocked article becomes readable and searchable. Best-effort and entirely
     * on-device beyond the two archive fetches; returns true only when a substantial body was saved,
     * so the caller can fall back to simply opening the snapshot in the browser.
     */
    suspend fun saveFromArchive(itemId: String): Boolean {
        val item = itemDao.getItem(itemId) ?: return false
        val target = item.url.takeIf { it.startsWith("http", ignoreCase = true) } ?: return false
        val candidates = listOf(
            com.cairn.reader.domain.archive.ArchiveResolver.archiveTodayNewest(target),
            com.cairn.reader.domain.archive.ArchiveResolver.waybackNewest(target),
        )
        for (snapshot in candidates) {
            coroutineContext.ensureActive()
            val res = coRunCatching { fetcher.fetch(snapshot) }.getOrNull() ?: continue
            val extracted = res.body?.let { coRunCatching { extractor.extract(res.finalUrl, it) }.getOrNull() } ?: continue
            // Require a genuine article — archives return a listing/placeholder page when they have no
            // capture, which extracts to almost nothing.
            if (extracted.wordCount < com.cairn.reader.domain.archive.ArchiveResolver.THIN_WORD_COUNT) continue
            val cleanHtml = if (sanitizeEnabled()) {
                coRunCatching { sanitizer.sanitize(extracted.contentHtml, res.finalUrl).html }.getOrDefault(extracted.contentHtml)
            } else {
                extracted.contentHtml
            }
            val blob = blobStore.writeArticle(itemId, cleanHtml)
            extracted.title?.let { itemDao.updateMeta(itemId, it, extracted.byline, hostOf(target)) }
            itemDao.setExtracted(
                id = itemId,
                blobPath = blob,
                excerpt = extracted.excerpt,
                wordCount = extracted.wordCount,
                minutes = extracted.readingMinutes,
                leadImage = extracted.leadImage,
                status = "OK",
                contentSource = ContentSource.ARCHIVE.raw,
            )
            itemDao.indexItem(
                ItemFtsEntity(itemId, extracted.title ?: item.title, extracted.byline, extracted.plainText.take(FTS_BODY_CHARS)),
            )
            coRunCatching { itemDao.setSimHash(itemId, com.cairn.reader.domain.dedupe.SimHash.compute(extracted.plainText)) }
            if (extracted.wordCount >= 200 && itemDao.getItem(itemId)?.type == ItemType.LINK.name) {
                itemDao.setType(itemId, ItemType.ARTICLE.name)
            }
            return true
        }
        return false
    }

    /**
     * Broken-link watchdog: HEAD/GET a batch of saved items' URLs and record whether the link still
     * resolves (linkStatus OK / BROKEN). A 4xx/5xx or a network failure that is not a timeout marks
     * it broken; a permanent offline copy means the article is still readable regardless. Runs a few
     * per sync and can be triggered manually. Returns how many were newly marked broken.
     */
    suspend fun checkLinks(limit: Int = 40): Int {
        var broken = 0
        itemDao.itemsToLinkCheck(limit).forEach { row ->
            val status = coRunCatching {
                val res = fetcher.fetch(row.url)
                if (res.isSuccess) "OK" else "BROKEN"
            }.getOrElse { e ->
                // Timeouts / transient connectivity aren't proof of rot; leave those unmarked.
                if (e is java.net.UnknownHostException || e is java.io.FileNotFoundException) "BROKEN" else return@forEach
            }
            itemDao.setLinkStatus(row.id, status, System.currentTimeMillis())
            if (status == "BROKEN") broken++
        }
        return broken
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
        if (item.extractStatus != ExtractStatus.OK.raw || item.blobPath.isNullOrBlank()) {
            coRunCatching { extractInto(itemId, item.url) }
        }
        val fresh = itemDao.getItem(itemId) ?: return Result.failure(IllegalStateException("Item not found"))
        val html = blobStore.readArticle(fresh.blobPath)
            ?: return Result.failure(IllegalStateException("No article content to save"))
        val doc = coRunCatching { Jsoup.parse(html, fresh.url) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Couldn't read the article"))

        // Honour the offline-image policy: images are optional, and may be restricted to Wi-Fi.
        val prefs = coRunCatching { preferencesRepository.preferences.first() }.getOrNull()
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
            val local = coRunCatching { blobStore.writeImage(itemId, index++, bytes, imageExtension(contentType, remote)) }
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
        itemDao.setCacheStatus(itemId, CacheStatus.PERMANENT.raw)
        return Result.success(cached)
    }

    /**
     * Link-rot heal: when a saved link no longer resolves, look it up in the Internet Archive's
     * Wayback Machine (a public availability API, no account) and, if a snapshot exists, extract a
     * readable copy from the archived page so the article survives the original going dark. Marks the
     * item OK on success. Returns true when a copy was recovered.
     */
    suspend fun healLink(itemId: String): Boolean {
        val item = itemDao.getItem(itemId) ?: return false
        val target = item.canonicalUrl ?: item.url
        if (!target.startsWith("http", ignoreCase = true)) return false
        val api = "https://archive.org/wayback/available?url=" + java.net.URLEncoder.encode(target, "UTF-8")
        val res = coRunCatching { fetcher.fetch(api) }.getOrNull() ?: return false
        val body = res.body ?: return false
        val snapshotUrl = coRunCatching {
            org.json.JSONObject(body).optJSONObject("archived_snapshots")
                ?.optJSONObject("closest")?.takeIf { it.optBoolean("available") }
                ?.optString("url")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: return false
        // Prefer the raw archived capture (id_ suffix) so Readability sees the original page, not
        // the Wayback chrome.
        val rawSnapshot = snapshotUrl.replaceFirst(Regex("/web/(\\d+)/"), "/web/$1id_/")
        coRunCatching { extractInto(itemId, rawSnapshot) }
        val healed = itemDao.getItem(itemId)?.extractStatus == ExtractStatus.OK.raw
        if (healed) itemDao.setLinkStatus(itemId, "OK", System.currentTimeMillis())
        return healed
    }

    /** Try to heal every broken saved link from the Wayback Machine. Returns how many were recovered. */
    suspend fun healBrokenLinks(limit: Int = 40): Int {
        var healed = 0
        itemDao.brokenItemIds(limit).forEach { id -> if (coRunCatching { healLink(id) }.getOrDefault(false)) healed++ }
        return healed
    }

    /**
     * Commute Mode: pull the next [limit] things you're likely to read (Read Later first, then
     * unread, newest first) fully onto the device — text and, per the offline-image policy,
     * images — so they're readable with no signal. Returns how many were newly saved offline.
     */
    suspend fun prepareOfflinePack(limit: Int = 25): Int {
        var saved = 0
        itemDao.offlinePackCandidates(limit).forEach { id ->
            if (coRunCatching { saveOffline(id) }.getOrNull()?.isSuccess == true) saved++
        }
        return saved
    }

    /** Render a PDF's first page to a small cover image so it has a real thumbnail in lists. */
    private suspend fun renderPdfThumbnail(itemId: String, pdfPath: String): String? = coRunCatching {
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
            videoHosts.any { host == it || host.endsWith(".$it") } || videoExt.any { u.endsWith(it) } -> ItemType.VIDEO.name
            imageExt.any { u.endsWith(it) } -> ItemType.IMAGE.name
            hasBody -> ItemType.ARTICLE.name
            else -> ItemType.LINK.name
        }
    }

    private fun firstImage(html: String, baseUrl: String): String? = coRunCatching {
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
