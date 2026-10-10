package app.parley.common.calls

import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.IdentityHashMap

/** How a call was carried, as far as Telecom said: over Wi-Fi calling at some point, or only the mobile network. */
enum class CallNetwork { WIFI, MOBILE }

/** A part of the day. The diary uses it instead of places: Parley has no location, and "evening" is often "at home". */
enum class DayPart {
    MORNING, AFTERNOON, EVENING, NIGHT;

    companion object {
        fun of(hour: Int): DayPart = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> NIGHT
        }
    }
}

/** What the diary suggests for a pattern. */
enum class QualityAdvice {
    /** Calls on the mobile network drop: Wi-Fi calling may hold where the signal doesn't. */
    TRY_WIFI_CALLING,

    /** Wi-Fi calling drops: the mobile network may do better. */
    TRY_MOBILE_NETWORK,

    /** This SIM drops where the other one ([QualityPattern.otherSim]) holds. */
    TRY_OTHER_SIM,
}

/** One call for the diary: its facts and an opaque key for who it was with (null: hidden or unknown to the app). */
data class DiaryCall(val who: String?, val facts: CallQualityFacts)

/** Connected calls and how many of them dropped. */
data class DropRate(val calls: Int, val drops: Int) {
    val rate: Double get() = if (calls == 0) 0.0 else drops.toDouble() / calls

    /** Whole per cent, for "4 %". */
    val percent: Int get() = Math.round(rate * 100).toInt()
}

/**
 * "Calls with Mum drop on SIM 2 in the evening": the calls that share [who], [sim], [network] and [dayPart] (null: any)
 * drop far more often than the others. [advice] says what might help.
 */
data class QualityPattern(
    val who: String?,
    val sim: String?,
    val network: CallNetwork?,
    val dayPart: DayPart?,
    val rate: DropRate,
    val advice: QualityAdvice,
    /** The SIM to try instead, with [QualityAdvice.TRY_OTHER_SIM]. */
    val otherSim: String? = null,
)

/** The Call insights "Quality" card. Rates count connected calls only (a call that never connected can't drop). */
data class QualityReport(
    val overall: DropRate,
    /** Per SIM, only on phones where calls went over more than one SIM. */
    val bySim: Map<String, DropRate>,
    /** Per network, only once Wi-Fi calling carried a call (otherwise it would just repeat [overall]). */
    val byNetwork: Map<CallNetwork, DropRate>,
    val patterns: List<QualityPattern>,
    /** The latest dropped calls with someone the app can call again, newest first. */
    val recentDrops: List<DiaryCall>,
)

/** The number history's quality line: "7 calls · 2 dropped, both on Work · 3 over Wi-Fi calling". */
data class NumberQuality(
    val rate: DropRate,
    /** The SIM every drop was on, when there are several SIMs and all drops share one. */
    val dropSim: String?,
    val wifiCalls: Int,
    val hdCalls: Int,
)

/**
 * The call quality diary: drop rates per SIM and per network, patterns worth acting on, and the recent drops, from
 * the quality facts Parley keeps per call ([CallQualityFacts], 60 days). Patterns use only the person, the SIM, Wi-Fi
 * calling and the part of the day: Parley never knows where the phone was.
 */
object CallQualityDiary {
    /** A pattern needs this many connected calls and drops before it says anything. */
    const val MIN_CALLS = 3
    const val MIN_DROPS = 2

    /** ... a drop rate of at least this ... */
    private const val MIN_RATE = 0.34

    /** ... and at least this many times the rate of the other calls. */
    private const val RATE_FACTOR = 2.0

    /** The other calls need this many calls before a comparison means anything. */
    private const val MIN_REST = 3

    private const val MAX_PATTERNS = 3
    private const val MAX_RECENT = 5

