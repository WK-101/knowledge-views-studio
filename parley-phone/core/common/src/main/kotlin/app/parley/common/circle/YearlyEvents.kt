package app.parley.common.circle

import app.parley.common.EventDate
import java.time.LocalDate
import java.util.Locale

/**
 * R10: custom dates (new job, moved, baby) marked "remember yearly". The flags live in Parley's contact_meta row
 * (`yearlyEvents`, one key per line), never in the system contact, and follow key changes through
 * [app.parley.common.people.MetaRekey]. A key is the event's type, label and month-day, so it survives the row id
 * changing on an edit; renaming the label or moving the day clears it, which is what the user would expect.
 */
object YearlyEvents {
    /** Android's Event.TYPE_CUSTOM and TYPE_OTHER (birthdays are 3, anniversaries 1). */
    private const val TYPE_CUSTOM = 0
    private const val TYPE_OTHER = 2

    fun key(type: Int, label: String?, date: EventDate): String =
        "%d|%s|%02d-%02d".format(Locale.ROOT, type, label?.trim()?.lowercase(Locale.ROOT).orEmpty().replace('\n', ' '), date.month, date.day)

    /** Only life events can be flagged: not birthdays and anniversaries (they have their own reminders). */
    fun eligible(type: Int): Boolean = type == TYPE_CUSTOM || type == TYPE_OTHER

    fun decode(stored: String?): Set<String> = stored?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** Null when nothing is flagged (nothing to store). */
    fun encode(keys: Set<String>): String? = keys.filter { it.isNotBlank() }.sorted().joinToString("\n").ifEmpty { null }

    fun toggle(stored: String?, key: String, on: Boolean): String? = encode(if (on) decode(stored) + key else decode(stored) - key)

    /** Two rows now belong to one contact: every flag of both is kept. */
    fun merge(into: String?, from: String?): String? = encode(decode(into) + decode(from))

    /**
     * The digest line for a flagged event whose day comes within [windowDays]: how many years it will be then
     * (null when the date has no year, or it's the first year).
     */
    data class Upcoming(val lookupKey: String, val label: String, val daysUntil: Int, val years: Int?)

    fun upcoming(lookupKey: String, label: String, date: EventDate, today: LocalDate, windowDays: Int): Upcoming? {
        val until = date.daysUntil(today).toInt()
        if (until !in 0..windowDays) return null
        val years = date.year?.let { date.next(today).year - it }
        if (years != null && years < 1) return null
        return Upcoming(lookupKey, label, until, years)
    }
}
