package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.domain.done.Accomplishment
import com.todocompanion.app.domain.done.DoneKind
import java.time.LocalDate
import java.util.Locale

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

    // ── Track 3.2 — "Moments to reflect on": a ranked list of candidate second-looks ──────────────────
    //
    // The single [select] above stays the calm one-card resurfacing. This grows the same idea into a small
    // engine that offers a *ranked list* of moments worth a second look, each sourced entirely from the
    // user's own logs: an anniversary, a recent hard day, a just-finished project/goal, a returning
    // obstacle, and a bright high-mood highlight. Each moment is an icon + a gentle line + an optional
    // deep-link day. Pure, deterministic, gracefully empty. Nothing leaves the device.

    /** How far back the "recent" sources reach. */
    private const val RECENT_HARD_DAYS = 21
    private const val RECENT_FINISH_DAYS = 30
    private const val RECENT_BRIGHT_DAYS = 45
    private const val OBSTACLE_WINDOW_DAYS = 45

    enum class MomentKind(val icon: String) {
        ANNIVERSARY("🕰️"),
        HARD_DAY("🌧️"),
        FINISHED("🏁"),
        RETURNING_OBSTACLE("🧱"),
        HIGH_MOMENT("✨"),
    }

    /**
     * One candidate "moment worth a second look". [line] is the gentle, already-framed caption; [epochDay]
     * is the day to open on tap (null = no single day); [rank] orders the list (higher first).
     */
    data class Moment(
        val kind: MomentKind,
        val line: String,
        val epochDay: Long?,
        val rank: Double,
    )

    /**
     * Build a ranked list of moments to reflect on for [anchorDay], from the user's [dayLogs] and,
     * optionally, the achievement [feed] (for just-finished projects/goals). Deterministic and honest —
     * returns fewer than [max] (or none) when there is little to draw on. Each source contributes at most
     * one moment so the row stays varied.
     */
    fun moments(
        anchorDay: Long,
        dayLogs: List<DayLogEntity>,
        feed: List<Accomplishment> = emptyList(),
        max: Int = 5,
    ): List<Moment> {
        val byDay = dayLogs.associateBy { it.epochDay }
        val out = mutableListOf<Moment>()

        // 1. Anniversary — the strongest pull: the same calendar date, a prior year then a prior month.
        select(anchorDay, dayLogs)?.takeIf { it.kind == Kind.ON_THIS_DAY }?.let { m ->
            out += Moment(MomentKind.ANNIVERSARY, "${m.whenLabel} today — “${m.text}”", m.epochDay, 1.0)
        }

        // 2. A just-finished project or goal in the last month — worth pausing to mark.
        feed.asSequence()
            .filter { (it.kind == DoneKind.GOAL || it.kind == DoneKind.PROJECT) && it.epochDay in (anchorDay - RECENT_FINISH_DAYS)..anchorDay }
            .maxByOrNull { it.whenMillis }
            ?.let { a ->
                val back = (anchorDay - a.epochDay).toInt().coerceAtLeast(0)
                out += Moment(MomentKind.FINISHED, "You finished “${a.title.trim()}” ${lowerWhen(back)} — take a moment to mark it.", a.epochDay, 0.9)
            }

        // 3. A returning obstacle / lesson — the same worry named on more than one recent day.
        returningObstacle(anchorDay, dayLogs)?.let { out += it }

        // 4. A recent hard day — a low-rated day in the last few weeks, worth a gentler second look.
        val hard = (1..RECENT_HARD_DAYS)
            .mapNotNull { back -> byDay[anchorDay - back] }
            .filter { it.dayRating in 1..2 }
            .minByOrNull { it.dayRating }   // the hardest first; ties → most recent (earliest back)
        if (hard != null) {
            val back = (anchorDay - hard.epochDay).toInt()
            val tail = snippet(hard).takeIf { it.isNotBlank() }?.let { " You wrote: “$it”." } ?: ""
            out += Moment(MomentKind.HARD_DAY, "A hard day ${lowerWhen(back)} — worth a kinder second look.$tail", hard.epochDay, 0.7)
        }

        // 5. A bright high-mood highlight in the last several weeks — a good moment worth savouring again.
        val bright = (1..RECENT_BRIGHT_DAYS)
            .mapNotNull { back -> byDay[anchorDay - back] }
            .filter { it.dayRating in 4..5 && it.highlight.isNotBlank() }
            .maxWithOrNull(compareBy<DayLogEntity>({ it.dayRating }, { it.epochDay }))
        if (bright != null) {
            val back = (anchorDay - bright.epochDay).toInt()
            out += Moment(MomentKind.HIGH_MOMENT, "A bright spot ${lowerWhen(back)} — “${bright.highlight.trim()}”.", bright.epochDay, 0.6)
        }

        return out
            .distinctBy { it.kind }
            .sortedWith(compareByDescending<Moment> { it.rank }.thenByDescending { it.epochDay ?: Long.MIN_VALUE })
            .take(max)
    }

    /** A worry the user named on more than one recent day (tomorrowObstacle or lesson), most recent shown. */
    private fun returningObstacle(anchorDay: Long, dayLogs: List<DayLogEntity>): Moment? {
        val window = dayLogs.filter { it.epochDay in (anchorDay - OBSTACLE_WINDOW_DAYS)..anchorDay }
        data class Hit(val norm: String, val text: String, val day: Long)
        val hits = window.flatMap { l ->
            listOf(l.tomorrowObstacle, l.lesson)
                .map { it.trim() }
                .filter { it.length >= 4 }
                .map { Hit(it.lowercase(Locale.getDefault()), it, l.epochDay) }
        }
        val grouped = hits.groupBy { it.norm }.filter { it.value.size >= 2 }
        if (grouped.isEmpty()) return null
        // The most-recurring worry (ties → the one seen most recently).
        val top = grouped.values.maxWithOrNull(
            compareBy<List<Hit>>({ it.size }, { it.maxOf { h -> h.day } })
        ) ?: return null
        val recent = top.maxByOrNull { it.day }!!
        return Moment(
            MomentKind.RETURNING_OBSTACLE,
            "“${recent.text}” has come up ${top.size} times lately — maybe worth a plan.",
            recent.day,
            0.8,
        )
    }

    /** A gentle lowercase relative caption for use mid-sentence ("a year ago", "3 days ago"). */
    private fun lowerWhen(back: Int): String = daysAgo(back).replaceFirstChar { it.lowercase(Locale.getDefault()) }
}
