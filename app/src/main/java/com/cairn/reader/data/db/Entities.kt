package com.cairn.reader.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities. Enum-typed concepts are stored as their String name and mapped to
 * domain enums in the repository layer, keeping the schema converter-free and stable.
 * Article bodies and images live on disk (the blob store), not in these rows.
 */

@Entity(
    tableName = "sources",
    indices = [Index(value = ["feedUrl"], unique = true)],
)
data class SourceEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val feedUrl: String,
    val siteUrl: String? = null,
    val title: String,
    val folder: String? = null,
    val openIn: String = "READER",
    val fullTextByDefault: Boolean = false,
    val notify: Boolean = false,
    val isPodcast: Boolean = false,
    val faviconUrl: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val retryAfter: Long? = null,
    val consecutiveErrors: Int = 0,
    val remoteId: String? = null,
    val lastSyncedAt: Long? = null,
    /** v20: publish time of the NEWEST item seen the last time this feed was successfully parsed.
     *  With [lastSyncedAt] it answers "verified up to date as of <checked>, newest post <this>" and
     *  drives the per-feed freshness badge. Null until the feed is fetched under v20+. */
    val latestItemAt: Long? = null,
    val sortOrder: Int = 0,
    /** Per-feed retention override: null = use the global cap, 0 = keep everything, N = keep newest N. */
    val maxItems: Int? = null,
    /** For WATCH sources: a hash of the page's last-seen text, to detect changes. */
    val contentHash: String? = null,
    /** For scraped sources taught by example: the CSS selector matching article links. */
    val scrapeSelector: String? = null,
    /** v3.64: muted feeds keep syncing but are hidden from the main Inbox / All river and the
     *  unread badge; they're still reachable by opening the feed directly. Migration 9→10 adds it. */
    val muted: Boolean = false,
    /** v3.74: WebSub (PubSubHubbub) hub declared by the feed, if any. Non-null marks a feed capable
     *  of real-time push; Cairn is serverless so it can't host a callback, but treats hub-enabled
     *  feeds as "live" and syncs them ahead of the rest. Migration 12→13 adds it. */
    val hubUrl: String? = null,
    /** v17: paused feeds are excluded from sync entirely — [FeedRepository.syncAll] skips them — but
     *  stay fully visible in feed lists/drawers/counts, and their already-fetched items remain
     *  readable. Distinct from [muted] (which keeps syncing but hides items from the Inbox).
     *  Migration 16→17 adds it. */
    val syncPaused: Boolean = false,
    // ---- v21: Content Engine (beyond RSS) ------------------------------------------------------
    /** How this source acquires content — [AcquisitionMode] name: FEED (feed as-is), EXTRACT (fetch
     *  every article's full page / crawl the site), or BOTH (feed + extract, de-duplicated). */
    val acquisitionMode: String = "FEED",
    /** How far back to fetch — [DepthMode] name: LIVE (recent window), ARCHIVE (whole history once),
     *  or BOTH. Drives the deep-archive crawler. */
    val depthMode: String = "LIVE",
    /** Offline depth for archived articles — [OfflineTier] name: INDEX (searchable text, body on open)
     *  or MIRROR (full body + images kept offline). */
    val offlineTier: String = "INDEX",
    /** One-time whole-archive backfill progress — [BackfillState] name. */
    val backfillState: String = "NONE",
    /** Opaque resume cursor for the archive backfill (page number, sitemap position, CDX offset…). */
    val backfillCursor: String? = null,
    /** How many archive URLs have been discovered / fetched so far, for the progress display. */
    val backfillDiscovered: Int = 0,
    val backfillDone: Int = 0,
)

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("sourceId"), Index("savedAt"), Index("guid"), Index("effectiveDate"), Index("dedupeKey")],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val url: String,
    val canonicalUrl: String? = null,
    val title: String,
    val author: String? = null,
    val siteName: String? = null,
    val publishedAt: Long? = null,
    val savedAt: Long,
    val sourceId: String? = null,
    val type: String = ItemType.ARTICLE.name,
    val excerpt: String? = null,
    val leadImage: String? = null,
    val wordCount: Int = 0,
    val readingMinutes: Int = 0,
    val lang: String? = null,
    val blobPath: String? = null,
    val extractStatus: String = "NONE",
    val contentSource: String = "FEED",
    val guid: String? = null,
    // v0.4 (Raindrop-style library): page domain and whether a permanent offline copy exists.
    // (The legacy single "home" collectionId column was dropped in v15 — the item_collections
    // join table is now the single source of truth for collection membership.)
    val domain: String? = null,
    val cacheStatus: String? = null,
    // v1.6: audio enclosure URL for podcast items (nullable → v2→v3 migration adds it).
    val enclosureUrl: String? = null,
    // v19: Podcasting 2.0 <podcast:transcript> URL, if the feed declared one for this episode.
    // Powers the in-app transcript screen without any speech-to-text. Migration 18→19 adds it.
    val transcriptUrl: String? = null,
    // v3.44: soft-delete. Non-null = the item is in the Trash (hidden everywhere but the Trash
    // screen, restorable, auto-purged after a grace period). Migration 7→8 adds the column.
    val trashedAt: Long? = null,
    // v3.62: a discussion/comments URL for the item (RSS <comments>, e.g. Hacker News, Reddit,
    // Lobsters), so the reader can offer "Open comments". Nullable → migration 8→9 adds the column.
    val commentsUrl: String? = null,
    // v3.67: broken-link watchdog. linkStatus is null (unchecked), "OK", or "BROKEN"; linkCheckedAt
    // is when it was last verified. Migration 10→11 adds both. Powers the "Broken" smart view.
    val linkStatus: String? = null,
    val linkCheckedAt: Long? = null,
    // v16: precomputed, indexed sort key = publishedAt ?: savedAt. The hot list queries order by this
    // directly instead of the non-sargable COALESCE(publishedAt, savedAt), so the sort uses an index.
    // Migration 15→16 adds the column, backfills it, and creates index_items_effectiveDate.
    val effectiveDate: Long = 0L,
    // v23 (Content Engine P4): 64-bit SimHash of the article's text, for near-duplicate detection
    // (cross-posted / lightly-edited reposts the exact-URL/title keys miss). 0 = not computed.
    val simHash: Long = 0L,
    // v16: precomputed, indexed duplicate-grouping key = lower(canonicalUrl ?: url). The Duplicates
    // view groups on this instead of the non-sargable LOWER(COALESCE(canonicalUrl, url)). Kept in sync
    // on write and by the canonicalUrl backfill. Migration 15→16 adds it and index_items_dedupeKey.
    val dedupeKey: String = "",
)

