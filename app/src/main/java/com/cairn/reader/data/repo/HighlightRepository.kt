package com.cairn.reader.data.repo

import com.cairn.reader.data.db.HighlightDao
import com.cairn.reader.data.db.HighlightEntity
import com.cairn.reader.data.db.HighlightWithArticle
import com.cairn.reader.data.db.SyncDao
import com.cairn.reader.data.db.SyncOpEntity
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Highlights and their notes. Like item state, every change appends a [SyncOpEntity]
 * to the outbox so an optional backend can reconcile later. Highlights are anchored to
 * a block index ([HighlightEntity.startSelector]) plus a character range within that
 * block, which lets the reader re-apply them on reload without a WebView or DOM.
 */
@Singleton
class HighlightRepository @Inject constructor(
    private val highlightDao: HighlightDao,
    private val syncDao: SyncDao,
) {
    private val clock: () -> Long = { System.currentTimeMillis() }

    fun observeForItem(itemId: String): Flow<List<HighlightEntity>> = highlightDao.observeForItem(itemId)
    fun observeAllWithArticle(): Flow<List<HighlightWithArticle>> = highlightDao.observeAllWithArticle()
    fun observeCount(): Flow<Int> = highlightDao.observeCount()

    // -- Spaced-repetition recall (SM-2) ---------------------------------------

    /** How many highlights are due for review right now. */
    fun observeDueCount(): Flow<Int> = highlightDao.observeDueCount(clock())

    /** The next batch of due review cards, oldest-due first. */
    suspend fun dueCards(limit: Int = 40): List<com.cairn.reader.data.db.ReviewCard> =
        highlightDao.dueCards(clock(), limit)

    /**
     * Grade a card and reschedule it. With [advanced] on, the FSRS-5 engine updates the card's
     * stability/difficulty and targets [retention] (capped at [maxIntervalDays]); otherwise the
     * classic SM-2 ease ladder runs. Each engine persists only its own columns, so a user can switch
     * schedulers back and forth without losing either model's memory of a card.
     */
    suspend fun review(
        card: com.cairn.reader.data.db.ReviewCard,
        grade: com.cairn.reader.domain.review.Grade,
        advanced: Boolean = true,
        retention: Double = 0.90,
        maxIntervalDays: Int = 3650,
    ) {
        val now = clock()
        if (advanced) {
            val state = com.cairn.reader.domain.review.FsrsState(
                stability = card.srStability,
                difficulty = card.srDifficulty,
                phase = com.cairn.reader.domain.review.SrPhase.entries.getOrElse(card.srPhase) {
                    com.cairn.reader.domain.review.SrPhase.NEW
                },
                reps = card.srReps,
                lapses = card.srLapses,
            )
            val r = com.cairn.reader.domain.review.Fsrs.review(
                state, grade, now, card.srLastReviewedAt, retention, maxIntervalDays,
            )
            highlightDao.updateSrFsrs(
                card.id, r.dueAt, r.intervalDays, r.state.reps, r.state.lapses,
                r.state.stability, r.state.difficulty, r.state.phase.ordinal, now,
            )
        } else {
            val state = com.cairn.reader.domain.review.SrState(card.srInterval, card.srEase, card.srReps, card.srLapses)
            val r = com.cairn.reader.domain.review.Sm2.review(state, grade, now)
            highlightDao.updateSr(card.id, r.dueAt, r.state.intervalDays, r.state.ease, r.state.reps, r.state.lapses, now)
        }
    }

    suspend fun add(itemId: String, blockIndex: Int, start: Int, end: Int, quote: String, color: Int) {
        val now = clock()
        val id = UUID.randomUUID().toString()
        highlightDao.upsert(
            HighlightEntity(
                id = id,
                itemId = itemId,
                quote = quote,
                color = color,
                startSelector = blockIndex.toString(),
                startOffset = start,
                endOffset = end,
                createdAt = now,
            ),
        )
        enqueue("addHighlight", itemId, id, now)
    }

    /**
     * Add a transcript annotation: a highlight anchored to a media timestamp rather than an article
     * block. The time range is stored in the selector columns as `t:<ms>` so the transcript screen
     * can re-mark and seek to it, and the note carries a human `[mm:ss]` stamp so the annotation
     * reads as a proper, timestamped quote everywhere it appears (notebook, exports, share).
     */
    suspend fun addTimestamped(itemId: String, startMs: Long, endMs: Long, quote: String, color: Int, note: String? = null): String {
        val now = clock()
        val id = UUID.randomUUID().toString()
        highlightDao.upsert(
            HighlightEntity(
                id = id,
                itemId = itemId,
                quote = quote,
                note = note?.ifBlank { null },
                color = color,
                startSelector = "t:$startMs",
                startOffset = 0,
                endSelector = "t:$endMs",
                endOffset = 0,
                createdAt = now,
            ),
        )
        enqueue("addHighlight", itemId, id, now)
        return id
    }

    suspend fun setNote(id: String, itemId: String, note: String?) {
        highlightDao.setNote(id, note?.ifBlank { null })
        enqueue("setHighlightNote", itemId, id, clock())
    }

    suspend fun setColor(id: String, itemId: String, color: Int) {
        highlightDao.setColor(id, color)
        enqueue("setHighlightColor", itemId, id, clock())
    }

    suspend fun remove(id: String, itemId: String) {
        highlightDao.delete(id)
        enqueue("removeHighlight", itemId, id, clock())
    }

    /** Markdown for one article's highlights, ready to share or save. */
    suspend fun exportItem(itemId: String): String = render(highlightDao.forItemWithArticle(itemId))

    /** Markdown for every highlight, grouped by article. */
    suspend fun exportAll(): String = render(highlightDao.allWithArticle())

    private fun render(rows: List<HighlightWithArticle>): String {
        if (rows.isEmpty()) return "No highlights yet."
        val date = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        return buildString {
            append("# Highlights\n")
            rows.groupBy { it.itemId }.values.forEach { group ->
                val first = group.first()
                append("\n## ").append(first.articleTitle).append('\n')
                if (first.articleUrl.isNotBlank()) append(first.articleUrl).append('\n')
                group.forEach { h ->
                    append('\n')
                    h.quote.trim().split("\n").forEach { line -> append("> ").append(line).append('\n') }
                    h.note?.takeIf { it.isNotBlank() }?.let { append("\n_Note:_ ").append(it.trim()).append('\n') }
                    append("\n_").append(date.format(Date(h.createdAt))).append("_\n")
                }
                append('\n').append("---\n")
            }
        }.trimEnd()
    }

    private suspend fun enqueue(op: String, itemId: String, highlightId: String, now: Long) {
        syncDao.enqueue(
            SyncOpEntity(id = UUID.randomUUID().toString(), op = op, itemId = itemId, fields = highlightId, createdAt = now),
        )
    }
}
