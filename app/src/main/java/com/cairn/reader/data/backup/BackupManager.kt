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
import com.cairn.reader.data.blob.BlobStore
import com.cairn.reader.data.prefs.PreferencesRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A full backup of the library — feeds (with all sync/per-feed settings), items (including
 * read/save/trash state), tags, collections, highlights, and every app setting — so the user
 * owns their data and can move it between devices. Two flavours:
 *
 *  - **Data backup** (`export`/`import`): a human-readable JSON document. Cached article bodies
 *    are excluded and simply re-fetch on next open.
 *  - **Full archive** (`exportArchive`/`importArchive`): a `.zip` bundling that same JSON plus
 *    every offline article copy, cached image, and imported PDF — a complete, self-contained
 *    snapshot so nothing is lost, readable fully offline the moment it's restored.
 *
 * Entirely local-first; no server is ever involved.
 */
@Singleton
class BackupManager @Inject constructor(
    private val sourceDao: SourceDao,
    private val itemDao: ItemDao,
    private val tagDao: TagDao,
    private val collectionDao: CollectionDao,
    private val highlightDao: HighlightDao,
    private val blobStore: BlobStore,
    private val preferencesRepository: PreferencesRepository,
) {
    suspend fun export(): String {
        val root = JSONObject()
        root.put("version", 3)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("filesRoot", blobStore.filesRoot().absolutePath)
        root.put("sources", JSONArray().apply { sourceDao.getAll().forEach { put(it.toJson()) } })
        root.put("items", JSONArray().apply { itemDao.allItems().forEach { put(it.toJson()) } })
        root.put("states", JSONArray().apply { itemDao.allStates().forEach { put(it.toJson()) } })
        root.put("tags", JSONArray().apply { tagDao.allTags().forEach { put(it.toJson()) } })
        root.put("itemTags", JSONArray().apply { tagDao.allCrossRefs().forEach { put(it.toJson()) } })
        root.put("collections", JSONArray().apply { collectionDao.all().forEach { put(it.toJson()) } })
        root.put("highlights", JSONArray().apply { highlightDao.all().forEach { put(it.toJson()) } })
        root.put("settings", preferencesRepository.exportSettings())
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
        root.optJSONObject("settings")?.let { runCatching { preferencesRepository.importSettings(it) } }
        return "Restored $restored feeds & items, plus tags, collections, highlights and settings."
    }

    // -- Full archive (.zip: data + offline copies) ---------------------------

    /** Write a complete `.zip` archive: the data JSON plus every cached article body, image and
     *  imported PDF, so the whole library — offline copies included — travels in one file. */
    suspend fun exportArchive(out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(export().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            blobStore.archiveDirs().forEach { (name, d) ->
                d.listFiles()?.filter { it.isFile }?.forEach { f ->
                    zip.putNextEntry(ZipEntry("$name/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Restore a full `.zip` archive: unpack every offline copy back to disk, restore the data,
     *  and rewire on-disk paths to this install so cached articles and images resolve. */
    suspend fun importArchive(input: InputStream): String {
        var backupJson: String? = null
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    if (name == "backup.json") {
                        backupJson = zip.readBytes().toString(Charsets.UTF_8)
                    } else {
                        blobStore.resolveArchivePath(name)?.let { target ->
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { zip.copyTo(it) }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val json = backupJson ?: return "That archive is missing its backup data."
        val summary = import(json)
        // Rewire absolute paths from the source device to this install.
        val oldBase = runCatching { JSONObject(json).optString("filesRoot", "") }.getOrDefault("")
        val newBase = blobStore.filesRoot().absolutePath
        blobStore.rewriteArticleBase(oldBase, newBase)
        relinkBlobs(oldBase, newBase)
        return "$summary Offline copies restored."
    }

    /** After an archive unpack, point each item at its restored blob and fix image URIs. */
    private suspend fun relinkBlobs(oldBase: String, newBase: String) {
        itemDao.allItems().forEach { item ->
            val article = blobStore.resolveArchivePath("articles/${item.id}.html.gz")
            val pdf = blobStore.resolveArchivePath("pdfs/${item.id}.pdf")
            val blob = when {
                pdf?.exists() == true -> pdf.absolutePath
                article?.exists() == true -> article.absolutePath
                else -> null
            }
            if (blob != null && blob != item.blobPath) itemDao.setBlobPath(item.id, blob)
            val lead = item.leadImage
            if (lead != null && oldBase.isNotBlank() && oldBase != newBase && lead.contains(oldBase)) {
                itemDao.setLeadImage(item.id, lead.replace(oldBase, newBase))
            }
        }
    }

    // -- Serialization ---------------------------------------------------------

    private fun SourceEntity.toJson() = JSONObject().apply {
        put("id", id); put("kind", kind); put("feedUrl", feedUrl); putOpt("siteUrl", siteUrl)
        put("title", title); putOpt("folder", folder); put("openIn", openIn)
        put("fullTextByDefault", fullTextByDefault); put("notify", notify); put("isPodcast", isPodcast); putOpt("faviconUrl", faviconUrl)
        putOpt("etag", etag); putOpt("lastModified", lastModified); putOpt("retryAfter", retryAfter)
        put("consecutiveErrors", consecutiveErrors); putOpt("remoteId", remoteId); putOpt("lastSyncedAt", lastSyncedAt)
        put("sortOrder", sortOrder); putOpt("maxItems", maxItems); putOpt("contentHash", contentHash); putOpt("scrapeSelector", scrapeSelector)
    }

    private fun JSONObject.toSource() = SourceEntity(
        id = getString("id"), kind = optString("kind", "RSS"), feedUrl = getString("feedUrl"),
        siteUrl = optStringOrNull("siteUrl"), title = optString("title", ""), folder = optStringOrNull("folder"),
        openIn = optString("openIn", "READER"), fullTextByDefault = optBoolean("fullTextByDefault"),
        notify = optBoolean("notify"), isPodcast = optBoolean("isPodcast"), faviconUrl = optStringOrNull("faviconUrl"),
        etag = optStringOrNull("etag"), lastModified = optStringOrNull("lastModified"), retryAfter = optLongOrNull("retryAfter"),
        consecutiveErrors = optInt("consecutiveErrors"), remoteId = optStringOrNull("remoteId"), lastSyncedAt = optLongOrNull("lastSyncedAt"),
        sortOrder = optInt("sortOrder"),
        maxItems = if (has("maxItems") && !isNull("maxItems")) getInt("maxItems") else null,
        contentHash = optStringOrNull("contentHash"), scrapeSelector = optStringOrNull("scrapeSelector"),
    )

    private fun ItemEntity.toJson() = JSONObject().apply {
        put("id", id); put("url", url); putOpt("canonicalUrl", canonicalUrl); put("title", title)
        putOpt("author", author); putOpt("siteName", siteName); putOpt("publishedAt", publishedAt); put("savedAt", savedAt)
        putOpt("sourceId", sourceId); put("type", type); putOpt("excerpt", excerpt); putOpt("leadImage", leadImage)
        put("wordCount", wordCount); put("readingMinutes", readingMinutes); putOpt("lang", lang)
        put("extractStatus", extractStatus); put("contentSource", contentSource); putOpt("guid", guid)
        putOpt("collectionId", collectionId); putOpt("domain", domain); putOpt("cacheStatus", cacheStatus)
        putOpt("enclosureUrl", enclosureUrl); putOpt("trashedAt", trashedAt)
    }

    private fun JSONObject.toItem() = ItemEntity(
        id = getString("id"), url = optString("url", ""), canonicalUrl = optStringOrNull("canonicalUrl"),
        title = optString("title", "(untitled)"), author = optStringOrNull("author"), siteName = optStringOrNull("siteName"),
        publishedAt = optLongOrNull("publishedAt"), savedAt = optLong("savedAt"), sourceId = optStringOrNull("sourceId"),
        type = optString("type", "ARTICLE"), excerpt = optStringOrNull("excerpt"), leadImage = optStringOrNull("leadImage"),
        wordCount = optInt("wordCount"), readingMinutes = optInt("readingMinutes"), lang = optStringOrNull("lang"),
        blobPath = null, extractStatus = optString("extractStatus", "NONE"), contentSource = optString("contentSource", "FEED"),
        guid = optStringOrNull("guid"), collectionId = optStringOrNull("collectionId"), domain = optStringOrNull("domain"),
        cacheStatus = optStringOrNull("cacheStatus"), enclosureUrl = optStringOrNull("enclosureUrl"), trashedAt = optLongOrNull("trashedAt"),
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