    /** The report, or null with fewer than [MIN_CALLS] connected calls in the last [days] days. */
    fun report(calls: List<DiaryCall>, zone: ZoneId, now: Long, days: Int = 60): QualityReport? {
        val since = now - days * DAY_MS
        val connected = calls.filter { it.facts.connected && it.facts.startedAt >= since && it.facts.startedAt <= now + DAY_MS }
        if (connected.size < MIN_CALLS) return null
        val sims = connected.mapNotNull { it.facts.sim }.toSet()
        val bySim = if (sims.size < 2) emptyMap() else sims.associateWith { s -> rateOf(connected.filter { it.facts.sim == s }) }
        val byNetwork = if (connected.none { it.facts.wifi }) emptyMap()
        else CallNetwork.entries.associateWith { n -> rateOf(connected.filter { network(it) == n }) }
        return QualityReport(
            overall = rateOf(connected),
            bySim = bySim,
            byNetwork = byNetwork,
            patterns = patterns(connected, zone, multiSim = sims.size >= 2),
            recentDrops = connected.filter { it.facts.drop != null && it.who != null }.sortedByDescending { it.facts.startedAt }.take(MAX_RECENT),
        )
    }

    /** The number history's line, or null with fewer than two connected calls. */
    fun numberLine(facts: List<CallQualityFacts>): NumberQuality? {
        val connected = facts.filter { it.connected }
        if (connected.size < 2) return null
        val drops = connected.filter { it.drop != null }
        val multiSim = connected.mapNotNull { it.sim }.toSet().size >= 2
        val dropSims = drops.map { it.sim }.toSet()
        return NumberQuality(
            rate = DropRate(connected.size, drops.size),
            dropSim = dropSims.singleOrNull()?.takeIf { multiSim && drops.size >= 2 },
            wifiCalls = connected.count { it.wifi },
            hdCalls = connected.count { it.hd },
        )
    }

    private fun rateOf(calls: List<DiaryCall>) = DropRate(calls.size, calls.count { it.facts.drop != null })

    private fun network(c: DiaryCall) = if (c.facts.wifi) CallNetwork.WIFI else CallNetwork.MOBILE

    /** The values of one call along the four dimensions. */
    private data class Key(val who: String?, val sim: String?, val network: CallNetwork?, val dayPart: DayPart?)

    private enum class Dim { WHO, SIM, NETWORK, DAY_PART }

    private class Candidate(val key: Key, val calls: List<DiaryCall>, val rate: DropRate, val dims: Int) {
        /** The calls by identity (data-class hashing of every call, again and again, made the search slow). */
        val members: Set<DiaryCall> = identitySet(calls)
        val drops: List<DiaryCall> = calls.filter { it.facts.drop != null }
    }

    private fun identitySet(calls: Collection<DiaryCall>): MutableSet<DiaryCall> =
        Collections.newSetFromMap(IdentityHashMap<DiaryCall, Boolean>(calls.size * 2)).apply { addAll(calls) }

    /** The calls grouped by their values along [subset]; calls missing one of them (no person, no SIM) are left out. */
    private fun groups(calls: List<DiaryCall>, subset: Set<Dim>, part: Map<DiaryCall, DayPart>): Map<Key, List<DiaryCall>> {
        val out = LinkedHashMap<Key, MutableList<DiaryCall>>()
        for (c in calls) {
            val who = if (Dim.WHO in subset) c.who else null
            val sim = if (Dim.SIM in subset) c.facts.sim else null
            val missing = (Dim.WHO in subset && who == null) || (Dim.SIM in subset && sim == null)
            if (missing) continue
            val key = Key(
                who = who,
                sim = sim,
                network = if (Dim.NETWORK in subset) network(c) else null,
                dayPart = if (Dim.DAY_PART in subset) part.getValue(c) else null,
            )
            out.getOrPut(key) { ArrayList() } += c
        }
        return out
    }

    /** [key] without [dim]: the wider group a pattern's part narrows. */
    private fun Key.without(dim: Dim): Key = when (dim) {
        Dim.WHO -> copy(who = null)
        Dim.SIM -> copy(sim = null)
        Dim.NETWORK -> copy(network = null)
        Dim.DAY_PART -> copy(dayPart = null)
    }

    /** Drops often: at least [MIN_DROPS] of [MIN_CALLS], [MIN_RATE], and [RATE_FACTOR] times the other calls' rate. */
    private fun dropsOften(rate: DropRate, rest: DropRate): Boolean =
        rate.calls >= MIN_CALLS && rate.drops >= MIN_DROPS && rate.rate >= MIN_RATE &&
            rest.calls >= MIN_REST && rate.rate >= RATE_FACTOR * rest.rate

