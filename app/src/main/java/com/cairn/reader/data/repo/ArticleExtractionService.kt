package com.cairn.reader.data.repo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.cairn.reader.data.blob.BlobStore
import com.cairn.reader.data.db.ContentSource
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemFtsEntity
import com.cairn.reader.data.db.ItemType
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.domain.extract.ArticleExtractor
import com.cairn.reader.data.net.HttpFetcher
import com.cairn.reader.util.coRunCatching
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-demand article extraction, split out of [FeedRepository] so that the "turn a URL into a clean,
 * searchable offline body" concern lives in one focused, independently-testable place. Covers the
 * static Readability fetch ([extractInto]), the headless-WebView JS-render fallback ([extractWithJs]),
 * the auto-escalating [extractFull] the reader calls on open, and public-archive recovery
 * ([saveFromArchive]). Everything is on-device beyond the article's own fetch(es); nothing is uploaded.
 *
 * [FeedRepository] keeps thin delegating methods over this service so every existing call site is
 * unchanged; capture/sync flows call [extractInto] directly.
 */
@Singleton
class ArticleExtractionService @Inject constructor(
    private val itemDao: ItemDao,
    private val fetcher: HttpFetcher,
    private val extractor: ArticleExtractor,
    private val blobStore: BlobStore,
    private val webViewRenderer: com.cairn.reader.domain.render.WebViewRenderer,
    private val preferencesRepository: PreferencesRepository,
    private val sanitizer: com.cairn.reader.domain.privacy.ContentSanitizer,
    @ApplicationContext private val context: Context,
) {

    suspend fun extractFull(itemId: String) {
        val item = itemDao.getItem(itemId) ?: return
        val outcome = extractInto(itemId, item.url)
        // Auto-escalate when the static fetch produced nothing usable or a paywalled/thin teaser:
        // re-render the SAME article URL with a headless WebView (client-side-rendered pages and soft
        // walls often only yield their body this way). This contacts no new host — just the article's
        // own URL, which the user already chose to read — so it needs no extra egress disclosure.
        // Public-archive recovery stays user-initiated (a genuinely new third party), offered in the
        // reader when even this fails.
        val needsEscalation = outcome == null ||
            com.cairn.reader.domain.archive.ArchiveResolver.looksPaywalled(outcome.plainText, outcome.wordCount)
        if (needsEscalation && isOnline() && item.url.startsWith("http", ignoreCase = true)) {
            coRunCatching { extractWithJs(itemId) }
        }
    }

    /** What [extractInto] recovered, so the caller can decide whether to escalate. */
    data class ExtractOutcome(val plainText: String, val wordCount: Int)

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
        extracted.publishedAt?.let { itemDao.setPublishedIfMissing(itemId, it) }
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

    suspend fun extractInto(itemId: String, url: String): ExtractOutcome? {
        val res = coRunCatching { fetcher.fetch(url) }.getOrNull()
        val extracted = res?.body?.let { extractor.extract(res.finalUrl, it) }
        if (extracted == null) {
            // Keep whatever content we already have (e.g. the feed body); just record the failure.
            itemDao.setExtractStatus(itemId, "FAILED")
            return null
        }
        val cleanHtml = if (sanitizeEnabled()) coRunCatching { sanitizer.sanitize(extracted.contentHtml, res.finalUrl).html }.getOrDefault(extracted.contentHtml) else extracted.contentHtml
        val blob = blobStore.writeArticle(itemId, cleanHtml)
        extracted.title?.let { itemDao.updateMeta(itemId, it, extracted.byline, hostOf(url)) }
        // Use the page's own publish date so a saved-from-web article shows its real date, not the
        // save/sync time. Only fills when the item has no date yet (feed dates stay authoritative).
        extracted.publishedAt?.let { itemDao.setPublishedIfMissing(itemId, it) }
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
        return ExtractOutcome(extracted.plainText, extracted.wordCount)
    }

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

    private fun isOnline(): Boolean = coRunCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    /** Whether to sanitize article bodies (strip trackers/beacons). Privacy-first default: on. */
    private suspend fun sanitizeEnabled(): Boolean =
        coRunCatching { preferencesRepository.preferences.first().sanitizeArticles }.getOrDefault(true)
}
