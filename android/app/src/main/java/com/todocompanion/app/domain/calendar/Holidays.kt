package com.todocompanion.app.domain.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * R59 (Wave 4) — local holiday packs. Generates a region's public holidays for a year entirely on-device
 * (no network, no permission): fixed-date holidays, nth-weekday rules, and Easter-derived dates via the
 * Gregorian Computus. Imported as all-day events into a dedicated calendar the user can hide or delete.
 */
object Holidays {
    data class Pack(val id: String, val name: String, val emoji: String)

    val PACKS = listOf(
        Pack("us", "United States", "🇺🇸"),
        Pack("uk", "United Kingdom", "🇬🇧"),
        Pack("ca", "Canada", "🇨🇦"),
        Pack("intl", "International & fun days", "🌍"),
    )

    data class Holiday(val name: String, val date: LocalDate)

    /** Gregorian Easter Sunday (Anonymous / Meeus algorithm). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100; val c = year % 100
        val d = b / 4; val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    private fun nth(year: Int, month: Int, dow: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow))

    private fun last(year: Int, month: Int, dow: DayOfWeek): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(dow))

    fun forPack(packId: String, year: Int): List<Holiday> {
        val e = easter(year)
        return when (packId) {
            "us" -> listOf(
                Holiday("New Year's Day", LocalDate.of(year, 1, 1)),
                Holiday("Martin Luther King Jr. Day", nth(year, 1, DayOfWeek.MONDAY, 3)),
                Holiday("Presidents' Day", nth(year, 2, DayOfWeek.MONDAY, 3)),
                Holiday("Memorial Day", last(year, 5, DayOfWeek.MONDAY)),
                Holiday("Juneteenth", LocalDate.of(year, 6, 19)),
                Holiday("Independence Day", LocalDate.of(year, 7, 4)),
                Holiday("Labor Day", nth(year, 9, DayOfWeek.MONDAY, 1)),
                Holiday("Columbus / Indigenous Peoples' Day", nth(year, 10, DayOfWeek.MONDAY, 2)),
                Holiday("Veterans Day", LocalDate.of(year, 11, 11)),
                Holiday("Thanksgiving", nth(year, 11, DayOfWeek.THURSDAY, 4)),
                Holiday("Christmas Day", LocalDate.of(year, 12, 25)),
            )
            "uk" -> listOf(
                Holiday("New Year's Day", LocalDate.of(year, 1, 1)),
                Holiday("Good Friday", e.minusDays(2)),
                Holiday("Easter Monday", e.plusDays(1)),
                Holiday("Early May Bank Holiday", nth(year, 5, DayOfWeek.MONDAY, 1)),
                Holiday("Spring Bank Holiday", last(year, 5, DayOfWeek.MONDAY)),
                Holiday("Summer Bank Holiday", last(year, 8, DayOfWeek.MONDAY)),
                Holiday("Christmas Day", LocalDate.of(year, 12, 25)),
                Holiday("Boxing Day", LocalDate.of(year, 12, 26)),
            )
            "ca" -> listOf(
                Holiday("New Year's Day", LocalDate.of(year, 1, 1)),
                Holiday("Good Friday", e.minusDays(2)),
                Holiday("Victoria Day", LocalDate.of(year, 5, 25).with(TemporalAdjusters.previous(DayOfWeek.MONDAY))),
                Holiday("Canada Day", LocalDate.of(year, 7, 1)),
                Holiday("Labour Day", nth(year, 9, DayOfWeek.MONDAY, 1)),
                Holiday("Thanksgiving", nth(year, 10, DayOfWeek.MONDAY, 2)),
                Holiday("Remembrance Day", LocalDate.of(year, 11, 11)),
                Holiday("Christmas Day", LocalDate.of(year, 12, 25)),
                Holiday("Boxing Day", LocalDate.of(year, 12, 26)),
            )
            else -> listOf(
                Holiday("New Year's Day", LocalDate.of(year, 1, 1)),
                Holiday("Valentine's Day", LocalDate.of(year, 2, 14)),
                Holiday("International Women's Day", LocalDate.of(year, 3, 8)),
                Holiday("Earth Day", LocalDate.of(year, 4, 22)),
                Holiday("Labour Day", LocalDate.of(year, 5, 1)),
                Holiday("Halloween", LocalDate.of(year, 10, 31)),
                Holiday("Christmas Day", LocalDate.of(year, 12, 25)),
                Holiday("New Year's Eve", LocalDate.of(year, 12, 31)),
            )
        }
    }

    /** All holidays for a pack across an inclusive year range. */
    fun forRange(packId: String, fromYear: Int, toYear: Int): List<Holiday> =
        (fromYear..toYear).flatMap { forPack(packId, it) }
}
