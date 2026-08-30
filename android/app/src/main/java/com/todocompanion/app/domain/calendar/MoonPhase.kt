package com.todocompanion.app.domain.calendar

import java.time.LocalDate

/**
 * R42 — a purely local lunar-phase calculation (no network, no data), for the optional moon overlay in
 * the month grid, à la aCalendar. Uses the standard synodic-month approximation from a known new moon.
 */
object MoonPhase {
    private const val SYNODIC = 29.53058867 // days between new moons
    // Reference new moon: 2000-01-06 18:14 UTC ≈ Julian day 2451550.1.
    private const val REF_EPOCH_DAY = 10962.0 // 2000-01-06 as epoch-day (days since 1970-01-01)

    /** 0..7 phase index: 0 new, 2 first-quarter, 4 full, 6 last-quarter. */
    fun index(day: Long): Int {
        val age = ((day - REF_EPOCH_DAY) % SYNODIC + SYNODIC) % SYNODIC
        return Math.round(age / SYNODIC * 8).toInt() % 8
    }

    fun glyph(day: Long): String = when (index(day)) {
        0 -> "🌑"  // 🌑 new
        1 -> "🌒"  // 🌒 waxing crescent
        2 -> "🌓"  // 🌓 first quarter
        3 -> "🌔"  // 🌔 waxing gibbous
        4 -> "🌕"  // 🌕 full
        5 -> "🌖"  // 🌖 waning gibbous
        6 -> "🌗"  // 🌗 last quarter
        else -> "🌘" // 🌘 waning crescent
    }

    /** Only the four principal phases are worth marking on a compact grid. */
    fun isPrincipal(day: Long): Boolean = index(day) % 2 == 0
    fun label(day: Long): String = when (index(day)) {
        0 -> "New moon"; 2 -> "First quarter"; 4 -> "Full moon"; 6 -> "Last quarter"; else -> ""
    }
    fun today(): Int = index(LocalDate.now().toEpochDay())
}
