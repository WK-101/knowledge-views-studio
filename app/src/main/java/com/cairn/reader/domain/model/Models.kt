package com.cairn.reader.domain.model

/** What kind of thing an item is. Stored as its [name] in the database. */
enum class ItemType { ARTICLE, LINK, NEWSLETTER, PDF, VIDEO }

/** Progress of on-device readable-content extraction for an item. */
enum class ExtractStatus { NONE, PENDING, OK, FAILED }

/** Which body a reader should show. */
enum class ContentSource { FEED, READABLE, ORIGINAL }

/** How a source produces items. */
enum class SourceKind { RSS, JSON, WEB_WATCH, EMAIL, MANUAL }

/** Where an item opens when tapped. */
enum class OpenIn { READER, IN_APP_BROWSER, CUSTOM_TAB, EXTERNAL }

/** A feed / newsletter / saved-page source. */
data class Source(
    val id: String,
    val kind: SourceKind,
    val feedUrl: String,
    val siteUrl: String?,
    val title: String,
    val folder: String?,
    val openIn: OpenIn,
    val fullTextByDefault: Boolean,
    val notify: Boolean,
    val faviconUrl: String?,
)

/** Mutable per-item state, kept separate so remote sync can update it independently. */
data class ItemState(
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    val isReadLater: Boolean = false,
    val readProgress: Float = 0f,
    val lastReadAt: Long? = null,
    val updatedAt: Long = 0L,
)

/** A single readable thing — the atom the whole app is built around. */
data class Item(
    val id: String,
    val url: String,
    val title: String,
    val author: String? = null,
    val siteName: String? = null,
    val publishedAt: Long? = null,
    val savedAt: Long,
    val sourceId: String? = null,
    val type: ItemType = ItemType.ARTICLE,
    val excerpt: String? = null,
    val leadImage: String? = null,
    val wordCount: Int = 0,
    val readingMinutes: Int = 0,
    val extractStatus: ExtractStatus = ExtractStatus.NONE,
    val state: ItemState = ItemState(),
)

data class Tag(val id: String, val name: String, val color: Int?)

/** A highlight/annotation stored as a portable CSS-selector range. */
data class Highlight(
    val id: String,
    val itemId: String,
    val quote: String,
    val note: String?,
    val color: Int,
    val startSelector: String?,
    val startOffset: Int,
    val endSelector: String?,
    val endOffset: Int,
    val createdAt: Long,
)
