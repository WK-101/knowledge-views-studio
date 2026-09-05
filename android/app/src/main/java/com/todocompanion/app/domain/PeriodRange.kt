package com.todocompanion.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Coherence Move 7 — the ONE period switcher vocabulary for every review / analytics surface.
 *
 * Four surfaces (Day Review, Statistics, Recap, The Record) used to each pick a time span with a
 * different range vocabulary and affordance — Day·Week·Month here, 7d·30d·90d·This year there,
 * week/month presets in a third place, and a nine-option today…lifetime list in a fourth. This is
 * the single set of options they all now share: Day · Week · Month · Year · All.
 *
 * Pure and Compose-free so it unit-tests as plain Kotlin, mirroring [YearReviewed] / [ReviewRollup].
 * [window] is the single mapping from a chosen period + anchor day to the inclusive epoch-day range
 * the surface then folds its existing content over. It reuses the app's canonical calendar-year window
 * ([YearReviewed.calendarYearWindow]) and week-start helper ([weekStartOf]) so every "year" and "week"
 * span agrees with the rest of the app, and every window is clamped to [today] (there is no data past
 * today, and letting a current period run into the future would distort any per-day averaging).
 */
enum class PeriodRange(val label: String) {
    DAY("Day"),
    WEEK("Week"),
    MONTH("Month"),
    YEAR("Year"),
    ALL("All");

    /**
     * The inclusive epoch-day window for this period around [anchorDay], honoring [weekStart]
     * (1..7 = Mon..Sun; 0/other = the locale's first day) and clamped so no window ends past [today]:
     *  - [DAY]   = the anchor day itself (never past today).
     *  - [WEEK]  = the week containing the anchor (via [weekStartOf]), clamped to today.
     *  - [MONTH] = the calendar month containing the anchor, clamped to today.
     *  - [YEAR]  = the calendar year containing the anchor (via [YearReviewed.calendarYearWindow]).
     *  - [ALL]   = an all-time window: epoch-day 0 (1970-01-01, before any real data) through today.
     */
    fun window(anchorDay: Long, weekStart: Int, today: Long): DayWindow = when (this) {
        DAY -> minOf(anchorDay, today).let { DayWindow(it, it) }
        WEEK -> {
            val ws = weekStartOf(LocalDate.ofEpochDay(anchorDay), weekStart)
            DayWindow(ws.toEpochDay(), minOf(ws.plusDays(6).toEpochDay(), today))
        }
        MONTH -> {
            val d = LocalDate.ofEpochDay(anchorDay)
            val first = d.withDayOfMonth(1)
            val last = d.withDayOfMonth(d.lengthOfMonth())
            DayWindow(first.toEpochDay(), minOf(last.toEpochDay(), today))
        }
        YEAR -> {
            val (s, e) = YearReviewed.calendarYearWindow(LocalDate.ofEpochDay(anchorDay).year, today)
            DayWindow(s, e)
        }
        ALL -> DayWindow(0L, today)
    }

    companion object {
        val ALL_PERIODS: List<PeriodRange> = entries.toList()
    }
}

/** An inclusive epoch-day span [startDay]..[endDay]. Empty (start > end) is possible for a future anchor. */
data class DayWindow(val startDay: Long, val endDay: Long) {
    /** As a [LongRange], for `epochDay in window.range` membership checks and `.first` / `.last` reads. */
    val range: LongRange get() = startDay..endDay

    /** Inclusive number of days in the window (0 when empty). */
    val days: Int get() = if (endDay < startDay) 0 else (endDay - startDay + 1).toInt()
}

/**
 * The first day of the week containing [today], honoring the user's week-start setting
 * ([weekStartSetting]: 1..7 = Mon..Sun; 0/other = the system locale's first day). The single canonical
 * week-start helper — the Day Review roll-ups, the Recap ranges, the calendar and the shared
 * [PeriodRange.window] all resolve week bounds through it, so every "week" in the app starts the same day.
 */
fun weekStartOf(today: LocalDate, weekStartSetting: Int): LocalDate {
    val firstDow = if (weekStartSetting in 1..7) DayOfWeek.of(weekStartSetting)
        else WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return today.minusDays((((today.dayOfWeek.value - firstDow.value) + 7) % 7).toLong())
}
