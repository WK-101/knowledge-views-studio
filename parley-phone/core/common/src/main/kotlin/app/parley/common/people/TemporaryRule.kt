package app.parley.common.people

import app.parley.common.cases.CaseFile
import app.parley.common.cases.CaseStatus

/** What a temporary contact's date counts from. */
enum class TemporaryClock {
    /** A fixed date: some days after it was saved (as temporary contacts always were). */
    FIXED,

    /** Some days after your last call with them (or a chat started from Parley): each one moves the date on. */
    LAST_CALL,

    /** When the case file kept for their number is closed. */
    CASE_CLOSED,
}

/**
 * How a temporary contact's date moves, stored with its entry: [slideDays] when it counts from the last call, [untilCase]
 * when it waits for its case file. Neither is a fixed date, the only kind before these existed; older versions read only
 * the date, which keeps working there as a fixed one.
 *
 * The stored date stays the one every other rule reads (due, "Keep 7 more days", the list's order): the daily upkeep
 * only moves it later ([follow]), never earlier, so "Ask before deleting" and the undo work exactly as they did.
 */
data class TemporaryRule(val slideDays: Int? = null, val untilCase: Boolean = false) {
    val clock: TemporaryClock get() = when {
        untilCase -> TemporaryClock.CASE_CLOSED
        slideDays != null -> TemporaryClock.LAST_CALL
        else -> TemporaryClock.FIXED
    }

    /** Where its case file stands, as the upkeep finds it. */
    enum class CaseNow {
        /** Still open (or waiting on them). */
        OPEN,

        /** Resolved, stopped, or no longer kept for their number. */
        CLOSED,

        /** The case files can't be read right now: nothing is decided on that. */
        UNKNOWN,
    }

    companion object {
        val FIXED = TemporaryRule()

        /** While a case is open (or can't be read) the date is kept this far ahead, so it is never due in between upkeeps. */
        const val CASE_HOLD_DAYS = 2

        /** The rule for a [clock] picked with [days] (days are a fixed or sliding length; a case needs none). */
        fun of(clock: TemporaryClock, days: Int): TemporaryRule = when (clock) {
            TemporaryClock.FIXED -> FIXED
            TemporaryClock.LAST_CALL -> TemporaryRule(slideDays = days.coerceIn(1, TemporaryChoice.MAX_DAYS))
            TemporaryClock.CASE_CLOSED -> TemporaryRule(untilCase = true)
        }

        /** Stored values read leniently: an unknown or out-of-range number of days is a fixed date. */
        fun stored(slideDays: Int?, untilCase: Boolean): TemporaryRule =
            TemporaryRule(slideDays?.takeIf { it in 1..TemporaryChoice.MAX_DAYS }, untilCase)

        /** The first date for a contact made temporary at [now]. */
        fun firstExpiry(clock: TemporaryClock, days: Int, now: Long): Long = when (clock) {
            TemporaryClock.CASE_CLOSED -> now + CASE_HOLD_DAYS * TemporaryChoice.DAY_MS
            else -> now + days * TemporaryChoice.DAY_MS
        }

        /** [case]'s state for the rule; null (no case for their number any more) counts as closed. */
        fun caseNow(case: CaseFile?): CaseNow = when {
            case == null || !case.kept || case.status == CaseStatus.RESOLVED -> CaseNow.CLOSED
            else -> CaseNow.OPEN
        }

        /** Whether "When this case is closed" may be offered for [case]: one that is kept and not closed yet. */
        fun offersCase(case: CaseFile?): Boolean = case != null && caseNow(case) == CaseNow.OPEN

        /**
         * The date to store at [now] for an entry dated [stored] with [rule]: [lastContact] is their newest connected
         * call or chat started from Parley (null: none), [case] where their case file stands. Only ever later than
         * [stored]: a fixed date, a closed case or an old call leave it as it is, so a date you extended stays.
         */
        fun follow(stored: Long, rule: TemporaryRule, lastContact: Long?, case: CaseNow, now: Long): Long {
            val wanted = when (rule.clock) {
                TemporaryClock.FIXED -> stored
                TemporaryClock.LAST_CALL -> {
                    val days = rule.slideDays ?: 0
                    lastContact?.let { it + days * TemporaryChoice.DAY_MS } ?: stored
                }
                TemporaryClock.CASE_CLOSED ->
                    if (case == CaseNow.CLOSED) stored else now + CASE_HOLD_DAYS * TemporaryChoice.DAY_MS
            }
            return maxOf(stored, wanted)
        }
    }
}
