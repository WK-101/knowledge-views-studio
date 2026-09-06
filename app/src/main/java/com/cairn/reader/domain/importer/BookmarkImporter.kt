package com.cairn.reader.domain.importer

import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemEntity
import com.cairn.reader.data.db.ItemFtsEntity
import com.cairn.reader.data.net.UrlCleaner
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.repo.CollectionRepository
import com.cairn.reader.data.repo.TagRepository
import kotlinx.coroutines.flow.first
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton
import com.cairn.reader.data.db.ContentSource
import com.cairn.reader.data.db.ExtractStatus

/**
 * Imports a reading list exported from Pocket, Instapaper, Raindrop or any Netscape-bookmark HTML —
 * entirely on-device, no account, no API keys. Each saved link becomes a Read Later item (with its
 * tags and, where present, its folder as a collection); full text is fetched lazily when opened, so
 * importing hundreds of links is instant and offline-friendly.
 *
 * Supported shapes, auto-detected from the file:
 *  - **HTML** (Pocket `ril_export.html`, Instapaper export, browser bookmarks): `<a href tags time_add…>`.
 *  - **CSV** (Pocket CSV, Instapaper CSV, Raindrop CSV): a header row naming url/title/tags/folder columns.
 */
@Singleton
class BookmarkImporter @Inject constructor(
    private val itemDao: ItemDao,
    private val tagRepository: TagRepository,
    private val collectionRepository: CollectionRepository,
    private val preferencesRepository: PreferencesRepository,
) {
    data class Report(val imported: Int, val skipped: Int, val source: String) {
        val message: String
            get() = when {
                imported == 0 && skipped == 0 -> "No saved links found in that file."
                imported == 0 -> "Nothing new — all $skipped were already in your library."
                else -> "Imported $imported from $source" + if (skipped > 0) " ($skipped already present)." else "."
            }
    }

    private data class Entry(val url: String, val title: String?, val tags: List<String>, val folder: String?, val savedAt: Long?)

    suspend fun import(fileName: String?, content: String): Report {
        val looksHtml = content.trimStart().startsWith("<") || content.contains("<a ", ignoreCase = true)
        val (entries, source) = when {
            fileName?.endsWith(".csv", true) == true -> parseCsv(content) to "CSV"
            looksHtml -> parseHtml(content) to "HTML export"
            content.contains(",") -> parseCsv(content) to "CSV"
            else -> parseHtml(content) to "HTML export"
        }
        return ingest(entries, source)
    }

    private suspend fun ingest(entries: List<Entry>, source: String): Report {
        var imported = 0
        var skipped = 0
        // Cache folder→collectionId so a run only creates each collection once.
        val folderToCollection = HashMap<String, String>()
        val strip = runCatching { preferencesRepository.preferences.first().stripTrackingParams }.getOrDefault(true)

        for (e in entries) {
            val normalized = normalize(e.url) ?: continue
            val url = if (strip) UrlCleaner.strip(normalized) else normalized
            val itemId = deterministicId("save|$url")
            if (itemDao.getItem(itemId) != null) { skipped++; continue }
            val now = e.savedAt ?: System.currentTimeMillis()
            itemDao.insertItemWithState(
                ItemEntity(
                    id = itemId,
                    url = url,
                    title = e.title?.takeIf { it.isNotBlank() } ?: hostOf(url),
                    savedAt = now,
                    type = "LINK",
                    extractStatus = ExtractStatus.PENDING.raw,
                    contentSource = ContentSource.READABLE.raw,
                ),
                now,
            )
            itemDao.setReadLater(itemId, true, now)
            itemDao.indexItem(ItemFtsEntity(itemId = itemId, title = e.title.orEmpty(), author = null, body = null))
            e.tags.filter { it.isNotBlank() }.forEach { runCatching { tagRepository.addToItem(itemId, it) } }
            e.folder?.takeIf { it.isNotBlank() }?.let { folder ->
                val colId = folderToCollection.getOrPut(folder) {
                    runCatching { collectionRepository.create(folder) }.getOrDefault("")
                }
                if (colId.isNotBlank()) runCatching { collectionRepository.setInCollection(itemId, colId, true) }
            }
            imported++
        }
        return Report(imported, skipped, source)
    }

    // -- HTML (Pocket / Instapaper / bookmarks) --------------------------------

    private fun parseHtml(html: String): List<Entry> {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
        return doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("href").trim().ifBlank { return@mapNotNull null }
            if (!href.startsWith("http", true)) return@mapNotNull null
            val tags = a.attr("tags").split(',').map { it.trim() }.filter { it.isNotBlank() }
            val added = a.attr("time_added").toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
            Entry(url = href, title = a.text().ifBlank { null }, tags = tags, folder = null, savedAt = added)
        }
    }

    // -- CSV (Pocket / Instapaper / Raindrop) ----------------------------------

    private fun parseCsv(csv: String): List<Entry> {
        val rows = parseCsvRows(csv)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val urlIdx = col("url", "link") ?: return emptyList()
        val titleIdx = col("title", "name")
        val tagsIdx = col("tags", "tag")
        val folderIdx = col("folder", "collection")
        val timeIdx = col("time_added", "created", "timestamp", "date")

        return rows.drop(1).mapNotNull { r ->
            val url = r.getOrNull(urlIdx)?.trim()?.takeIf { it.startsWith("http", true) } ?: return@mapNotNull null
            val tags = tagsIdx?.let { r.getOrNull(it) }?.split(Regex("[|,]"))?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
            val time = timeIdx?.let { r.getOrNull(it) }?.trim()?.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
            Entry(
                url = url,
                title = titleIdx?.let { r.getOrNull(it) }?.trim()?.takeIf { it.isNotBlank() },
                tags = tags,
                folder = folderIdx?.let { r.getOrNull(it) }?.trim()?.takeIf { it.isNotBlank() && !it.equals("Unsorted", true) },
                savedAt = time,
            )
        }
    }

    /** Minimal RFC-4180 CSV reader: handles quoted fields, escaped quotes and embedded newlines. */
    private fun parseCsvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { row.add(field.toString()); field = StringBuilder() }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString()); field = StringBuilder()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); if (row.any { it.isNotBlank() }) rows.add(row) }
        return rows
    }

    // -- helpers ----------------------------------------------------------------

    private fun normalize(raw: String): String? {
        val u = raw.trim()
        if (u.isBlank()) return null
        return if (u.startsWith("http", true)) u else "https://$u"
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

    /** Must match FeedRepository.deterministicId so an imported link dedups against a saved one. */
    private fun deterministicId(seed: String): String =
        java.util.UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
}
