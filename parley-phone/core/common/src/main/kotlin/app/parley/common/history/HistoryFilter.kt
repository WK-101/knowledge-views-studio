package app.parley.common.history

import app.parley.common.CallEntry
import app.parley.common.CallType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Time window of a [HistoryFilter], relative to "now". */
enum class FilterPeriod {
    ANY,
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    THIS_MONTH,
    LAST_90_DAYS,
    THIS_YEAR,
    ;

    /** Start of the window in epoch millis (inclusive); [Long.MIN_VALUE] for [ANY]. */
    fun since(now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        return when (this) {
            ANY -> Long.MIN_VALUE
            TODAY -> start(today)
            LAST_7_DAYS -> start(today.minusDays(6))
            LAST_30_DAYS -> start(today.minusDays(29))
            THIS_MONTH -> start(today.withDayOfMonth(1))
            LAST_90_DAYS -> start(today.minusDays(89))
            THIS_YEAR -> start(today.withDayOfYear(1))
        }
    }
}

/** Groups of call types a filter can pick (labels are app resources). */
enum class TypeGroup(val types: Set<CallType>) {
    INCOMING(setOf(CallType.INCOMING, CallType.ANSWERED_EXTERNALLY)),
    OUTGOING(setOf(CallType.OUTGOING)),
    MISSED(setOf(CallType.MISSED)),
    REJECTED(setOf(CallType.REJECTED)),
    BLOCKED(setOf(CallType.BLOCKED)),
    VOICEMAIL(setOf(CallType.VOICEMAIL)),
}

/**
 * A Recents filter (SIM + call types + period + duration), optionally saved under a [name] and shown as a
 * chip. An empty filter matches everything.
 */
@Serializable
data class HistoryFilter(
    val name: String = "",
    /** Empty = every type. */
    val types: Set<TypeGroup> = emptySet(),
    /** Phone account id; null = every SIM. */
    val simId: String? = null,
    val period: FilterPeriod = FilterPeriod.ANY,
    /** Minimum talk time in seconds (inclusive); null = no minimum. */
    val minDurationSec: Long? = null,
    /** Maximum talk time in seconds (inclusive); null = no maximum. */
    val maxDurationSec: Long? = null,
) {
    /** True when the filter doesn't restrict anything (the name doesn't count). */
    val isEmpty: Boolean get() = sameCriteria(HistoryFilter())

    /** Equal ignoring the name: "Apply" is only enabled when this is false against the active filter. */
    fun sameCriteria(o: HistoryFilter): Boolean =
        types == o.types && simId == o.simId && period == o.period && minDurationSec == o.minDurationSec && maxDurationSec == o.maxDurationSec

    /** A predicate for many calls: resolves "now" once. */
    fun matcher(now: Long, zone: ZoneId): (CallEntry) -> Boolean {
        if (isEmpty) return { true }
        val since = period.since(now, zone)
        val allowed = types.flatMap { it.types }.toSet()
        return { e ->
            (allowed.isEmpty() || e.type in allowed) &&
                (simId == null || e.accountId == simId) &&
                e.date >= since &&
                (minDurationSec == null || e.durationSec >= minDurationSec) &&
                (maxDurationSec == null || e.durationSec <= maxDurationSec)
        }
    }

    fun matches(e: CallEntry, now: Long, zone: ZoneId): Boolean = matcher(now, zone)(e)


    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val listSerializer = ListSerializer(serializer())

        fun encodeList(list: List<HistoryFilter>): String = json.encodeToString(listSerializer, list)

        /** Tolerates garbage (returns an empty list) so a bad preference never breaks Recents. */
        fun decodeList(s: String?): List<HistoryFilter> =
            if (s.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(listSerializer, s) }.getOrDefault(emptyList())

        fun encode(f: HistoryFilter): String = json.encodeToString(serializer(), f)
        fun decode(s: String?): HistoryFilter = if (s.isNullOrBlank()) HistoryFilter() else runCatching { json.decodeFromString(serializer(), s) }.getOrDefault(HistoryFilter())
    }
}
