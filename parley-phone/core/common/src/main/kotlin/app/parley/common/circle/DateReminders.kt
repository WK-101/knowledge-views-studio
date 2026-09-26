package app.parley.common.circle

import app.parley.common.EventDate
import java.time.LocalDate
import java.util.Locale

/**
 * R5: birthday and date reminders. Each occasion fires at most twice: once on the lead day (1, 3 or 7 days before,
 * if chosen) and once on the day, never daily. "Mark as wished" closes the occasion so nothing more fires for it.
 */
object DateReminders {
    enum class Fire { LEAD, ON_DAY }

    /** What fires [today] for [date] with [leadDays] of notice, or null. */
    fun fire(date: EventDate, today: LocalDate, leadDays: Int): Fire? {
        val until = date.daysUntil(today)
        return when {
            until == 0L -> Fire.ON_DAY
            leadDays > 0 && until == leadDays.toLong() -> Fire.LEAD
            else -> null
        }
    }

    /**
     * Stable id of one event of a contact (type, month-day and label; Android's data row ids change on edits). The
     * label (normalised like [YearlyEvents.key], as a short hash) keeps two custom events on one day apart (G5);
     * events without a label keep the key they always had.
     */
    fun eventKey(type: Int, date: EventDate, label: String? = null): String {
        val base = "%d-%02d%02d".format(Locale.ROOT, type, date.month, date.day)
        val norm = label?.trim()?.lowercase(Locale.ROOT)?.replace('\n', ' ').orEmpty()
        return if (norm.isEmpty()) base else base + "-" + Integer.toHexString(norm.hashCode())
    }

    /**
     * One occasion: this event in the year it next falls on. The lead-day and on-the-day reminders share it, also
     * across New Year (a 3-day lead on 29 Dec for 1 Jan).
     */
    fun occurrence(contactId: Long, eventKey: String, date: EventDate, today: LocalDate): String = "$contactId:$eventKey:${date.next(today).year}"

    /** G5: notification tag of one event (id 0), so two dates of one person never replace each other. */
    fun tag(contactId: Long, eventKey: String): String = "birthday:$contactId:$eventKey"

    /** G5: notification tag of a person's keep-in-touch reminder (id 0). */
    fun nudgeTag(contactId: Long): String = "nudge:$contactId"

    /** Tag of the weekly digest (id 0). */
    const val DIGEST_TAG = "circle:digest"

    /**
     * Occasions remembered as fired or wished are kept for a while and then forgotten; entries look like
     * "<occurrence>|<time>". Returns the entries still younger than [keepMs].
     */
    fun prune(entries: Collection<String>, now: Long, keepMs: Long = 400L * NaturalRhythm.DAY): Set<String> =
        entries.filter { e -> e.substringAfterLast('|').toLongOrNull()?.let { now - it < keepMs } ?: false }.toSet()

    fun has(entries: Collection<String>, key: String): Boolean = entries.any { it.substringBeforeLast('|') == key }
}
