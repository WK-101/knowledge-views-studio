package com.cairn.reader.data.backup

import com.cairn.reader.data.db.CollectionDao
import com.cairn.reader.data.db.CollectionEntity
import com.cairn.reader.data.db.HighlightDao
import com.cairn.reader.data.db.HighlightEntity
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemCollectionCrossRef
import com.cairn.reader.data.db.ItemEntity
import com.cairn.reader.data.db.ItemStateEntity
import com.cairn.reader.data.db.ItemTagCrossRef
import com.cairn.reader.data.db.SourceDao
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.db.TagDao
import com.cairn.reader.data.db.TagEntity
import com.cairn.reader.data.blob.BlobStore
import com.cairn.reader.data.net.WebDavClient
import com.cairn.reader.data.prefs.PreferencesRepository
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    private val webDavClient: WebDavClient,
) {
    /**
     * A spreadsheet-friendly CSV of every item — title, link, source, dates, reading time, state,
     * tags and any comments link. Portable to Pocket/Instapaper-style tools and plain spreadsheets;
     * complements the full JSON/zip backup (which alone can restore the app).
     */
    suspend fun exportCsv(): String {
        val sourceTitles = sourceDao.getAll().associate { it.id to it.title }
        val states = itemDao.allStates().associateBy { it.itemId }
        val tagNames = tagDao.allTags().associate { it.id to it.name }
        val tagsByItem = tagDao.allCrossRefs().groupBy({ it.itemId }, { tagNames[it.tagId] ?: "" })
        val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        fun ts(v: Long?): String = if (v == null || v <= 0L) "" else dateFmt.format(java.util.Date(v))

        val sb = StringBuilder()
        sb.append("Title,URL,Source,Author,Published,Saved,Reading minutes,Read,Starred,Read later,Archived,Tags,Comments URL\n")
        itemDao.allItems().sortedByDescending { it.publishedAt ?: it.savedAt }.forEach { i ->
            val s = states[i.id]
            val tags = tagsByItem[i.id]?.filter { it.isNotBlank() }?.joinToString("; ").orEmpty()
            val row = listOf(
                i.title, i.url, sourceTitles[i.sourceId] ?: i.siteName ?: "", i.author ?: "",
                ts(i.publishedAt), ts(i.savedAt), i.readingMinutes.toString(),
                yesNo(s?.isRead), yesNo(s?.isStarred), yesNo(s?.isReadLater), yesNo(s?.isArchived),
                tags, i.commentsUrl ?: "",
            )
            sb.append(row.joinToString(",") { csvCell(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun yesNo(b: Boolean?): String = if (b == true) "yes" else "no"

    /** RFC-4180 CSV escaping: quote when the value has a comma, quote or newline; double inner quotes. */
    private fun csvCell(raw: String): String {
        val v = raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        return if (v.any { it == ',' || it == '"' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

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
        root.put("itemCollections", JSONArray().apply {
            itemDao.allItemCollections().forEach { put(JSONObject().apply { put("itemId", it.itemId); put("collectionId", it.collectionId) }) }
        })
        root.put("highlights", JSONArray().apply { highlightDao.all().forEach { put(it.toJson()) } })
        root.put("settings", preferencesRepository.exportSettings())
        return root.toString(2)
    }

    /**
     * Restore a backup, merging into the current library with de-duplication.
     *
     * Restoring the same account onto a second device (or re-importing after a re-install) must not
     * pile up duplicate copies of every article. Items already present are matched by their natural
     * identity — feed guid within a source, else canonical/plain URL — even when their generated row
     * id differs across devices. A matched item's read/star/save state is merged last-write-wins by
     * timestamp, and any tags/highlights the backup attaches are re-pointed at the copy already here.
     */
    suspend fun import(json: String): String {
        val root = JSONObject(json)
        var restored = 0
        var merged = 0

        root.optJSONArray("sources").forEachObject { sourceDao.upsert(it.toSource()) }
        root.optJSONArray("collections").forEachObject { collectionDao.upsert(it.toCollection()) }

        // Build identity indexes over what's already on this device so incoming duplicates map onto
        // the existing rows instead of inserting new ones.
        val existing = itemDao.allItems()
        val knownIds = HashSet<String>(existing.size).apply { existing.forEach { add(it.id) } }
        val byGuid = HashMap<String, String>()      // "sourceId|guid" -> local id
        val byUrl = HashMap<String, String>()       // normalized url -> local id
        existing.forEach { e ->
            if (!e.guid.isNullOrBlank() && e.sourceId != null) byGuid["${e.sourceId}|${e.guid}"] = e.id
            urlKey(e.canonicalUrl ?: e.url)?.let { byUrl[it] = e.id }
        }
        // incoming id -> the local id it actually resolves to (its own, or a de-duped existing one).
        val idRemap = HashMap<String, String>()

        root.optJSONArray("items").forEachObject { obj ->
            val item = obj.toItem()
            when {
                item.id in knownIds -> { itemDao.upsertItem(item); restored++ }
                else -> {
                    val dup = (if (!item.guid.isNullOrBlank() && item.sourceId != null) byGuid["${item.sourceId}|${item.guid}"] else null)
                        ?: urlKey(item.canonicalUrl ?: item.url)?.let { byUrl[it] }
                    if (dup != null) {
                        idRemap[item.id] = dup
                        merged++
                    } else {
                        itemDao.upsertItem(item)
                        restored++
                        knownIds.add(item.id)
                        if (!item.guid.isNullOrBlank() && item.sourceId != null) byGuid["${item.sourceId}|${item.guid}"] = item.id
                        urlKey(item.canonicalUrl ?: item.url)?.let { byUrl[it] = item.id }
                    }
                }
            }
        }

        root.optJSONArray("states").forEachObject { obj ->
            val incoming = obj.toState()
            val targetId = idRemap[incoming.itemId] ?: incoming.itemId
            val state = if (targetId == incoming.itemId) incoming else incoming.copy(itemId = targetId)
            val current = itemDao.getState(targetId)
            if (current == null || state.updatedAt >= current.updatedAt) itemDao.upsertState(state)
        }

        root.optJSONArray("tags").forEachObject { tagDao.upsert(it.toTag()) }
        root.optJSONArray("itemTags").forEachObject {
            val ref = it.toCrossRef()
            val targetId = idRemap[ref.itemId] ?: ref.itemId
            tagDao.link(if (targetId == ref.itemId) ref else ref.copy(itemId = targetId))
        }
        root.optJSONArray("highlights").forEachObject {
            val h = it.toHighlight()
            val targetId = idRemap[h.itemId] ?: h.itemId
            highlightDao.upsert(if (targetId == h.itemId) h else h.copy(itemId = targetId))
        }
        // Item↔collection membership (v3.67+). Restore the explicit join rows when present; for
        // older backups that predate the join table, seed membership from each item's legacy
        // primary collectionId so nothing drops out of its collection on restore.
        val itemCollections = root.optJSONArray("itemCollections")
        if (itemCollections != null && itemCollections.length() > 0) {
            itemCollections.forEachObject {
                val rawItem = it.optString("itemId")
                val collectionId = it.optString("collectionId")
                if (rawItem.isNotBlank() && collectionId.isNotBlank()) {
                    val targetId = idRemap[rawItem] ?: rawItem
                    runCatching { itemDao.addToCollection(ItemCollectionCrossRef(targetId, collectionId)) }
                }
            }
        } else {
            root.optJSONArray("items").forEachObject { obj ->
                val collectionId = obj.optStringOrNull("collectionId") ?: return@forEachObject
                val rawItem = obj.optString("id")
                if (rawItem.isNotBlank()) {
                    val targetId = idRemap[rawItem] ?: rawItem
                    runCatching { itemDao.addToCollection(ItemCollectionCrossRef(targetId, collectionId)) }
                }
            }
        }

        root.optJSONObject("settings")?.let { runCatching { preferencesRepository.importSettings(it) } }

        val dupNote = if (merged > 0) " Merged $merged duplicates already on this device." else ""
        return "Restored $restored feeds & items, plus tags, collections, highlights and settings.$dupNote"
    }

    /** A stable identity key for an item URL: lower-cased, fragment and trailing slash stripped.
     *  Null for blank URLs so they never collide in the de-dup index. */
    private fun urlKey(url: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isBlank()) return null
        return u.substringBefore('#').trimEnd('/').lowercase()
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

    // -- WebDAV / Nextcloud mirror --------------------------------------------

    /** The configured WebDAV target, or null when the user hasn't set one up. */
    private suspend fun webDavConfig(): WebDavClient.Config? {
        val p = preferencesRepository.preferences.first()
        val url = p.webdavUrl?.trim().orEmpty()
        if (url.isBlank()) return null
        return WebDavClient.Config(url, p.webdavUser, p.webdavPass)
    }

    /** Whether a WebDAV backup target is configured. */
    suspend fun webDavConfigured(): Boolean = webDavConfig() != null

    /** Check that an (ad-hoc) WebDAV target is reachable and the credentials work. */
    suspend fun testWebDav(url: String, user: String?, pass: String?): Result<Unit> =
        webDavClient.test(WebDavClient.Config(url.trim(), user, pass))

    /** Upload a backup to the WebDAV folder — a `.zip` full archive when the user has opted to
     *  include offline copies, otherwise a data-only `.json` — then prune to the most recent few.
     *  Returns the uploaded file's name. */
    suspend fun backupToWebDav(): Result<String> {
        val cfg = webDavConfig() ?: return Result.failure(IllegalStateException("No WebDAV server configured"))
        val includeOffline = preferencesRepository.preferences.first().backupIncludeOffline
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        return runCatching {
            val name: String
            val bytes: ByteArray
            val type: String
            if (includeOffline) {
                val buf = ByteArrayOutputStream()
                exportArchive(buf)
                name = "cairn-backup-$stamp.zip"; bytes = buf.toByteArray(); type = "application/zip"
            } else {
                name = "cairn-backup-$stamp.json"; bytes = export().toByteArray(Charsets.UTF_8); type = "application/json"
            }
            webDavClient.put(cfg, name, bytes, type).getOrThrow()
            runCatching {
                webDavClient.listBackups(cfg).getOrThrow().drop(KEEP_WEBDAV)
                    .forEach { webDavClient.delete(cfg, it) }
            }
            name
        }
    }

    /** Pull and restore the most recent backup found in the WebDAV folder (merging + de-duping). */
    suspend fun restoreFromWebDav(): Result<String> {
        val cfg = webDavConfig() ?: return Result.failure(IllegalStateException("No WebDAV server configured"))
        return runCatching {
            val latest = webDavClient.listBackups(cfg).getOrThrow().firstOrNull()
                ?: error("No Cairn backups found on the server")
            val bytes = webDavClient.get(cfg, latest) { it.readBytes() }.getOrThrow()
            if (latest.endsWith(".zip")) importArchive(ByteArrayInputStream(bytes))
            else import(bytes.toString(Charsets.UTF_8))
        }
    }

    private companion object { const val KEEP_WEBDAV = 5 }

    // -- Serialization ---------------------------------------------------------

    private fun SourceEntity.toJson() = JSONObject().apply {
        put("id", id); put("kind", kind); put("feedUrl", feedUrl); putOpt("siteUrl", siteUrl)
        put("title", title); putOpt("folder", folder); put("openIn", openIn)
        put("fullTextByDefault", fullTextByDefault); put("notify", notify); put("isPodcast", isPodcast); putOpt("faviconUrl", faviconUrl)
        putOpt("etag", etag); putOpt("lastModified", lastModified); putOpt("retryAfter", retryAfter)
        put("consecutiveErrors", consecutiveErrors); putOpt("remoteId", remoteId); putOpt("lastSyncedAt", lastSyncedAt)
        put("sortOrder", sortOrder); putOpt("maxItems", maxItems); putOpt("contentHash", contentHash); putOpt("scrapeSelector", scrapeSelector)
        put("muted", muted)
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
        muted = optBoolean("muted"),
    )

    private fun ItemEntity.toJson() = JSONObject().apply {
        put("id", id); put("url", url); putOpt("canonicalUrl", canonicalUrl); put("title", title)
        putOpt("author", author); putOpt("siteName", siteName); putOpt("publishedAt", publishedAt); put("savedAt", savedAt)
        putOpt("sourceId", sourceId); put("type", type); putOpt("excerpt", excerpt); putOpt("leadImage", leadImage)
        put("wordCount", wordCount); put("readingMinutes", readingMinutes); putOpt("lang", lang)
        put("extractStatus", extractStatus); put("contentSource", contentSource); putOpt("guid", guid)
        putOpt("collectionId", collectionId); putOpt("domain", domain); putOpt("cacheStatus", cacheStatus)
        putOpt("enclosureUrl", enclosureUrl); putOpt("trashedAt", trashedAt); putOpt("commentsUrl", commentsUrl)
        putOpt("linkStatus", linkStatus); putOpt("linkCheckedAt", linkCheckedAt)
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
        commentsUrl = optStringOrNull("commentsUrl"),
        linkStatus = optStringOrNull("linkStatus"), linkCheckedAt = optLongOrNull("linkCheckedAt"),
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
