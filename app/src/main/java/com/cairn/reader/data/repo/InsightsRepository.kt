package com.cairn.reader.data.repo

import com.cairn.reader.data.db.InsightsDao
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemListRow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** A private snapshot of the reader's habits — computed on-device, never uploaded. */
data class ReadingAnalytics(
    val read: Int,
    val starred: Int,
    val saved: Int,
    val readMinutes: Int,
    val readThisWeek: Int,
    val streakDays: Int,
    val topSources: List<Pair<String, Int>>,
)

/** A feed-hygiene finding — a feed worth pruning, fixing, or that's gone quiet. */
data class HygieneIssue(
    val kind: Kind,
    val title: String,
    val detail: String,
    val sourceId: String?,
) {
    enum class Kind { FAILING, NEVER_OPENED, STALE, BROKEN_LINKS, DUPLICATES }
}

@Singleton
class InsightsRepository @Inject constructor(
    private val insightsDao: InsightsDao,
    private val itemDao: ItemDao,
    private val itemRepository: ItemRepository,
) {
    suspend fun analytics(): ReadingAnalytics {
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600_000
        return ReadingAnalytics(
            read = insightsDao.readCount(),
            starred = insightsDao.starredCount(),
            saved = insightsDao.savedCount(),
            readMinutes = insightsDao.readMinutesSum(),
            readThisWeek = insightsDao.readSince(weekAgo),
            streakDays = computeStreak(insightsDao.readTimestamps()),
            topSources = insightsDao.topReadSources(5).map { it.title to it.c },
        )
    }

    /** Longest run of consecutive days (ending today or yesterday) with at least one article read. */
    private fun computeStreak(timestamps: List<Long>): Int {
        if (timestamps.isEmpty()) return 0
        val dayMs = 24L * 3600_000
        val days = timestamps.map { it / dayMs }.toSortedSet().toList().reversed() // distinct days, newest first
        val today = System.currentTimeMillis() / dayMs
        if (days.first() < today - 1) return 0 // last read was before yesterday — streak broken
        var streak = 1
        for (i in 1 until days.size) {
            if (days[i] == days[i - 1] - 1) streak++ else break
        }
        return streak
    }

    suspend fun feedHygiene(): List<HygieneIssue> {
        val issues = ArrayList<HygieneIssue>()
        val staleCutoff = System.currentTimeMillis() - 60L * 24 * 3600_000 // 60 days
        insightsDao.sourceHealth().forEach { h ->
            when {
                h.errors >= 3 ->
                    issues += HygieneIssue(HygieneIssue.Kind.FAILING, h.title, "Failing to sync (${h.errors} errors in a row)", h.id)
                h.itemCount >= 15 && h.readCount == 0 ->
                    issues += HygieneIssue(HygieneIssue.Kind.NEVER_OPENED, h.title, "${h.itemCount} articles, none ever opened", h.id)
                h.lastSyncedAt != null && h.lastSyncedAt < staleCutoff ->
                    issues += HygieneIssue(HygieneIssue.Kind.STALE, h.title, "No new posts in months", h.id)
            }
        }
        val broken = itemDao.observeBrokenCount().first()
        if (broken > 0) issues += HygieneIssue(HygieneIssue.Kind.BROKEN_LINKS, "Broken links", "$broken saved article(s) no longer resolve", null)
        val dupes = itemDao.observeDuplicatesCount().first()
        if (dupes > 0) issues += HygieneIssue(HygieneIssue.Kind.DUPLICATES, "Duplicates", "$dupes duplicate article(s) across feeds", null)
        // Order: failing first, then never-opened, then the rest.
        return issues.sortedBy { it.kind.ordinal }
    }

    /**
     * Passive Focus scoring: rank unread items by how well they match what the reader actually
     * engages with — the feeds they open and the words in titles they read/star/save — plus a
     * freshness nudge. No model, no network, no profile leaves the device.
     */
    suspend fun topPicks(limit: Int = 20): List<ItemListRow> {
        val unread = itemRepository.inbox().first()
        if (unread.isEmpty()) return emptyList()

        val sourceWeight = insightsDao.sourceEngagement().associate { it.sourceId to it.weight }
        val profile = HashMap<String, Int>()
        insightsDao.engagedTitles(300).forEach { t -> tokenize(t.title).forEach { profile[it] = (profile[it] ?: 0) + 1 } }
        // If the reader has no history yet, fall back to plain recency.
        if (sourceWeight.isEmpty() && profile.isEmpty()) return unread.take(limit)

        val now = System.currentTimeMillis()
        val maxSrc = max(1, sourceWeight.values.maxOrNull() ?: 1)
        val scored = unread.map { row ->
            val src = (sourceWeight[row.sourceId] ?: 0).toDouble() / maxSrc // 0..1
            val overlap = tokenize(row.title).sumOf { (profile[it] ?: 0).toDouble() }
            val ageDays = ((now - (row.publishedAt ?: row.savedAt)).coerceAtLeast(0)) / (24.0 * 3600_000)
            val freshness = 1.0 / (1.0 + ageDays / 3.0) // decays over a few days
            row to (src * 3.0 + overlap * 0.5 + freshness)
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private val stopwords = setOf(
        "the", "and", "for", "are", "but", "not", "you", "your", "with", "from", "this", "that",
        "have", "has", "was", "will", "what", "how", "why", "who", "can", "all", "new", "out",
        "about", "into", "over", "more", "than", "then", "they", "them", "its", "his", "her",
    )

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in stopwords }
}
