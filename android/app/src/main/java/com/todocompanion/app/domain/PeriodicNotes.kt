package com.todocompanion.app.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Periodic Notes — one canonical note per time-period (day · week · month · year), the qualitative
 * companion to Kairo's own quantitative review/recap of that same period.
 *
 * Inspired by Obsidian's Periodic Notes plugin, but improvised for an app that already owns its data:
 *   • The plugin resolves a note's period from a filename date-format string. We have real dates, so a
 *     periodic note is resolved by (kind, period-window) instead — no fragile filename parsing.
 *   • The plugin's note is a blank page. Ours is *linked to the matching review*: the daily note ↔ the
 *     Day Review, the weekly note ↔ the weekly review, the monthly/yearly note ↔ that month/year recap.
 *     Each period's note carries an auto-embedded, always-current recap block ([RECAP_OPEN]…[RECAP_CLOSE])
 *     folded from the same [ReviewRollup] the review screens use — a digest no filename-based plugin can
 *     produce because it doesn't hold the tasks, habits and tracked time in one store.
 *   • Periods thread together: a month's note gathers its weekly notes, a week's its daily notes — a
 *     navigable time-tree, resolved live rather than hand-linked.
 *
 * Storage reuses the existing note machinery with NO schema migration: [NoteEntity.kind] gets the period
 * discriminator ([kindFor]) and the existing [NoteEntity.dayEpoch] column stores the period's anchor
 * (its first day, per [PeriodRange.window]). Daily notes keep their historical `kind == "journal"`, so
 * every daily-note feature already built (On-this-day, Wrapped, the Day-Review recap fold) keeps working.
 */
object PeriodicNotes {
    // Recap markers — shared with the daily note's writeDayRecapToNote so the fold logic is identical.
    const val RECAP_OPEN = "<!-- kairo:recap -->"
    const val RECAP_CLOSE = "<!-- /kairo:recap -->"

    /** The four periodic granularities, in coarsening order. ALL is not a periodic note. */
    val GRANULARITIES: List<PeriodRange> = listOf(PeriodRange.DAY, PeriodRange.WEEK, PeriodRange.MONTH, PeriodRange.YEAR)

    /** The [NoteEntity.kind] discriminator for a period. Daily stays "journal" for backward compatibility. */
    fun kindFor(period: PeriodRange): String = when (period) {
        PeriodRange.DAY -> "journal"
        PeriodRange.WEEK -> "weekly"
        PeriodRange.MONTH -> "monthly"
        PeriodRange.YEAR -> "yearly"
        PeriodRange.ALL -> "journal"
    }

    /** Reverse of [kindFor] — the period a note kind represents, or null for a non-periodic note. */
    fun periodOf(kind: String): PeriodRange? = when (kind) {
        "journal" -> PeriodRange.DAY
        "weekly" -> PeriodRange.WEEK
        "monthly" -> PeriodRange.MONTH
        "yearly" -> PeriodRange.YEAR
        else -> null
    }

    /** The coarser period this one rolls up into (day→week→month→year), or null at the top. */
    fun parentOf(period: PeriodRange): PeriodRange? = when (period) {
        PeriodRange.DAY -> PeriodRange.WEEK
        PeriodRange.WEEK -> PeriodRange.MONTH
        PeriodRange.MONTH -> PeriodRange.YEAR
        else -> null
    }

    /** The finer period this one contains (year→month→week→day), or null at the bottom. */
    fun childOf(period: PeriodRange): PeriodRange? = when (period) {
        PeriodRange.WEEK -> PeriodRange.DAY
        PeriodRange.MONTH -> PeriodRange.WEEK
        PeriodRange.YEAR -> PeriodRange.MONTH
        else -> null
    }

    /** Step an anchor one period-unit forward/back (for prev/next navigation), returning the new epoch-day. */
    fun step(period: PeriodRange, anchorDay: Long, forward: Boolean): Long = runCatching {
        val d = LocalDate.ofEpochDay(anchorDay)
        val n = (if (forward) 1L else -1L)
        when (period) {
            PeriodRange.DAY -> d.plusDays(n)
            PeriodRange.WEEK -> d.plusWeeks(n)
            PeriodRange.MONTH -> d.plusMonths(n)
            PeriodRange.YEAR -> d.plusYears(n)
            PeriodRange.ALL -> d
        }.toEpochDay()
    }.getOrDefault(anchorDay)

    /** A tasteful, distinct emoji per granularity (daily aligns with the Daily-cockpit template's 🌅). */
    fun emojiFor(period: PeriodRange): String = when (period) {
        PeriodRange.DAY -> "🌅"
        PeriodRange.WEEK -> "📆"
        PeriodRange.MONTH -> "🗓️"
        PeriodRange.YEAR -> "🎆"
        PeriodRange.ALL -> "📓"
    }

    /** The noun for a period's note, for buttons and copy ("daily note", "weekly note", …). */
    fun noun(period: PeriodRange): String = when (period) {
        PeriodRange.DAY -> "daily note"
        PeriodRange.WEEK -> "weekly note"
        PeriodRange.MONTH -> "monthly note"
        PeriodRange.YEAR -> "yearly note"
        PeriodRange.ALL -> "note"
    }

    /** The human title for a period's note, anchored on its first day ([startDay] from [PeriodRange.window]). */
    fun titleFor(period: PeriodRange, startDay: Long): String = runCatching {
        val d = LocalDate.ofEpochDay(startDay)
        when (period) {
            PeriodRange.DAY -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))
            PeriodRange.WEEK -> "Week of " + d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
            PeriodRange.MONTH -> d.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
            PeriodRange.YEAR -> d.year.toString()
            PeriodRange.ALL -> "All time"
        }
    }.getOrDefault(noun(period).replaceFirstChar { it.uppercase() })

    /**
     * A light Markdown scaffold seeded into a NEW periodic note (day notes are intentionally left blank,
     * matching the historical journal behaviour). Prompts are framed for reflection at that cadence; the
     * empty recap-marker block is filled immediately after creation with the live period digest.
     */
    fun seedBody(period: PeriodRange, title: String): String {
        val recap = "$RECAP_OPEN\n$RECAP_CLOSE"
        return when (period) {
            PeriodRange.WEEK -> """
                # $title

                ## Wins this week


                ## What I learned


                ## Next week's focus
                -

                $recap
            """.trimIndent()
            PeriodRange.MONTH -> """
                # $title

                ## Highlights


                ## Lessons & patterns


                ## Intentions for next month
                -

                $recap
            """.trimIndent()
            PeriodRange.YEAR -> """
                # $title

                ## The story of the year


                ## What defined it


                ## Who I'm becoming next year
                -

                $recap
            """.trimIndent()
            else -> "" // DAY / ALL: blank page (journal note keeps its historical blank body)
        }
    }
}
