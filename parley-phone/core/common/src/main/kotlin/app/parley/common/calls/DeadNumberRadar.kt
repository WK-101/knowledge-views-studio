package app.parley.common.calls

import java.time.Instant
import java.time.ZoneId

/**
 * "Numbers that seem out of service": a saved number whose outgoing calls keep failing in a way that points at the
 * number itself (the network said it isn't allocated or isn't valid, or the call failed within seconds for no reason on
 * this phone's side), on more than one day, with nothing since that shows the line is alive. Only a suggestion: Parley
 * never changes or removes the number by itself.
 */
object DeadNumberRadar {
    /** Failures needed, ... */
    const val MIN_FAILURES = 2

    /** ... on at least this many separate days (one bad afternoon on the network isn't enough). */
    const val MIN_DAYS = 2

    /** An outgoing call that failed within this many seconds without a cause on the phone's side counts too. */
    const val SHORT_FAILURE_SEC = 5L

    /** Why the number looks out of service. */
    enum class Reason {
        /** The network said so (unallocated or invalid number). */
        NOT_IN_SERVICE,

        /** Calls to it failed within seconds each time. */
        FAILS_AT_ONCE,
    }

    data class Finding(
        val failures: Int,
        val days: Int,
        val firstFailureAt: Long,
        val lastFailureAt: Long,
        val reason: Reason,
    )

    /** Telephony's causes that mean the number itself isn't in service (or never was a valid number). */
    private val NUMBER_GONE = setOf(
        "UNOBTAINABLE_NUMBER", "INVALID_NUMBER", "UNASSIGNED_NUMBER", "NUMBER_CHANGED", "INVALID_NUMBER_FORMAT", "NO_ROUTE_TO_DESTINATION",
    )

    /**
     * Causes on this phone's side or the network's (no signal, no SIM, barred, congestion, a limit…): a quick failure
     * with one of these says nothing about the number.
     */
    private val PHONE_SIDE = setOf(
        "OUT_OF_SERVICE", "POWER_OFF", "LOST_SIGNAL", "CDMA_DROP", "WIFI_LOST", "ICC_ERROR", "CONGESTION", "TIMED_OUT",
        "OUT_OF_NETWORK", "SERVER_UNREACHABLE", "SERVER_ERROR", "EMERGENCY_ONLY", "CALL_BARRED", "FDN_BLOCKED",
        "CS_RESTRICTED", "CS_RESTRICTED_NORMAL", "CS_RESTRICTED_EMERGENCY", "LIMIT_EXCEEDED", "DATA_DISABLED",
        "DATA_LIMIT_REACHED", "LOW_BATTERY", "DIAL_LOW_BATTERY", "IMS_ACCESS_BLOCKED", "DIALED_ON_WRONG_SLOT",
        "MAXIMUM_NUMBER_OF_CALLS_REACHED", "NO_PHONE_NUMBER_SUPPLIED", "OUTGOING_FAILURE", "OUTGOING_CANCELED",
        "BUSY", "INCOMING_MISSED", "INCOMING_REJECTED", "LOCAL", "NORMAL", "MMI", "IMEI_NOT_ACCEPTED", "INVALID_CREDENTIALS",
        "VOICEMAIL_NUMBER_MISSING", "RADIO_OFF", "SATELLITE_ENABLED",
    )

    /** One outgoing call whose failure points at the number. */
    fun pointsAtNumber(f: CallQualityFacts): Boolean {
        if (f.incoming || f.connected || f.drop != null) return false
        val cause = f.cause?.uppercase()
        if (cause != null && cause in NUMBER_GONE) return true
        val quick = f.endedAfterSec?.let { it <= SHORT_FAILURE_SEC } == true
        return quick && f.end == EndCode.ERROR && (cause == null || cause !in PHONE_SIDE)
    }

    /** A call that shows the line works: any call from it, or a call to it that connected. */
    fun showsAlive(f: CallQualityFacts): Boolean = f.incoming || f.connected

    /**
     * The finding for one number from its quality facts, or null. [lastAliveElsewhere]: the latest call in the call
     * history that shows the line works (calls placed before Parley kept facts, or by another app). [dismissedAt]:
     * when "Dismiss" was last tapped for it; only a failure after that brings it back.
     */
    fun check(facts: List<CallQualityFacts>, zone: ZoneId, lastAliveElsewhere: Long? = null, dismissedAt: Long? = null): Finding? {
        val alive = maxOf(facts.filter(::showsAlive).maxOfOrNull { it.startedAt } ?: Long.MIN_VALUE, lastAliveElsewhere ?: Long.MIN_VALUE)
        val failures = facts.filter { it.startedAt > alive && pointsAtNumber(it) }
        if (failures.size < MIN_FAILURES) return null
        val days = failures.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }.toSet().size
        if (days < MIN_DAYS) return null
        val last = failures.maxOf { it.startedAt }
        if (dismissedAt != null && last <= dismissedAt) return null
        val gone = failures.any { it.cause?.uppercase() in NUMBER_GONE }
        return Finding(failures.size, days, failures.minOf { it.startedAt }, last, if (gone) Reason.NOT_IN_SERVICE else Reason.FAILS_AT_ONCE)
    }
}
