package com.cairn.reader.data.db

/**
 * The Content Engine's per-source knobs. Each is stored as its String name on [SourceEntity] (the
 * schema stays converter-free) and mapped through these enums at the repository boundary.
 */

/** How a source acquires content. */
enum class AcquisitionMode(val raw: String) {
    /** Use the subscribed feed as-is (RSS/Atom/JSON Feed, or a synthesized site feed). */
    FEED("FEED"),
    /** Fetch each item's full article text straight from the page — and, with a deep archive crawl,
     *  harvest the site directly — instead of trusting the feed's summary. */
    EXTRACT("EXTRACT"),
    /** Both: take the feed AND extract/crawl the site, merged and de-duplicated. */
    BOTH("BOTH");

    /** True when this mode wants every item's full page fetched (not just the feed summary). */
    val wantsFullText: Boolean get() = this != FEED

    companion object {
        fun from(raw: String?): AcquisitionMode = entries.firstOrNull { it.raw == raw } ?: FEED
    }
}

/** How far back a source is fetched. Behaviour is driven by the deep-archive crawler; a fresh source
 *  defaults to LIVE (the recent window that RSS gives). */
enum class DepthMode(val raw: String) {
    /** Only the recent window the feed/index exposes. */
    LIVE("LIVE"),
    /** Backfill the site's entire published history once, then stop. */
    ARCHIVE("ARCHIVE"),
    /** Keep syncing the live feed AND backfill the full archive. */
    BOTH("BOTH");

    /** True when a one-time whole-archive backfill should run for this source. */
    val wantsArchive: Boolean get() = this != LIVE

    companion object {
        fun from(raw: String?): DepthMode = entries.firstOrNull { it.raw == raw } ?: LIVE
    }
}

/** How much of an archived article is stored offline. */
enum class OfflineTier(val raw: String) {
    /** Metadata + full extracted text (fully searchable offline); the rendered body is fetched on open. */
    INDEX("INDEX"),
    /** Full body + inlined images kept permanently offline. */
    MIRROR("MIRROR");

    companion object {
        fun from(raw: String?): OfflineTier = entries.firstOrNull { it.raw == raw } ?: INDEX
    }
}

/** Progress of a source's one-time whole-archive backfill. */
enum class BackfillState(val raw: String) {
    NONE("NONE"), PENDING("PENDING"), RUNNING("RUNNING"), PAUSED("PAUSED"), DONE("DONE");

    companion object {
        fun from(raw: String?): BackfillState = entries.firstOrNull { it.raw == raw } ?: NONE
    }
}
