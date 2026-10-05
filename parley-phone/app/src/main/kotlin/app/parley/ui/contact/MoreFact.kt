package app.parley.ui.contact

import android.content.res.Resources
import app.parley.R
import app.parley.common.AltCalendar
import app.parley.common.AltCalendars
import app.parley.common.EventDate
import app.parley.common.people.Citizenship
import app.parley.common.people.Languages
import app.parley.data.ContactDetails
import app.parley.data.people.IcuCalendars
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One line of the contact page's "More" section: its value and what it is. */
internal data class MoreFact(val value: String, val label: String)

/**
 * The page's "More" section: custom fields, the languages they speak, their citizenship, RFC 9554's second surname and generation, and
 * address parts (room, floor…) another app or a card wrote. Empty for most contacts, so the section stays away.
 */
internal fun moreFacts(res: Resources, d: ContactDetails): List<MoreFact> = buildList {
    d.customFields.filterNot { it.isBlank }.forEach { f ->
        add(MoreFact(f.value.trim().ifEmpty { f.label.trim() }, f.label.trim().takeIf { f.value.isNotBlank() } ?: res.getString(R.string.detail_custom_field)))
    }
    // "Speaks Russian, English": the first is the one to use with them.
    Languages.displayList(d.languages).takeIf { it.isNotEmpty() }?.let {
        val label = if (d.languages.size > 1) R.string.detail_languages else R.string.detail_language
        add(MoreFact(res.getString(R.string.detail_speaks, it), res.getString(label)))
    }
    Citizenship.displayList(d.citizenships).takeIf { it.isNotEmpty() }?.let { add(MoreFact(it, res.getString(R.string.detail_citizenship))) }
    d.secondSurname.trim().takeIf { it.isNotEmpty() }?.let { add(MoreFact(it, res.getString(R.string.detail_second_surname))) }
    d.generation.trim().takeIf { it.isNotEmpty() }?.let { add(MoreFact(it, res.getString(R.string.detail_generation))) }
    d.addresses.forEach { a ->
        val street = a.formatted.lines().firstOrNull().orEmpty().trim()
        addressPartsText(res, a.parts)?.let { add(MoreFact(it, res.getString(R.string.detail_address_parts, street))) }
    }
}

/**
 * [date] as shown when it comes round by another calendar: "12 March 1990 · Chinese lunar · next 10 Feb 2027".
 * Null for a Gregorian date (or one without a year), which the usual description covers.
 */
internal fun describeCalendarEvent(res: Resources, date: String, calendar: String?, today: LocalDate = LocalDate.now()): String? {
    val cal = AltCalendar.byKey(calendar) ?: return null
    val e = EventDate.parse(date) ?: return null
    val y = e.year ?: return null
    val shown = runCatching { LocalDate.of(y, e.month, e.day) }.getOrNull() ?: return null
    val next = AltCalendars.next(shown, cal, today, IcuCalendars)
    val nextText = next?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    return listOfNotNull(
        shown.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)),
        nextText?.let { res.getString(R.string.detail_next_by_calendar, calendarName(res, cal), it) } ?: calendarName(res, cal),
    ).joinToString(" · ")
}

/** [date] moved to its next day by its [calendar] (see [AltCalendars.effective]); the date itself when Gregorian. */
internal fun effectiveDate(date: EventDate, calendar: String?, today: LocalDate): EventDate? =
    AltCalendars.effective(date, AltCalendar.byKey(calendar), today, IcuCalendars)
