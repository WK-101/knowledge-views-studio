package com.cairn.reader.domain.training

import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.db.TrainerEntity

/** The facet a trainer signal is attached to. */
enum class TrainerKind { AUTHOR, TAG, FEED, TITLE }

/** Where a story lands once its trainer signals are summed. */
enum class Tier { FOCUS, NEUTRAL, HIDDEN }

/**
 * Pure, on-device scorer for the NewsBlur-style training filter: sum the sentiments of every trainer
 * facet a story matches. A story by a liked author with a muted keyword nets out; positive is Focus,
 * negative is Hidden, zero is Neutral. Reads only fields already on [ItemListRow] (author, sourceId,
 * tagNames, title), so scoring a list costs nothing beyond the trainer set — no per-item query.
 */
object TrainingScorer {
    private const val SEP = '\u001f'

    fun score(row: ItemListRow, trainers: List<TrainerEntity>): Int {
        if (trainers.isEmpty()) return 0
        val author = row.author?.trim()?.lowercase()
        val title = row.title.lowercase()
        val tags = row.tagNames?.split(SEP)?.mapNotNull { it.trim().lowercase().ifEmpty { null } } ?: emptyList()
        var s = 0
        for (t in trainers) {
            val hit = when (t.kind) {
                TrainerKind.AUTHOR.name -> author != null && author == t.value
                TrainerKind.FEED.name -> row.sourceId == t.value
                TrainerKind.TAG.name -> t.value in tags
                TrainerKind.TITLE.name -> t.value.isNotEmpty() && title.contains(t.value)
                else -> false
            }
            if (hit) s += t.sentiment
        }
        return s
    }

    fun tier(score: Int): Tier = when {
        score > 0 -> Tier.FOCUS
        score < 0 -> Tier.HIDDEN
        else -> Tier.NEUTRAL
    }
}
