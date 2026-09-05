package com.cairn.reader.data.backup

import com.cairn.reader.data.db.CollectionDao
import com.cairn.reader.data.db.CollectionEntity
import com.cairn.reader.data.db.HighlightDao
import com.cairn.reader.data.db.HighlightEntity
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemEntity
import com.cairn.reader.data.db.ItemStateEntity
import com.cairn.reader.data.db.ItemTagCrossRef
import com.cairn.reader.data.db.SourceDao
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.db.TagDao
import com.cairn.reader.data.db.TagEntity
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A full, human-readable JSON backup of the library — feeds, items, read/save state,
 * tags, collections and highlights — so the user owns their data and can move it between
 * devices. Cached article bodies (on disk) are intentionally excluded; they re-fetch.
 * Local-first, no server involved.
 */
@Singleton
class BackupManager @Inject constructor(
    private val sourceDao: SourceDao,
    private val itemDao: ItemDao,
    private val tagDao: TagDao,
    private val collectionDao: CollectionDao,
    private val highlightDao: HighlightDao,
) {
    suspend fun export(): String {
        val root = JSONObject()
        root.put("version", 2)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("sources", JSONArray().apply { sourceDao.getAll().forEach { put(it.toJson()) } })
        root.put("items", JSONArray().apply { itemDao.allItems().forEach { put(it.toJson()) } })
        root.put("states", JSONArray().apply { itemDao.allStates().forEach { put(it.toJson()) } })
        root.put("tags", JSONArray().apply { tagDao.allTags().forEach { put(it.toJson()) } })
        root.put("itemTags", JSONArray().apply { tagDao.allCrossRefs().forEach { put(it.toJson()) } })
        root.put("collections", JSONArray().apply { collectionDao.all().forEach { put(it.toJson()) } })
        root.put("highlights", JSONArray().apply { highlightDao.all().forEach { put(it.toJson()) } })
        return root.toString(2)
    }

    /** Restore a backup, merging into the current library. Returns a short summary. */
    suspend fun import(json: String): String {
        val root = JSONObject(json)
        var restored = 0
        root.optJSONArray("sources").forEachObject { sourceDao.upsert(it.toSource()); restored++ }
        root.optJSONArray("collections").forEachObject { collectionDao.upsert(it.toCollection()) }
        root.optJSONArray("items").forEachObject { itemDao.upsertItem(it.toItem()); restored++ }
        root.optJSONArray("states").forEachObject { itemDao.upsertState(it.toState()) }
        root.optJSONArray("tags").forEachObject { tagDao.upsert(it.toTag()) }
        root.optJSONArray("itemTags").forEachObject { tagDao.link(it.toCrossRef()) }
        root.optJSONArray("highlights").forEachObject { highlightDao.upsert(it.toHighlight()) }
        return "Restored $restored feeds & items, plus tags, collections and highlights."
    }

    // -- Serialization ---------------------------------------------------------

    private fun SourceEntity.toJson() = JSONObject().apply {
        put("id", id); put("kind", kind); put("feedUrl", feedUrl); putOpt("siteUrl", siteUrl)
        put("title", title); putOpt("folder", folder); put("openIn", openIn)
        put("fullTextByDefault", fullTextByDefault); put("notify", notify); put("isPodcast", isPodcast); putOpt("faviconUrl", faviconUrl)
        putOpt("remoteId", remoteId); put("sortOrder", sortOrder)
    }

    private fun JSONObject.toSource() = SourceEntity(
        id = getString("id"), kind = optString("kind", "RSS"), feedUrl = getString("feedUrl"),
        siteUrl = optStringOrNull("siteUrl"), title = optString("title", ""), folder = optStringOrNull("folder"),
        openIn = optString("openIn", "READER"), fullTextByDefault = optBoolean("fullTextByDefault"),
        notify = optBoolean("notify"), isPodcast = optBoolean("isPodcast"), faviconUrl = optStringOrNull("faviconUrl"),
        remoteId = optStringOrNull("remoteId"), sortOrder = optInt("sortOrder"),
    )

    private fun ItemEntity.toJson() = JSONObject().apply {
        put("id", id); put("url", url); putOpt("canonicalUrl", canonicalUrl); put("title", title)
        putOpt("author", author); putOpt("siteName", siteName); putOpt("publishedAt", publishedAt); put("savedAt", savedAt)
        putOpt("sourceId", sourceId); put("type", type); putOpt("excerpt", excerpt); putOpt("leadImage", leadImage)
        put("wordCount", wordCount); put("readingMinutes", readingMinutes); putOpt("lang", lang)
        put("extractStatus", extractStatus); put("contentSource", contentSource); putOpt("guid", guid)
        putOpt("collectionId", collectionId); putOpt("domain", domain); putOpt("cacheStatus", cacheStatus)
    }

    private fun JSONObject.toItem() = ItemEntity(
        id = getString("id"), url = optString("url", ""), canonicalUrl = optStringOrNull("canonicalUrl"),
        title = optString("title", "(untitled)"), author = optStringOrNull("author"), siteName = optStringOrNull("siteName"),
        publishedAt = optLongOrNull("publishedAt"), savedAt = optLong("savedAt"), sourceId = optStringOrNull("sourceId"),
        type = optString("type", "ARTICLE"), excerpt = optStringOrNull("excerpt"), leadImage = optStringOrNull("leadImage"),
        wordCount = optInt("wordCount"), readingMinutes = optInt("readingMinutes"), lang = optStringOrNull("lang"),
        blobPath = null, extractStatus = optString("extractStatus", "NONE"), contentSource = optString("contentSource", "FEED"),
        guid = optStringOrNull("guid"), collectionId = optStringOrNull("collectionId"), domain = optStringOrNull("domain"),
        cacheStatus = optStringOrNull("cacheStatus"),
    )

    private fun ItemStateEntity.toJson() = JSONObject().apply {
        put("itemId", itemId); put("isRead", isRead); put("isStarred", isStarred); put("isArchived", isArchived)
        put("isReadLater", isReadLater); put("readProgress", readProgress.toDouble()); putOpt("lastReadAt", lastReadAt); put("updatedAt", updatedAt)
    }

    private fun JSONObject.toState() = ItemStateEntity(
        itemId = getString("itemId"), isRead = optBoolean("isRead"), isStarred = optBoolean("isStarred"),
        isArchived = optBoolean("isArchived"), isReadLater = optBoolean("isReadLater"),
        readProgress = optDouble("readProgress", 0.0).toFloat(), lastReadAt = optLongOrNull("lastReadAt"), updatedAt = optLong("updatedAt"),
    )

    private fun TagEntity.toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("normalizedName", normalizedName); putOpt("color", color)
    }

    private fun JSONObject.toTag() = TagEntity(
        id = getString("id"), name = optString("name", ""), normalizedName = optString("normalizedName", optString("name", "").lowercase()),
        color = if (has("color") && !isNull("color")) getInt("color") else null,
    )

    private fun ItemTagCrossRef.toJson() = JSONObject().apply { put("itemId", itemId); put("tagId", tagId); put("attachedBy", attachedBy) }
    private fun JSONObject.toCrossRef() = ItemTagCrossRef(itemId = getString("itemId"), tagId = getString("tagId"), attachedBy = optString("attachedBy", "human"))

    private fun CollectionEntity.toJson() = JSONObject().apply {
        put("id", id); put("name", name); putOpt("parentId", parentId); put("kind", kind); putOpt("query", query)
        put("sortOrder", sortOrder); putOpt("icon", icon); putOpt("viewMode", viewMode)
    }

    private fun JSONObject.toCollection() = CollectionEntity(
        id = getString("id"), name = optString("name", ""), parentId = optStringOrNull("parentId"),
        kind = optString("kind", "manual"), query = optStringOrNull("query"), sortOrder = optInt("sortOrder"),
        icon = optStringOrNull("icon"), viewMode = optStringOrNull("viewMode"),
    )

    private fun HighlightEntity.toJson() = JSONObject().apply {
        put("id", id); put("itemId", itemId); put("quote", quote); putOpt("note", note); put("color", color)
        putOpt("startSelector", startSelector); put("startOffset", startOffset); putOpt("endSelector", endSelector)
        put("endOffset", endOffset); put("createdAt", createdAt)
    }

    private fun JSONObject.toHighlight() = HighlightEntity(
        id = getString("id"), itemId = getString("itemId"), quote = optString("quote", ""), note = optStringOrNull("note"),
        color = optInt("color"), startSelector = optStringOrNull("startSelector"), startOffset = optInt("startOffset"),
        endSelector = optStringOrNull("endSelector"), endOffset = optInt("endOffset"), createdAt = optLong("createdAt"),
    )
}

// -- JSON helpers -------------------------------------------------------------

private inline fun JSONArray?.forEachObject(action: (JSONObject) -> Unit) {
    if (this == null) return
    for (i in 0 until length()) optJSONObject(i)?.let(action)
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null
