package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.DayLogEntity
import java.time.LocalDate

/**
 * Wave 2 (feature 8) — local memory resurfacing. Gently brings one past moment back into view on the
 * single-day review, chosen entirely from local day logs:
 *  1. an "on this day" memory — a reflection or highlight written on the same calendar date in a prior
 *     year or month ("A year ago you wrote…"); failing that,
 *  2. a recent good moment worth savouring — a highlight / three-good-thing / high-rated day from the
 *     last couple of weeks.
 *
 * Pure, deterministic and Compose-free so it unit-tests as plain Kotlin (mirroring ReviewInsights /
 * DayPrompts). One memory at a time, and gracefully empty when there is no history to draw on. Nothing
 * leaves the device.
 */
object DayMemories {

    /** How far back the "on this day" anniversary search reaches. */
    private const val MAX_YEARS = 12
    private const val MAX_MONTHS = 11

    /** The window (in days before the anchor) the "recent good moment" fallback draws from. */
    private const val RECENT_DAYS = 14

    enum class Kind { ON_THIS_DAY, RECENT_GOOD }

    /**
     * One resurfaced memory. [epochDay] is the day it came from (tap to open); [whenLabel] is a gentle
     * relative caption ("A year ago", "Last month", "2 weeks ago"); [text] is the surfaced snippet;
     * [rating] is that day's rating (0 = unrated) for an optional star read-back.
     */
    data class DayMemory(
        val kind: Kind,
        val epochDay: Long,
        val whenLabel: String,
        val text: String,
        val rating: Int,
    )

    /**
     * Pick at most one memory to surface for [anchorDay] (usually the day being reviewed), from [dayLogs].
     * Deterministic: the same inputs always yield the same result. Returns null when there is nothing
     * worth resurfacing.
     */
    fun select(anchorDay: Long, dayLogs: List<DayLogEntity>, memorableFilter: (DayLogEntity) -> Boolean = ::isMemorable): DayMemory? {
        val byDay = dayLogs.associateBy { it.epochDay }
        val anchor = LocalDate.ofEpochDay(anchorDay)

        // 1. "On this day" — same calendar date in a prior year, then a prior month.
        for (years in 1..MAX_YEARS) {
            val d = anchor.minusYears(years.toLong()).toEpochDay()
            val log = byDay[d] ?: continue
            if (memorableFilter(log)) return DayMemory(Kind.ON_THIS_DAY, d, yearsAgo(years), snippet(log), log.dayRating)
        }
        for (months in 1..MAX_MONTHS) {
            val d = anchor.minusMonths(months.toLong()).toEpochDay()
            val log = byDay[d] ?: continue
            if (memorableFilter(log)) return DayMemory(Kind.ON_THIS_DAY, d, monthsAgo(months), snippet(log), log.dayRating)
        }

        // 2. Recent good moment — the best of the last couple of weeks (highest rating, then most recent).
        val recent = (1..RECENT_DAYS)
            .mapNotNull { back -> byDay[anchorDay - back] }
            .filter { memorableFilter(it) }
        val pick = recent.maxWithOrNull(compareBy<DayLogEntity>({ it.dayRating }, { it.epochDay }))
        if (pick != null) {
            val back = (anchorDay - pick.epochDay).toInt()
            return DayMemory(Kind.RECENT_GOOD, pick.epochDay, daysAgo(back), snippet(pick), pick.dayRating)
        }
        return null
    }

    /** A day is worth resurfacing when it has some words to show — a highlight, good thing, or note. */
    fun isMemorable(log: DayLogEntity): Boolean = snippet(log).isNotBlank()

    /** The best single line to resurface from a day: highlight, else a good-thing, gratitude, lesson,
     *  reflection or prompt answer — whichever is present first, in that gentle-first order. */
    fun snippet(log: DayLogEntity): String {
        val candidates = listOf(
            log.highlight, log.good1, log.good2, log.good3,
            log.gratitude, log.lesson, log.pmReflection, log.promptAnswer,
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun yearsAgo(y: Int): String = if (y == 1) "A year ago" else "$y years ago"
    private fun monthsAgo(m: Int): String = if (m == 1) "A month ago" else "$m months ago"
    private fun daysAgo(d: Int): String = when {
        d <= 1 -> "Yesterday"
        d < 7 -> "$d days ago"
        d == 7 -> "A week ago"
        d < 14 -> "$d days ago"
        else -> "2 weeks ago"
    }
}
