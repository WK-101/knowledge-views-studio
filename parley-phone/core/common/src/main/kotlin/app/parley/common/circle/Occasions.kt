package app.parley.common.circle

import app.parley.common.AltCalendar
import app.parley.common.AltCalendars
import app.parley.common.CalendarConverter
import app.parley.common.EventDate
import app.parley.common.people.LifeEvents
import java.time.LocalDate

/** A birthday or an anniversary that falls today. */
enum class Occasion { BIRTHDAY, ANNIVERSARY }

/**
 * Today's occasion for one person, from their dates as the address book keeps them: the call screen's quiet "Birthday
 * today" line and Next up's "Ana's birthday today". A date kept in another calendar (Hijri, Hebrew, Chinese) counts on
 * the Gregorian day it falls on this year, as the date reminders count it. Never for someone who has died: the date
 * stays on their page, but nothing calls it out.
 */
object Occasions {
    /** Android's Event.TYPE_ANNIVERSARY and TYPE_BIRTHDAY. */
    const val TYPE_ANNIVERSARY = 1
    const val TYPE_BIRTHDAY = 3

    /** One date: Android's event [type] and [label], the stored [date] and its calendar's key, if not Gregorian. */
    data class Dated(val type: Int, val label: String?, val date: String, val calendar: String? = null)

    /** The occasion on [today] among [dates], a birthday before an anniversary; null when none falls today. */
    fun today(dates: List<Dated>, today: LocalDate, converter: CalendarConverter?): Occasion? {
        if (dates.any { LifeEvents.isDeath(it.type, it.label) }) return null
        fun falls(d: Dated): Boolean {
            val parsed = EventDate.parse(d.date) ?: return false
            val due = AltCalendars.due(parsed, AltCalendar.byKey(d.calendar), today, converter) ?: return false
            return due.date.daysUntil(today) == 0L
        }
        return when {
            dates.any { it.type == TYPE_BIRTHDAY && falls(it) } -> Occasion.BIRTHDAY
            dates.any { it.type == TYPE_ANNIVERSARY && falls(it) } -> Occasion.ANNIVERSARY
            else -> null
        }
    }
}