@Entity(
    tableName = "item_states",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // v16: the four boolean flags (isRead/isStarred/isArchived/isReadLater) are non-selective — each
    // splits the table only two ways — so their single-column indices never paid off and are dropped.
    // The list queries filter these with COALESCE(...) over a LEFT JOIN anyway, which can't use them.
)
data class ItemStateEntity(
    @PrimaryKey val itemId: String,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    val isReadLater: Boolean = false,
    val readProgress: Float = 0f,
    val lastReadAt: Long? = null,
    val updatedAt: Long = 0L,
)

@Entity(tableName = "tags", indices = [Index(value = ["normalizedName"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val color: Int? = null,
)

@Entity(
    tableName = "item_tags",
    primaryKeys = ["itemId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = ItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class ItemTagCrossRef(
    val itemId: String,
    val tagId: String,
    val attachedBy: String = "human",
)

/** v3.67: many-to-many item↔collection, so one item can be filed into several collections.
 *  Since v15 this is the single source of truth for membership (the legacy single-collection
 *  column on [ItemEntity] was dropped). */
@Entity(
    tableName = "item_collections",
    primaryKeys = ["itemId", "collectionId"],
    foreignKeys = [
        ForeignKey(entity = ItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("collectionId")],
)
data class ItemCollectionCrossRef(
    val itemId: String,
    val collectionId: String,
)

@Entity(tableName = "collections", indices = [Index("parentId")])
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val kind: String = "manual",
    val query: String? = null,
    val sortOrder: Int = 0,
    val icon: String? = null,
    val viewMode: String? = null,
)

@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(entity = ItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("itemId")],
)
data class HighlightEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val quote: String,
    val note: String? = null,
    val color: Int,
    val startSelector: String? = null,
    val startOffset: Int = 0,
    val endSelector: String? = null,
    val endOffset: Int = 0,
    val createdAt: Long,
    // v3.76: spaced-repetition (SM-2) recall state. A null srDueAt means "new / due now".
    val srDueAt: Long? = null,
    val srInterval: Int = 0,       // current interval in days
    val srEase: Int = 250,         // ease factor ×100 (SM-2 default 2.5)
    val srReps: Int = 0,           // successful reviews in a row
    val srLapses: Int = 0,         // times forgotten
    val srLastReviewedAt: Long? = null,
    // v3.98: FSRS (advanced scheduler) memory state, used when the Advanced scheduler is on.
    // srPhase: 0=NEW, 1=LEARNING, 2=REVIEW, 3=RELEARNING. Zero stability means "not yet seeded".
    val srStability: Double = 0.0,
    val srDifficulty: Double = 0.0,
    val srPhase: Int = 0,
)

/** Standalone full-text index. Populated alongside item extraction. */
@Fts4
@Entity(tableName = "item_fts")
data class ItemFtsEntity(
    val itemId: String,
    val title: String,
    val author: String? = null,
    val body: String? = null,
)

/**
 * A saved transcript for a media item (podcast episode or video). One row per item; the cues are
 * stored as a compact JSON array in [cuesJson] so the whole timed transcript travels with the row
 * (and rides the SQLCipher encryption + the normal backup). Only present when the user chose to keep
 * the whole transcript — highlighted cues are also saved separately as annotations (highlights).
 */
@Entity(
    tableName = "transcripts",
    foreignKeys = [
        ForeignKey(entity = ItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("itemId")],
)
data class TranscriptEntity(
    @PrimaryKey val itemId: String,
    /** JSON array of cues: [{"s":startMs,"e":endMs,"t":"text"}, …]. */
    val cuesJson: String,
    val language: String? = null,
    /** TranscriptSourceKind.name — provenance for the UI. */
    val source: String,
    val cueCount: Int,
    val durationMs: Long,
    val savedAt: Long,
)

@Entity(tableName = "tombstones")
data class TombstoneEntity(
    @PrimaryKey val itemId: String,
    val deletedAt: Long,
)

/**
 * v22 (Content Engine P3): the deep-archive crawl frontier — a resumable, persisted queue of article
 * URLs discovered for a source's whole published history. The archive worker drains it in small polite
 * batches across sync cycles, so a large site backfills in the background and survives restarts. One
 * row per (source, url); [state] is PENDING / FETCHED / FAILED.
 */
@Entity(
    tableName = "crawl_frontier",
    primaryKeys = ["sourceId", "url"],
    indices = [Index("sourceId"), Index("state")],
)
data class CrawlFrontierEntity(
    val sourceId: String,
    val url: String,
    /** Where the URL was discovered: "wordpress" / "sitemap" / "wayback" / "scrape". */
    val discoveredVia: String,
    /** Best-known publish/modified time (from the sitemap lastmod or CMS date), for ordering + item date. */
    val lastmod: Long? = null,
    /** PENDING (not yet fetched) / FETCHED (stored) / FAILED (gave up after retries). */
    val state: String = "PENDING",
    val attempts: Int = 0,
    val addedAt: Long,
)

@Entity(tableName = "sync_ops")
data class SyncOpEntity(
    @PrimaryKey val id: String,
    val op: String,
    val itemId: String,
    val fields: String? = null,
    val createdAt: Long,
    val selected: Boolean = false,
)

/**
 * A user-defined automation rule (Inoreader-style, but 100% on-device). When a new item arrives it
 * is matched against every enabled rule in [sortOrder]; a match applies the rule's actions.
 * Conditions and actions are stored as compact JSON arrays so the rule shape can grow without a
 * migration: conditions are `[{"field":..,"op":..,"value":..}]`, actions `[{"type":..,"value":..}]`.
 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean = true,
    /** true = all conditions must match (AND); false = any (OR). */
    val matchAll: Boolean = true,
    val conditionsJson: String,
    val actionsJson: String,
    /** Stop evaluating later rules once this one matches. */
    val stopAfter: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long,
)
