package com.cairn.reader.data.repo

import com.cairn.reader.data.db.BackfillState
import com.cairn.reader.data.db.CrawlFrontierDao
import com.cairn.reader.data.db.CrawlFrontierEntity
import com.cairn.reader.data.db.SourceDao
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.domain.crawl.RobotsCache
import com.cairn.reader.domain.feed.SiteFeedBuilder
import com.cairn.reader.util.coRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the deep-archive backfill (Content Engine P3): enumerate a site's whole published history
 * into the resumable [crawl_frontier], then drain that queue politely — respecting robots.txt and a
 * per-host delay — fetching and storing each article so it becomes permanently offline-searchable.
 * Bounded per run and resumable, so a huge site backfills across many background windows.
 */
@Singleton
class ArchiveRepository @Inject constructor(
    private val sourceDao: SourceDao,
    private val frontierDao: CrawlFrontierDao,
    private val siteFeedBuilder: SiteFeedBuilder,
    private val feedRepository: FeedRepository,
    private val preferencesRepository: PreferencesRepository,
    private val robots: RobotsCache,
) {
    /** Enumerate the archive for any source the UI has flagged PENDING, filling the frontier. */
    suspend fun enumeratePending() = withContext(Dispatchers.Default) {
        val pending = coRunCatching { sourceDao.sourcesByBackfillState(BackfillState.PENDING.raw) }.getOrDefault(emptyList())
        for (source in pending) {
            coroutineContext.ensureActive()
            coRunCatching { startBackfill(source) }
        }
    }

    /** Enumerate one source's whole archive into the frontier and mark it RUNNING. */
    private suspend fun startBackfill(source: SourceEntity) {
        val site = source.siteUrl?.takeIf { it.startsWith("http") }
            ?: source.feedUrl.takeIf { it.startsWith("http") }
            ?: run { sourceDao.setBackfillState(source.id, BackfillState.DONE.raw); return }
        sourceDao.setBackfillState(source.id, BackfillState.RUNNING.raw)
        val urls = coRunCatching { siteFeedBuilder.enumerateArchive(site) }.getOrDefault(emptyList())
        if (urls.isEmpty()) { sourceDao.setBackfillState(source.id, BackfillState.DONE.raw); return }
        val now = System.currentTimeMillis()
        coRunCatching {
            frontierDao.enqueue(urls.map { CrawlFrontierEntity(source.id, it.url, it.via, it.lastmod, "PENDING", 0, now) })
        }
        val discovered = coRunCatching { frontierDao.totalForSource(source.id) }.getOrDefault(urls.size)
        sourceDao.setBackfill(source.id, BackfillState.RUNNING.raw, null, discovered, source.backfillDone)
    }

    /**
     * Fetch and store up to [budget] frontier URLs, politely. Returns how many new items were stored.
     * Honours robots.txt (when enabled) and spaces requests per host by the greater of the configured
     * floor and the site's declared Crawl-delay.
     */
    suspend fun drain(budget: Int): Int = withContext(Dispatchers.Default) {
        val prefs = coRunCatching { preferencesRepository.preferences.first() }.getOrNull()
        val respectRobots = prefs?.crawlRespectRobots ?: true
        val delayFloor = (prefs?.crawlDelayMs ?: 1500L).coerceAtLeast(250L)
        val batch = coRunCatching { frontierDao.nextPending(budget) }.getOrDefault(emptyList())
        if (batch.isEmpty()) { finalizeDone(); return@withContext 0 }

        val lastHostFetch = HashMap<String, Long>()
        val sourceCache = HashMap<String, SourceEntity?>()
        var stored = 0
        for (row in batch) {
            coroutineContext.ensureActive()
            val source = sourceCache.getOrPut(row.sourceId) { coRunCatching { sourceDao.getById(row.sourceId) }.getOrNull() }
            if (source == null) { frontierDao.mark(row.sourceId, row.url, "FAILED"); continue }
            val host = row.url.toHttpUrlOrNull()?.host
            if (host == null) { frontierDao.mark(row.sourceId, row.url, "FAILED"); continue }
            if (respectRobots && !robots.allowed(row.url)) { frontierDao.mark(row.sourceId, row.url, "FAILED"); continue }

            // Polite per-host spacing.
            val minGap = if (respectRobots) maxOf(delayFloor, robots.crawlDelayMs(host) ?: 0L) else delayFloor
            lastHostFetch[host]?.let { last ->
                val wait = minGap - (System.currentTimeMillis() - last)
                if (wait > 0) delay(wait)
            }
            lastHostFetch[host] = System.currentTimeMillis()

            val ok = coRunCatching { feedRepository.saveArchivedUrl(source, row.url, row.lastmod) }.getOrDefault(false)
            frontierDao.mark(row.sourceId, row.url, "FETCHED")
            if (ok) { stored++; bumpDone(row.sourceId) }
        }
        finalizeDone()
        stored
    }

    private suspend fun bumpDone(sourceId: String) {
        val s = coRunCatching { sourceDao.getById(sourceId) }.getOrNull() ?: return
        sourceDao.setBackfill(sourceId, s.backfillState, s.backfillCursor, s.backfillDiscovered, s.backfillDone + 1)
    }

    /** Mark any RUNNING source with no remaining queued URLs as DONE. */
    private suspend fun finalizeDone() {
        coRunCatching { sourceDao.sourcesByBackfillState(BackfillState.RUNNING.raw) }.getOrDefault(emptyList())
            .forEach { s ->
                if (coRunCatching { frontierDao.pendingCount(s.id) }.getOrDefault(1) == 0) {
                    sourceDao.setBackfillState(s.id, BackfillState.DONE.raw)
                }
            }
    }

    suspend fun hasWork(): Boolean = withContext(Dispatchers.Default) {
        val enumerating = coRunCatching { sourceDao.sourcesByBackfillState(BackfillState.PENDING.raw).isNotEmpty() }.getOrDefault(false)
        enumerating || coRunCatching { frontierDao.totalPending() > 0 }.getOrDefault(false)
    }

    /** Stop and forget a source's backfill (clears its queue, resets state). */
    suspend fun cancelBackfill(sourceId: String) = withContext(Dispatchers.Default) {
        coRunCatching { frontierDao.clearForSource(sourceId) }
        coRunCatching { sourceDao.setBackfillState(sourceId, BackfillState.NONE.raw) }
        Unit
    }

    fun observePending(): Flow<Int> = frontierDao.observePending()
}
