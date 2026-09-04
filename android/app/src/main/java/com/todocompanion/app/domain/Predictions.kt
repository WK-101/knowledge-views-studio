package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Wave 3 (feature C) — the Drucker feedback-analysis loop. You log a *prediction* now ("I expect that
 * finishing X / this change will make me feel Y") with a resurface date weeks or months out. When that
 * date arrives the day review brings it back — "N weeks ago you predicted… — how did it actually turn
 * out?" — and you record the outcome (a short note + a matched / not-matched marker). Comparing what you
 * expected to what happened, over a private local timeline, is exactly the practice Peter Drucker called
 * feedback analysis; almost no consumer app does it.
 *
 * The whole store is ONE settings JSON value ([AppSettings.predictionsJson]) — a list of [Prediction] —
 * mirroring how the app already keeps list-like data as JSON strings (DailyQuestions / WeeklyReview), so
 * there is no schema change and it round-trips in the lossless backup. Pure and Compose-free so it
 * unit-tests as plain Kotlin.
 */
@Serializable
data class Prediction(
    val id: String,
    val createdEpochDay: Long,        // when the prediction was made
    val resurfaceEpochDay: Long,      // when it should come back to be checked
    val expectation: String,          // "…will make me feel…" — the prediction itself
    // Recorded when the prediction is resolved after resurfacing (all optional until then).
    val resolved: Boolean = false,
    val outcomeNote: String = "",      // how it actually turned out, in the user's words
    val matched: Int = 0,              // 0 unset · 1 matched (as expected) · 2 not matched
    val resolvedEpochDay: Long = 0,
)

object Predictions {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Matched-marker values, kept explicit so the UI and tests agree. */
    const val MATCH_UNSET = 0
    const val MATCH_YES = 1
    const val MATCH_NO = 2

    /** Parse the whole store (a list) from its settings JSON ("" / malformed = empty). */
    fun parseAll(s: String): List<Prediction> =
        if (s.isBlank()) emptyList()
        else runCatching { json.decodeFromString<List<Prediction>>(s) }.getOrDefault(emptyList())

    /** Serialize the whole store back to a settings JSON string ("" when empty). */
    fun encodeAll(list: List<Prediction>): String =
        if (list.isEmpty()) "" else runCatching { json.encodeToString(list) }.getOrDefault("")

    /** Add / replace one prediction (matched by id), returning the new JSON string. */
    fun upsert(s: String, prediction: Prediction): String {
        val map = parseAll(s).associateBy { it.id }.toMutableMap()
        map[prediction.id] = prediction
        return encodeAll(map.values.sortedBy { it.createdEpochDay })
    }

    /** Remove one prediction by id, returning the new JSON string. */
    fun remove(s: String, id: String): String = encodeAll(parseAll(s).filterNot { it.id == id })

    /**
     * Record the outcome of a prediction after it has resurfaced: an optional note and a matched marker,
     * flipping it to resolved so it stops surfacing. A no-op (returns the store unchanged) if the id is
     * unknown. [matched] is coerced to a known marker value.
     */
    fun resolve(s: String, id: String, outcomeNote: String, matched: Int, resolvedEpochDay: Long): String {
        val list = parseAll(s)
        if (list.none { it.id == id }) return s
        return encodeAll(list.map { p ->
            if (p.id != id) p
            else p.copy(
                resolved = true,
                outcomeNote = outcomeNote.trim(),
                matched = matched.coerceIn(MATCH_UNSET, MATCH_NO),
                resolvedEpochDay = resolvedEpochDay,
            )
        })
    }

    /**
     * The unresolved predictions whose resurface date has arrived by [today], oldest-resurfacing first —
     * the set the day review brings back to be checked. Never returns a resolved prediction.
     */
    fun dueToResurface(list: List<Prediction>, today: Long): List<Prediction> =
        list.filter { !it.resolved && it.resurfaceEpochDay <= today }
            .sortedBy { it.resurfaceEpochDay }

    /** Predictions still waiting for their resurface date (unresolved, not yet due), soonest first. */
    fun pending(list: List<Prediction>, today: Long): List<Prediction> =
        list.filter { !it.resolved && it.resurfaceEpochDay > today }
            .sortedBy { it.resurfaceEpochDay }

    /**
     * A gentle "how long ago" caption for when the prediction was made, relative to [today]:
     * "N weeks ago" / "N months ago" (falling back to days for very recent ones).
     */
    fun sinceLabel(createdEpochDay: Long, today: Long): String {
        val days = (today - createdEpochDay).coerceAtLeast(0)
        return when {
            days <= 1L -> "Yesterday"
            days < 7L -> "$days days ago"
            days < 60L -> {
                val w = (days / 7.0).roundToInt().coerceAtLeast(1)
                if (w == 1) "A week ago" else "$w weeks ago"
            }
            else -> {
                val m = (days / 30.0).roundToInt().coerceAtLeast(2)
                "$m months ago"
            }
        }
    }

    /** Standard resurface horizons offered when logging a prediction (label to weeks-out). */
    val HORIZONS: List<Pair<String, Long>> = listOf(
        "2 weeks" to 14L, "1 month" to 30L, "3 months" to 90L, "6 months" to 180L, "1 year" to 365L,
    )

    /** The resurface epoch-day for a horizon in days from [createdEpochDay]. */
    fun resurfaceFor(createdEpochDay: Long, daysOut: Long): Long =
        createdEpochDay + daysOut.coerceAtLeast(1)

    /** Convenience for the LocalDate-driven UI. */
    fun sinceLabel(createdEpochDay: Long): String = sinceLabel(createdEpochDay, LocalDate.now().toEpochDay())
}
