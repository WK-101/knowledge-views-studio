package app.parley.common.people

import app.parley.common.EventDate
import java.time.LocalDate

/** One fact of the "at a glance" line under a contact's name. The app words each one. */
sealed interface GlanceFact {
    /** The last call with talk time, at [time] (epoch millis). */
    data class LastTalked(val time: Long) : GlanceFact

    /** No call with talk time yet. */
    data object NoCalls : GlanceFact

    /** The soonest date: [index] into the dates given to [ContactGlance.facts], in [days] (0 = today). */
    data class NextDate(val index: Int, val days: Long) : GlanceFact

    /** Open promises from notes about this person. */
    data class OpenPromises(val count: Int) : GlanceFact
}

/**
 * The short line under a contact's name ("Last talked 3 days ago · Birthday in 6 days · 1 open promise"). It
 * replaces small sections that only repeated one fact, so the page opens on what matters now. Always in the same
 * order, so the line reads the same from one person to the next; dates only when they're close.
 */
object ContactGlance {
    /** A date shows from this many days before it. */
    const val DATE_HORIZON_DAYS = 30L

    fun facts(
        lastTalked: Long?,
        dates: List<EventDate>,
        today: LocalDate,
        openPromises: Int,
        horizonDays: Long = DATE_HORIZON_DAYS,
    ): List<GlanceFact> = buildList {
        add(if (lastTalked != null) GlanceFact.LastTalked(lastTalked) else GlanceFact.NoCalls)
        ContactPage.nextDate(dates, today)?.takeIf { it.second <= horizonDays }?.let { (i, days) -> add(GlanceFact.NextDate(i, days)) }
        if (openPromises > 0) add(GlanceFact.OpenPromises(openPromises))
    }
}