    /**
     * Every group of calls sharing up to three of person, SIM, network and part of the day that [dropsOften]. The most
     * drops win, then the higher rate, then the simpler one; each part has to narrow the group, and a group whose drops
     * are mostly an earlier pattern's says nothing new.
     */
    private fun patterns(calls: List<DiaryCall>, zone: ZoneId, multiSim: Boolean): List<QualityPattern> {
        val dims = buildList {
            add(Dim.WHO)
            if (multiSim) add(Dim.SIM)
            if (calls.any { it.facts.wifi }) add(Dim.NETWORK)
            add(Dim.DAY_PART)
        }
        val part = IdentityHashMap<DiaryCall, DayPart>(calls.size * 2)
        calls.forEach { part[it] = DayPart.of(Instant.ofEpochMilli(it.facts.startedAt).atZone(zone).hour) }
        // The other calls' rate is the total less the group's, never a list rebuilt (and rehashed) per group.
        val total = rateOf(calls)
        val grouped = subsets(dims).associateWith { groups(calls, it, part) }

        // Each part of a pattern has to narrow it: "Calls with Mum in the morning" says nothing more than "Calls with
        // Mum" when every call with Mum was in the morning.
        fun narrows(subset: Set<Dim>, key: Key, size: Int): Boolean = subset.all { dim ->
            val up = subset - dim
            val wider = if (up.isEmpty()) calls.size else grouped[up]?.get(key.without(dim))?.size ?: 0
            wider > size
        }
        val candidates = grouped.flatMap { (subset, groups) ->
            groups.mapNotNull { (key, group) ->
                val rate = rateOf(group)
                val rest = DropRate(total.calls - rate.calls, total.drops - rate.drops)
                val fits = dropsOften(rate, rest) && narrows(subset, key, group.size)
                if (fits) Candidate(key, group, rate, subset.size) else null
            }
        }
        val ordered = candidates.sortedWith(compareByDescending<Candidate> { it.rate.drops }.thenByDescending { it.rate.rate }.thenBy { it.dims })
        val chosen = ArrayList<Candidate>()
        for (c in ordered) {
            if (chosen.size >= MAX_PATTERNS) break
            val repeats = chosen.any { p -> c.drops.count { it in p.members } * 2 > c.drops.size }
            if (!repeats && chosen.size < MAX_PATTERNS) chosen += c
        }
        return chosen.map { c -> c.toPattern(calls) }
    }

    private fun Candidate.toPattern(all: List<DiaryCall>): QualityPattern {
        val drops = calls.filter { it.facts.drop != null }
        val sim = key.sim ?: drops.map { it.facts.sim }.toSet().singleOrNull()
        // Another SIM that holds clearly better, with enough calls to say so.
        val other = sim?.let { s ->
            all.mapNotNull { it.facts.sim }.toSet().filter { it != s }
                .map { o -> o to rateOf(all.filter { it.facts.sim == o }) }
                .filter { (_, r) -> r.calls >= MIN_CALLS && r.rate * RATE_FACTOR <= rate.rate }
                .minByOrNull { (_, r) -> r.rate }?.first
        }
        val wifiDrops = key.network == CallNetwork.WIFI || (key.network == null && drops.all { it.facts.wifi })
        val advice = when {
            wifiDrops -> QualityAdvice.TRY_MOBILE_NETWORK
            other != null -> QualityAdvice.TRY_OTHER_SIM
            else -> QualityAdvice.TRY_WIFI_CALLING
        }
        return QualityPattern(key.who, key.sim, key.network, key.dayPart, rate, advice, other.takeIf { advice == QualityAdvice.TRY_OTHER_SIM })
    }

    /** The non-empty subsets of [dims] with at most three members. */
    private fun subsets(dims: List<Dim>): List<Set<Dim>> {
        val out = ArrayList<Set<Dim>>()
        for (mask in 1 until (1 shl dims.size)) {
            val s = dims.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            if (s.size <= 3) out += s
        }
        return out
    }

    private const val DAY_MS = 86_400_000L
}
