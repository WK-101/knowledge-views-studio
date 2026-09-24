package com.cairn.reader.data.repo

import com.cairn.reader.data.db.TrainerDao
import com.cairn.reader.data.db.TrainerEntity
import com.cairn.reader.domain.training.TrainerKind
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the on-device training signals for the focus/hide filter. Each facet ([TrainerKind] + value)
 * holds at most one sentiment, so a stable id derived from the facet lets an upsert flip or clear it
 * idempotently. Nothing here leaves the device.
 */
@Singleton
class TrainingRepository @Inject constructor(
    private val dao: TrainerDao,
) {
    val trainers: Flow<List<TrainerEntity>> = dao.observeAll()
    val count: Flow<Int> = dao.observeCount()

    /** Feed ids are opaque and case-sensitive; text facets match case-insensitively. */
    private fun norm(kind: TrainerKind, value: String): String =
        if (kind == TrainerKind.FEED) value.trim() else value.trim().lowercase()

    private fun idFor(kind: TrainerKind, value: String) = "${kind.name}:$value"

    suspend fun sentiment(kind: TrainerKind, value: String): Int =
        dao.sentimentFor(kind.name, norm(kind, value)) ?: 0

    /**
     * Thumb a facet up (+1) or down (-1). Applying the sentiment it already carries clears it (a
     * toggle); the opposite sentiment replaces it — so a facet is never both liked and muted.
     */
    suspend fun toggle(kind: TrainerKind, value: String, sentiment: Int) {
        val v = norm(kind, value)
        if (v.isEmpty()) return
        val current = dao.sentimentFor(kind.name, v)
        if (current == sentiment) {
            dao.remove(kind.name, v)
        } else {
            dao.upsert(
                TrainerEntity(
                    id = idFor(kind, v),
                    kind = kind.name,
                    value = v,
                    sentiment = if (sentiment >= 0) 1 else -1,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun clear(kind: TrainerKind, value: String) = dao.remove(kind.name, norm(kind, value))
}
