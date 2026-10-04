package app.parley.common.calls

import java.time.Instant
import java.time.ZoneId

/**
 * "Numbers that seem out of service": a saved number whose outgoing calls keep failing because the network said the
 * number itself isn't allocated (or has changed), on more than one day, with nothing since that shows the line is
 * alive. "Move to note" takes a number off a contact, so this prefers missing a dead number to flagging a working one:
 * only those definite causes count, never a call that just failed fast or with a cause Parley doesn't know, never one
 * placed in airplane mode, and a number dialled in its national form only when the phone wasn't roaming (abroad, the
 * visited network reads "07700…" under its own numbering and answers "unassigned" for a working number). Only a
 * suggestion: Parley never changes or removes the number by itself.
 */
object DeadNumberRadar {
    /** Failures needed, ... */
    const val MIN_FAILURES = 2

    /** ... on at least this many separate days (one bad afternoon on the network isn't enough). */
    const val MIN_DAYS = 2

    /** Why the number looks out of service. */
    enum class Reason {
        /** The network said so (an unallocated or changed number). */
        NOT_IN_SERVICE,
    }

    data class Finding(
        val failures: Int,
        val days: Int,
        val firstFailureAt: Long,
        val lastFailureAt: Long,
        val reason: Reason,
    )

    /**
     * Telephony's causes that can only mean the number itself isn't in service. Wider ones (an invalid number or
     * format, no route) also come from dialling a national number abroad or from the network, so they don't count.
     */
    private val NUMBER_GONE = setOf("UNOBTAINABLE_NUMBER", "UNASSIGNED_NUMBER", "NUMBER_CHANGED")

    /** Whether [number] is written in international form ("+44…", "0044…"), which reads the same from any country. */
    fun international(number: String): Boolean {
        val t = number.trim()
        return t.startsWith("+") || t.startsWith("00")
    }

    /**
     * One outgoing call whose failure points at the number. [international]: the saved number is in international
     * form; otherwise the call only counts when it is known the phone wasn't roaming.
     */
    fun pointsAtNumber(f: CallQualityFacts, international: Boolean): Boolean {
        if (f.incoming || f.connected) return false
        if (f.drop != null || f.offline) return false
        if (f.cause?.uppercase() !in NUMBER_GONE) return false
        return international || f.roaming == false
    }

    /** As [pointsAtNumber] for any spelling of the number: a first sieve before the numbers are known. */
    fun mayPointAtNumber(f: CallQualityFacts): Boolean = pointsAtNumber(f, international = true)

    /** A call that shows the line works: any call from it, or a call to it that connected. */
    fun showsAlive(f: CallQualityFacts): Boolean = f.incoming || f.connected

    /**
     * The finding for one [number] from its quality facts, or null. [lastAliveElsewhere]: the latest call in the call
     * history that shows the line works (calls placed before Parley kept facts, or by another app). [dismissedAt]:
     * when "Dismiss" was last tapped for it; only a failure after that brings it back.
     */
    fun check(facts: List<CallQualityFacts>, number: String, zone: ZoneId, lastAliveElsewhere: Long? = null, dismissedAt: Long? = null): Finding? {
        val intl = international(number)
        val alive = maxOf(facts.filter(::showsAlive).maxOfOrNull { it.startedAt } ?: Long.MIN_VALUE, lastAliveElsewhere ?: Long.MIN_VALUE)
        val failures = facts.filter { it.startedAt > alive && pointsAtNumber(it, intl) }
        if (failures.size < MIN_FAILURES) return null
        val days = failures.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }.toSet().size
        if (days < MIN_DAYS) return null
        val last = failures.maxOf { it.startedAt }
        if (dismissedAt != null && last <= dismissedAt) return null
        return Finding(failures.size, days, failures.minOf { it.startedAt }, last, Reason.NOT_IN_SERVICE)
    }
}
