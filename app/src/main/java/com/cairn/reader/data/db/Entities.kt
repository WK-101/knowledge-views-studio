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
    indices = [Index("sourceId"), Index("savedAt"), Index("publishedAt"), Index("guid")],
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
    val type: String = "ARTICLE",
    val excerpt: String? = null,
    val leadImage: String? = null,
    val wordCount: Int = 0,
    val readingMinutes: Int = 0,
    val lang: String? = null,
    val blobPath: String? = null,
    val extractStatus: String = "NONE",
    val contentSource: String = "FEED",
    val guid: String? = null,
    // v0.4 (Raindrop-style library): a single "home" collection, page domain, and
    // whether a permanent offline copy exists. All nullable so the v1→v2 migration is
    // a plain ALTER ADD COLUMN and no existing data is touched.
    val collectionId: String? = null,
    val domain: String? = null,
    val cacheStatus: String? = null,
    // v1.6: audio enclosure URL for podcast items (nullable → v2→v3 migration adds it).
    val enclosureUrl: String? = null,
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
    indices = [Index("isRead"), Index("isStarred"), Index("isArchived"), Index("isReadLater")],
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
 *  The legacy [ItemEntity.collectionId] is kept in sync with the item's first/primary membership. */
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

@Entity(tableName = "tombstones")
data class TombstoneEntity(
    @PrimaryKey val itemId: String,
    val deletedAt: Long,
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
