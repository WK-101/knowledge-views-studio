package com.cairn.reader.data.repo

import com.cairn.reader.data.blob.BlobStore
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemEntity
import com.cairn.reader.data.db.ItemFtsEntity
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.db.SourceDao
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.db.SyncDao
import com.cairn.reader.data.db.SyncOpEntity
import kotlinx.coroutines.flow.Flow
import org.jsoup.Jsoup
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's read/write gateway for items. Mutations update local state immediately and
 * append a [SyncOpEntity] to the outbox, so an optional remote backend can reconcile
 * later without changing any calling code (the design proven by Capy/Wallabag).
 */
/** Everything the reader screen needs for one item, including its cached HTML body. */
data class ReaderData(
    val id: String,
    val url: String,
    val title: String,
    val author: String?,
    val siteName: String?,
    val publishedAt: Long?,
    val readingMinutes: Int,
    val leadImage: String?,
    val contentSource: String,
    val extractStatus: String,
    val isStarred: Boolean,
    val isReadLater: Boolean,
    val collectionId: String?,
    val enclosureUrl: String?,
    val isArchived: Boolean,
    val cacheStatus: String?,
    val type: String,
    /** On-disk path of an imported PDF (only for PDF-type items); null otherwise. */
    val pdfPath: String?,
    val html: String?,
)

