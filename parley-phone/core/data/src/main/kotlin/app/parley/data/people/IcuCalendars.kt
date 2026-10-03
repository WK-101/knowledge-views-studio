package app.parley.data.people

import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import app.parley.common.AltCalendar
import app.parley.common.AltDay
import app.parley.common.CalendarConverter
import java.time.LocalDate

/**
 * [CalendarConverter] on Android's own ICU calendars (android.icu, API 24+): Chinese, Hebrew and Hijri by
 * Umm al-Qura. No library and no network; the arithmetic is the system's. Days are taken at noon UTC, so no time
 * zone moves a date.
 */
object IcuCalendars : CalendarConverter {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun calendar(c: AltCalendar): Calendar = Calendar.getInstance(utc, ULocale("en@calendar=${c.key}")).apply { isLenient = false }

    private fun gregorian(): Calendar = Calendar.getInstance(utc, ULocale("en@calendar=gregorian")).apply { isLenient = false }

    override fun toAlt(calendar: AltCalendar, date: LocalDate): AltDay {
        val g = gregorian().apply {
            clear()
            set(date.year, date.monthValue - 1, date.dayOfMonth, NOON, 0, 0)
        }
        val c = calendar(calendar).apply { timeInMillis = g.timeInMillis }
        val leap = calendar == AltCalendar.CHINESE && c.get(Calendar.IS_LEAP_MONTH) == 1
        return AltDay(c.get(Calendar.EXTENDED_YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), leap)
    }

    override fun toGregorian(calendar: AltCalendar, day: AltDay): LocalDate? = runCatching {
        val c = calendar(calendar).apply {
            clear()
            set(Calendar.EXTENDED_YEAR, day.year)
            set(Calendar.MONTH, day.month - 1)
            if (calendar == AltCalendar.CHINESE) set(Calendar.IS_LEAP_MONTH, if (day.leap) 1 else 0)
            set(Calendar.DAY_OF_MONTH, day.day)
            set(Calendar.HOUR_OF_DAY, NOON)
        }
        val millis = c.timeInMillis
        // Read back: a day the year doesn't have (or a leap month it lacks) is no day at all.
        if (c.get(Calendar.MONTH) != day.month - 1 || c.get(Calendar.DAY_OF_MONTH) != day.day) return@runCatching null
        if (calendar == AltCalendar.CHINESE && (c.get(Calendar.IS_LEAP_MONTH) == 1) != day.leap) return@runCatching null
        val g = gregorian().apply { timeInMillis = millis }
        LocalDate.of(g.get(Calendar.YEAR), g.get(Calendar.MONTH) + 1, g.get(Calendar.DAY_OF_MONTH))
    }.getOrNull()

    private const val NOON = 12
}
