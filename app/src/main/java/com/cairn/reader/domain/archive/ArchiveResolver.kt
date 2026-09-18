package com.cairn.reader.domain.archive

/**
 * Builds public-archive URLs for an article and recognises paywall / registration walls (Content
 * Engine P5). When a page is behind a paywall, a reader can't extract it — but a public archive
 * (archive.today, the Wayback Machine) very often holds a readable snapshot. Cairn offers to open, or
 * save offline, that snapshot instead.
 *
 * Pure and dependency-free (no Android, no network) so it is unit-testable off-device. The resolver
 * only *builds* URLs and *classifies* text; fetching a snapshot is the repository's job.
 */
object ArchiveResolver {

    /** The canonical archive.today alias (fastest to resolve; the service also answers on
     *  archive.today / archive.is / archive.li). */
    const val ARCHIVE_TODAY = "https://archive.ph"

    /**
     * archive.today URL that redirects to the **most recent** snapshot of [articleUrl] (or its
     * "save this page" prompt when none exists yet). archive.today expects the target URL appended
     * raw, not percent-encoded, so we only trim it.
     */
    fun archiveTodayNewest(articleUrl: String): String =
        "$ARCHIVE_TODAY/newest/${articleUrl.trim()}"

    /** archive.today listing page for [articleUrl] — every captured snapshot plus a save button. */
    fun archiveTodaySearch(articleUrl: String): String =
        "$ARCHIVE_TODAY/${articleUrl.trim()}"

    /**
     * Wayback Machine URL that redirects to the newest capture of [articleUrl]. The far-future
     * timestamp is the well-known "closest to now = latest" trick; `id_` asks for the original,
     * un-rewritten page (no Wayback toolbar/banner) so extraction sees clean HTML.
     */
    fun waybackNewest(articleUrl: String): String =
        "https://web.archive.org/web/2999id_/${articleUrl.trim()}"

    // Phrases that betray a paywall, metered wall, or registration gate. Lower-cased substring match
    // against the (short) extracted body — deliberately conservative to avoid false positives on
    // articles that merely mention subscriptions.
    private val PAYWALL_MARKERS = listOf(
        "subscribe to continue", "subscribe to read", "already a subscriber",
        "this article is for subscribers", "subscribers only", "for subscribers only",
        "create a free account", "create an account to continue", "register to continue",
        "sign in to read", "log in to continue", "to continue reading", "continue reading",
        "your subscription", "start your free trial", "become a member to",
        "this content is available to subscribers", "unlock this article",
    )

    /**
     * Heuristic: does a fetched article look like it hit a paywall or registration wall?
     *
     * True when either the body is suspiciously thin ([wordCount] below [THIN_WORD_COUNT] — a wall
     * usually leaves only a teaser) **or** a short body carries a known paywall phrase. A comfortably
     * long body is never flagged, even if it mentions subscribing, so full articles about the news
     * industry don't trip it.
     */
    fun looksPaywalled(plainText: String?, wordCount: Int): Boolean {
        if (wordCount in 1 until THIN_WORD_COUNT) return true
        if (wordCount >= LONG_WORD_COUNT) return false
        val t = plainText?.lowercase() ?: return false
        return PAYWALL_MARKERS.any { it in t }
    }

    /** Below this many words, a "successful" extraction is almost certainly just a teaser. */
    const val THIN_WORD_COUNT = 120

    /** At or above this many words, the article is substantial enough to never be called a wall. */
    private const val LONG_WORD_COUNT = 600
}
