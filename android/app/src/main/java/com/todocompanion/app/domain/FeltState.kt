package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.DayLogEntity
import java.util.Locale

/**
 * Track 1 (Unify) — the shared "how it felt" fold. A pure, Compose-free summary of the felt state the
 * day logs already carry (day-rating, energy, evening mood, precise emotion word) over any inclusive
 * epoch-day window, so every achievement surface — Momentum, Statistics, The Record — can show the same
 * felt lane without each re-deriving it from raw entities.
 *
 * The aggregation mirrors what ReviewRollup / YearReviewed already do: ratings and mood are the 1–5
 * signals averaged over the days they were logged, with a per-day trend list (null = nothing that day)
 * for a sparkline / mood strip; the dominant emotion is the most-common *known* emotion word. Nothing
 * leaves the device and nothing is persisted (no schema change). Unit-tested as plain Kotlin.
 */
object FeltState {

    /**
     * The felt summary for a window. Averages are 0.0 when nothing was logged; trend lists have one slot
     * per day in the window (null where that day had no value). [dominantEmotion] is "" when no known
     * emotion word was named enough to report.
     */
    data class FeltSummary(
        val startDay: Long,
        val endDay: Long,
        val daysInRange: Int,
        val daysReviewed: Int,
        val avgRating: Double,
        val ratedDays: Int,
        val ratingTrend: List<Int?>,
        val avgEnergy: Double,
        val energyDays: Int,
        val avgMood: Double,
        val moodDays: Int,
        val moodTrend: List<Int?>,
        val dominantEmotion: String,
        val dominantEmotionCount: Int,
    ) {
        /** True when there is any felt signal worth rendering a lane for. */
        val hasData: Boolean
            get() = ratedDays > 0 || moodDays > 0 || energyDays > 0 || dominantEmotion.isNotBlank()
    }

    /** An empty summary for [startDay]..[endDay] (used for a reversed or empty window). */
    private fun empty(startDay: Long, endDay: Long): FeltSummary =
        FeltSummary(startDay, endDay, 0, 0, 0.0, 0, emptyList(), 0.0, 0, 0.0, 0, emptyList(), "", 0)

    /**
     * Fold [logs] into a [FeltSummary] over the inclusive window [rangeStart]..[rangeEnd]. Day logs must
     * already be workspace-scoped (as every felt surface scopes them). Mood mirrors ReviewRollup: the
     * evening mood (pmMood, 1–5) averaged over the days it was logged.
     */
    fun summarize(logs: List<DayLogEntity>, rangeStart: Long, rangeEnd: Long): FeltSummary {
        if (rangeEnd < rangeStart) return empty(rangeStart, rangeEnd)
        val logByDay = logs.filter { it.epochDay in rangeStart..rangeEnd }.associateBy { it.epochDay }
        val daysInRange = (rangeEnd - rangeStart + 1).toInt()

        val daysReviewed = logByDay.values.count { ReviewRollup.isReviewed(it) }

        val ratingTrend = (rangeStart..rangeEnd).map { d -> logByDay[d]?.dayRating?.takeIf { it in 1..5 } }
        val ratings = ratingTrend.filterNotNull()
        val avgRating = if (ratings.isEmpty()) 0.0 else ratings.average()

        val moodTrend = (rangeStart..rangeEnd).map { d -> logByDay[d]?.pmMood?.takeIf { it in 1..5 } }
        val moods = moodTrend.filterNotNull()
        val avgMood = if (moods.isEmpty()) 0.0 else moods.average()

        val energies = (rangeStart..rangeEnd).mapNotNull { d -> logByDay[d]?.energy?.takeIf { it in 1..5 } }
        val avgEnergy = if (energies.isEmpty()) 0.0 else energies.average()

        val emotionCounts = logByDay.values
            .mapNotNull { it.emotionLabel.trim().takeIf { w -> EmotionWords.isKnown(w) } }
            .groupingBy { it.lowercase(Locale.getDefault()) }
            .eachCount()
        val top = emotionCounts.maxByOrNull { it.value }
        val dominantEmotion = top?.key?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: ""
        val dominantEmotionCount = top?.value ?: 0

        return FeltSummary(
            startDay = rangeStart, endDay = rangeEnd, daysInRange = daysInRange,
            daysReviewed = daysReviewed,
            avgRating = avgRating, ratedDays = ratings.size, ratingTrend = ratingTrend,
            avgEnergy = avgEnergy, energyDays = energies.size,
            avgMood = avgMood, moodDays = moods.size, moodTrend = moodTrend,
            dominantEmotion = dominantEmotion, dominantEmotionCount = dominantEmotionCount,
        )
    }
}