@Singleton
class ItemRepository @Inject constructor(
    private val itemDao: ItemDao,
    private val sourceDao: SourceDao,
    private val syncDao: SyncDao,
    private val blobStore: BlobStore,
) {
    private val clock: () -> Long = { System.currentTimeMillis() }

    suspend fun reader(id: String): ReaderData? {
        val e = itemDao.getItem(id) ?: return null
        val state = itemDao.getState(id)
        return ReaderData(
            id = e.id,
            url = e.url,
            title = e.title,
            author = e.author,
            siteName = e.siteName,
            publishedAt = e.publishedAt,
            readingMinutes = e.readingMinutes,
            leadImage = e.leadImage,
            contentSource = e.contentSource,
            extractStatus = e.extractStatus,
            isStarred = state?.isStarred == true,
            isReadLater = state?.isReadLater == true,
            collectionId = e.collectionId,
            enclosureUrl = e.enclosureUrl,
            isArchived = state?.isArchived == true,
            cacheStatus = e.cacheStatus,
            type = e.type,
            pdfPath = if (e.type == "PDF") e.blobPath else null,
            // A PDF's blob is the raw file, not gzipped HTML, so don't try to read it as an article.
            html = if (e.type == "PDF") null else blobStore.readArticle(e.blobPath),
        )
    }

    /** Article text for read-aloud: (title, plain body). Falls back to the excerpt when
     *  no full content is cached. Null when there's nothing speakable. */
    suspend fun articleText(itemId: String): Pair<String, String>? {
        val e = itemDao.getItem(itemId) ?: return null
        val html = blobStore.readArticle(e.blobPath)
        val body = html?.let { runCatching { Jsoup.parse(it).text() }.getOrNull() }?.takeIf { it.isNotBlank() }
            ?: e.excerpt?.takeIf { it.isNotBlank() }
            ?: return null
        return e.title to body
    }

    fun inbox(sourceId: String? = null, folder: String? = null): Flow<List<ItemListRow>> = itemDao.observeInbox(sourceId, folder)
    fun saved(sourceId: String? = null, folder: String? = null): Flow<List<ItemListRow>> = itemDao.observeSaved(sourceId, folder)
    fun all(sourceId: String? = null, folder: String? = null): Flow<List<ItemListRow>> = itemDao.observeAll(sourceId, folder)
    fun starred(sourceId: String? = null, folder: String? = null): Flow<List<ItemListRow>> = itemDao.observeStarred(sourceId, folder)
    fun library(): Flow<List<ItemListRow>> = itemDao.observeLibrary()
    fun libraryAll(): Flow<List<ItemListRow>> = itemDao.observeLibraryAll()
    fun unsorted(): Flow<List<ItemListRow>> = itemDao.observeUnsorted()
    fun archived(): Flow<List<ItemListRow>> = itemDao.observeArchived()
    fun favorites(): Flow<List<ItemListRow>> = itemDao.observeFavorites()
    fun libraryCounts(): Flow<com.cairn.reader.data.db.LibraryCounts> = itemDao.observeLibraryCounts()
    fun collectionItems(collectionId: String): Flow<List<ItemListRow>> = itemDao.observeCollection(collectionId)
    fun byTag(tagId: String): Flow<List<ItemListRow>> = itemDao.observeByTag(tagId)
    fun unreadCount(): Flow<Int> = itemDao.observeUnreadCount()
    fun feedUnread(): Flow<List<com.cairn.reader.data.db.FeedUnread>> = itemDao.observeFeedUnread()

    suspend fun search(query: String): List<ItemListRow> {
        val sanitized = query.trim()
        if (sanitized.isBlank()) return emptyList()
        // Prefix match on each term for a forgiving search-as-you-type feel.
        val match = sanitized.split(Regex("\\s+")).joinToString(" ") { "$it*" }
        return runCatching { itemDao.search(match) }.getOrDefault(emptyList())
    }

    suspend fun setRead(id: String, read: Boolean) {
        val now = clock()
        itemDao.setRead(id, read, now)
        enqueue("setRead", id, read.toString(), now)
    }

    /** Mark all unread items in the given scope read (null/null = everything). */
    suspend fun markAllRead(sourceId: String?, folder: String?) {
        itemDao.markScopeRead(sourceId, folder, clock())
    }

    suspend fun setStarred(id: String, starred: Boolean) {
        val now = clock()
        itemDao.setStarred(id, starred, now)
        enqueue("setStarred", id, starred.toString(), now)
    }

    suspend fun setReadLater(id: String, readLater: Boolean) {
        val now = clock()
        itemDao.setReadLater(id, readLater, now)
        enqueue("setReadLater", id, readLater.toString(), now)
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        val now = clock()
        itemDao.setArchived(id, archived, now)
        enqueue("setArchived", id, archived.toString(), now)
    }

    suspend fun setProgress(id: String, progress: Float) {
        itemDao.setProgress(id, progress.coerceIn(0f, 1f), clock())
    }

    private suspend fun enqueue(op: String, itemId: String, fields: String?, now: Long) {
        syncDao.enqueue(
            SyncOpEntity(id = UUID.randomUUID().toString(), op = op, itemId = itemId, fields = fields, createdAt = now),
        )
    }

    /** Seeds a small starter library the first time the app runs, so the UI has real
     *  content before the feed pipeline is wired in. Replaced by real sync soon. */
    suspend fun seedIfEmpty() {
        if (sourceDao.getAll().isNotEmpty()) return
        val now = clock()
        val sources = listOf(
            SourceEntity(id = "seed-tns", kind = "RSS", feedUrl = "https://thenewstack.io/feed/", siteUrl = "https://thenewstack.io", title = "The New Stack"),
            SourceEntity(id = "seed-ala", kind = "RSS", feedUrl = "https://alistapart.com/main/feed/", siteUrl = "https://alistapart.com", title = "A List Apart"),
            SourceEntity(id = "seed-cabel", kind = "RSS", feedUrl = "https://cabel.com/feed/", siteUrl = "https://cabel.com", title = "Cabel's Blog"),
        )
        sources.forEach { sourceDao.upsert(it) }

        data class Seed(val src: String, val title: String, val author: String?, val minutes: Int, val agoMin: Long, val excerpt: String)
        val seeds = listOf(
            Seed("seed-tns", "The quiet return of the personal archive", "Ellen Park", 6, 120,
                "After a decade of feeds that forget, a wave of tools is betting that the things you read should be yours to keep — searchable, offline, and free of the churn."),
            Seed("seed-ala", "How Readability actually decides what matters", "Marco Reyes", 9, 300,
                "A walk through the scoring heuristics that turn a cluttered page into a clean article, and where they still fall down."),
            Seed("seed-tns", "Designing for the second read", "Priya Nair", 4, 1440,
                "Highlights, notes, and the case for treating saved articles as a library rather than an inbox."),
            Seed("seed-cabel", "RSS never died. It just went quiet.", "Cabel Sasser", 5, 2880,
                "Why the humble feed is the most durable format on the web, and how to bend it around sites that pretend not to have one."),
        )
        seeds.forEachIndexed { index, s ->
            val id = "seed-item-$index"
            itemDao.insertItemWithState(
                ItemEntity(
                    id = id,
                    url = "https://example.com/$id",
                    title = s.title,
                    author = s.author,
                    siteName = sources.first { it.id == s.src }.title,
                    publishedAt = now - s.agoMin * 60_000,
                    savedAt = now - s.agoMin * 60_000,
                    sourceId = s.src,
                    type = "ARTICLE",
                    excerpt = s.excerpt,
                    readingMinutes = s.minutes,
                    extractStatus = "NONE",
                    contentSource = "FEED",
                    guid = id,
                ),
                now,
            )
            itemDao.indexItem(ItemFtsEntity(itemId = id, title = s.title, author = s.author, body = s.excerpt))
        }
    }
}
