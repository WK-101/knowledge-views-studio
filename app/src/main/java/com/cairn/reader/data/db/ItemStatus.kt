package com.cairn.reader.data.db

/**
 * The canonical vocabularies for the small set of status columns on [ItemEntity]. They are stored as
 * plain strings (so a schema migration is never needed to add a value, and unknown values from an
 * older/newer backup round-trip untouched), but every Kotlin comparison and assignment goes through
 * these enums instead of repeating string literals — one place defines the allowed values, and a
 * typo becomes a compile error rather than a silently-never-matching branch.
 *
 * Room `@Query` strings must keep the literal (SQL can't reference a Kotlin enum), so those still
 * spell the value out; the enum here documents what that literal means.
 */

/** How far article extraction has got for an item (`items.extractStatus`). */
enum class ExtractStatus(val raw: String) {
    /** Not extracted — only the feed's summary/content is available. */
    NONE("NONE"),
    /** Queued for a full-text extraction pass. */
    PENDING("PENDING"),
    /** Full text extracted and stored in the blob. */
    OK("OK"),
    /** Extraction was attempted and failed (network / unparseable page). */
    FAILED("FAILED");

    companion object {
        fun fromRaw(raw: String?): ExtractStatus? = raw?.let { r -> entries.firstOrNull { it.raw == r } }

        /** True when the full text has been extracted and stored. */
        fun isExtracted(raw: String?): Boolean = raw == OK.raw
    }
}

/** Whether an item has a permanent, self-contained offline copy (`items.cacheStatus`). */
enum class CacheStatus(val raw: String) {
    /** A permanent, archival copy (text + images) the user explicitly saved offline. */
    PERMANENT("PERMANENT");

    companion object {
        /** True when the item has a permanent offline copy (vs. an ordinary read-through cache/none). */
        fun isPermanent(raw: String?): Boolean = raw == PERMANENT.raw
    }
}

/** Where an item's stored body text came from (`items.contentSource`). */
enum class ContentSource(val raw: String) {
    /** The body shipped in the RSS/Atom feed itself. */
    FEED("FEED"),
    /** Extracted from the original page with the readability cleaner. */
    READABLE("READABLE"),
    /** Captured from a Share-to-Cairn / saved-URL flow. */
    SHARED("SHARED"),
    /** An imported PDF document. */
    PDF("PDF");

    companion object {
        fun fromRaw(raw: String?): ContentSource? = raw?.let { r -> entries.firstOrNull { it.raw == r } }
    }
}

/** Broken-link watchdog verdict for an item (`items.linkStatus`); null = not yet checked. */
enum class LinkStatus(val raw: String) {
    /** The link resolved fine on the last check. */
    OK("OK"),
    /** The link 4xx/5xx'd or otherwise failed to resolve — surfaced in the "Broken" smart view. */
    BROKEN("BROKEN");

    companion object {
        fun fromRaw(raw: String?): LinkStatus? = raw?.let { r -> entries.firstOrNull { it.raw == r } }

        fun isBroken(raw: String?): Boolean = raw == BROKEN.raw
    }
}
