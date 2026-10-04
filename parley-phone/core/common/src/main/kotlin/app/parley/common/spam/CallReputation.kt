package app.parley.common.spam

import app.parley.common.Codecs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.time.ZoneId

/** How one past call went, as personal reputation reads it. */
enum class RepKind {
    /** You answered ([RepCall.durationSec] is how long you talked). */
    ANSWERED,

    /** It rang and nobody answered. */
    MISSED,

    /** You declined it. */
    DECLINED,

    /** The caller left a voicemail. */
    VOICEMAIL,

    /**
     * Parley's screening or a block rule stopped it (blocked, or silenced by a rule). It says nothing about what *you* did,
     * so it never counts as unanswered: a silenced number must not keep itself silenced.
     */
    SCREENED,

    /** You called them. */
    OUTGOING,
}

/**
 * One past call with a line. [line] is its E.164 form (the caller works it out with the SIM's country); a number that
 * has none (a short code, a service code) is never passed in, so it is never scored.
 */
data class RepCall(
    val line: String,
    val time: Long,
    val kind: RepKind,
    val durationSec: Long = 0,
    /** How long it rang, when Parley's ringer recorded it; null when unknown. */
    val ringMillis: Long? = null,
)

/** What personal reputation noticed, in the order the "Why?" list shows it. */
enum class RepSignal {
    /** Hung up while it was still ringing, under 5 s ([RepReason.count] calls). */
    SHORT_RINGS,

    /** You declined every call ([RepReason.count] declines). */
    ALWAYS_DECLINED,

    /** You answered and ended it within 3 s ([RepReason.count] calls). */
    SHORT_HANGUPS,

    /** Never answered ([RepReason.count] calls). */
    NEVER_ANSWERED,

    /** Missed or declined calls, and never a voicemail ([RepReason.count] missed or declined calls). */
    NO_VOICEMAIL,

    /** Most of their calls came before 8:00 or after 21:00 ([RepReason.count] calls). */
    ODD_HOURS,

    /** [RepReason.count] different numbers from the same range called within a week. */
    RANGE_BURST,

    /** No call from the range was ever answered ([RepReason.count] calls). */
    RANGE_UNANSWERED,

    /** You blocked [RepReason.count] numbers from the same range. */
    RANGE_BLOCKED,
}

@Serializable
data class RepReason(val signal: RepSignal, val count: Int, val points: Int)

/**
 * A local score learned only from your own calls with a number or its range: [score] 0–100 and the [reasons] that add
 * up to it (each with its points), so the call screen can say why. Never sent anywhere.
 */
@Serializable
data class Reputation(val score: Int, val reasons: List<RepReason>) {
    /** "Looks like a sales line (your calls)": a high enough score from at least two different reasons. */
    val looksLikeSales: Boolean get() = score >= CallReputation.SALES_AT && reasons.size >= CallReputation.MIN_REASONS

    /** "short rings ×2, never answered ×3" for the screening trace (English, like the rest of the trace). */
    fun describe(): String = reasons.joinToString(", ") { it.signal.name.lowercase().replace('_', ' ') + " ×" + it.count }
}

/** What reputation learned: tagged lines and tagged ranges (E.164 prefixes), each with its reasons. */
data class ReputationIndex(val lines: Map<String, Reputation>, val ranges: Map<String, Reputation>) {
    /** The line's own assessment (which includes its range's), else its range's, for a number never seen before. */
    fun lookup(line: String?): Reputation? {
        if (line.isNullOrEmpty()) return null
        return lines[line] ?: CallReputation.rangeOf(line)?.let { ranges[it] }
    }

    val isEmpty: Boolean get() = lines.isEmpty() && ranges.isEmpty()

    companion object {
        val EMPTY = ReputationIndex(emptyMap(), emptyMap())
    }
}

/** "Block this range?": the narrowest prefix covering the related numbers, and what it would have matched. */
data class RangeProposal(
    /** E.164 prefix, e.g. "+3361234". */
    val prefix: String,
    /** Distinct numbers from your calls that start with [prefix]. */
    val numbers: Int,
    /** Past incoming calls from numbers starting with [prefix]. */
    val calls: Int,
)

/**
 * I2 personal reputation: a deterministic, explainable score learned only from *your* history with a number or the
 * range (the number without its last [RANGE_DROP] digits) it belongs to. Pure: the maintenance worker gathers the
 * calls, this works out which lines and ranges look like sales lines, and the call path only looks the answer up.
 *
 * False-positive guards: a line that is [known] (a contact or private contact), that you ever called, or that ever had
 * a call of [LONG_CALL_SEC] or more is never scored; a range holding any such line is never scored as a range; short
 * codes and emergency numbers never get here (no E.164 form, or [known] says so).
 */
object CallReputation {
    const val SALES_AT = 50
    const val MIN_REASONS = 2
    const val WINDOW_DAYS = 90L
    const val SHORT_RING_MS = 5_000L
    const val SHORT_CALL_SEC = 3L
    const val LONG_CALL_SEC = 60L
    const val RANGE_DROP = 3

    /** A range needs this many digits left once the last [RANGE_DROP] are dropped (country code included). */
    const val RANGE_MIN_DIGITS = 7
    const val BURST_WINDOW_MS = 7L * DAY_MS
    const val ODD_START_MINUTE = 21 * 60
    const val ODD_END_MINUTE = 8 * 60

    /** The range of an E.164 [line] ("+33612345678" → "+33612345"), or null for a number too short to have one. */
    fun rangeOf(line: String): String? {
        if (!line.startsWith("+") || line.length - 1 < RANGE_MIN_DIGITS + RANGE_DROP || line.drop(1).any { !it.isDigit() }) return null
        return line.dropLast(RANGE_DROP)
    }

    /**
     * Learns from [calls] (any order) of the last [WINDOW_DAYS] before [now]. [blockedLines] are numbers you blocked
     * (exact rules, Android's list), [known] says whether a line is a contact, a private contact or an emergency number,
     * and [knownLines] are the E.164 numbers of your contacts: a range holding one of them (a company's switchboard) is
     * never scored as a range. Only what looks like a sales line is kept.
     */
    fun index(
        calls: List<RepCall>,
        now: Long,
        zone: ZoneId,
        blockedLines: Set<String> = emptySet(),
        knownLines: Set<String> = emptySet(),
        known: (String) -> Boolean = { false },
    ): ReputationIndex {
        val recent = calls.filter { it.time in (now - WINDOW_DAYS * DAY_MS)..now && it.line.startsWith("+") }
        val byLine = recent.groupBy { it.line }
        // Trust looks at every call you have, however old: a long call or a call you made years ago still counts.
        val allByLine = calls.groupBy { it.line }
        val trusted = HashMap<String, Boolean>()
        fun isTrusted(line: String) = trusted.getOrPut(line) { trustedLine(line, allByLine[line].orEmpty(), known) }
        val byRange = byLine.keys.mapNotNull { l -> rangeOf(l)?.let { it to l } }.groupBy({ it.first }, { it.second })
        val knownRanges = knownLines.mapNotNullTo(HashSet()) { rangeOf(it) }
        val rangeSignals = HashMap<String, List<RepReason>>()
        for ((range, lines) in byRange) {
            if (range in knownRanges || lines.any { isTrusted(it) }) continue
            rangeSignals[range] = rangeReasons(lines, byLine, blockedLines.filter { rangeOf(it) == range }.toSet(), exclude = null)
        }
        val outLines = HashMap<String, Reputation>()
        for ((line, list) in byLine) {
            if (isTrusted(line)) continue
            // A line's range counts only when the range itself may be scored (nobody you know in it).
            val range = rangeOf(line)?.takeIf { it in rangeSignals }
            val rangePart = range?.let { r ->
                rangeReasons(byRange.getValue(r), byLine, blockedLines.filter { it != line && rangeOf(it) == r }.toSet(), exclude = line)
            }
            val rep = reputation(lineReasons(list, zone) + rangePart.orEmpty())
            if (rep.looksLikeSales) outLines[line] = rep
        }
        val outRanges = rangeSignals.mapValues { reputation(it.value) }.filterValues { it.looksLikeSales }
        return ReputationIndex(outLines, outRanges)
    }

    /** One line's assessment from [calls] alone (ranges are left out): for tests and a single-number check. */
    fun score(calls: List<RepCall>, zone: ZoneId, known: (String) -> Boolean = { false }): Reputation {
        val line = calls.firstOrNull()?.line ?: return Reputation(0, emptyList())
        if (trustedLine(line, calls, known)) return Reputation(0, emptyList())
        return reputation(lineReasons(calls, zone))
    }

    /**
     * The narrowest prefix covering the numbers from [line]'s range that you didn't answer, when at least two did call
     * and none of the range's numbers is one you know ([knownLines] included), called or talked to. Null otherwise.
     */
    fun proposeRange(
        line: String,
        calls: List<RepCall>,
        now: Long,
        knownLines: Set<String> = emptySet(),
        known: (String) -> Boolean = { false },
    ): RangeProposal? {
        val range = rangeOf(line) ?: return null
        if (knownLines.any { rangeOf(it) == range }) return null
        val recent = calls.filter { it.time in (now - WINDOW_DAYS * DAY_MS)..now }
        val inRange = recent.filter { rangeOf(it.line) == range }.groupBy { it.line }
        if (calls.filter { rangeOf(it.line) == range }.groupBy { it.line }.any { (l, list) -> trustedLine(l, list, known) }) return null
        val related = inRange.filter { (_, list) -> list.any { it.kind != RepKind.OUTGOING && it.kind != RepKind.SCREENED } }.keys
        if (related.size < 2 || line !in related) return null
        // At least the range itself: numbers of one range share it.
        val prefix = related.reduce { a, b -> a.commonPrefixWith(b) }
        // Everything starting with the prefix, in all your calls: what the rule would have matched.
        val matched = calls.filter { it.kind != RepKind.OUTGOING && it.line.startsWith(prefix) }
        return RangeProposal(prefix, matched.map { it.line }.distinct().size, matched.size)
    }

    private fun trustedLine(line: String, calls: List<RepCall>, known: (String) -> Boolean): Boolean =
        known(line) || calls.any { it.kind == RepKind.OUTGOING || it.durationSec >= LONG_CALL_SEC }

    private fun lineReasons(calls: List<RepCall>, zone: ZoneId): List<RepReason> {
        // What Parley's screening stopped says nothing about you: only the calls that reached you count.
        val incoming = calls.filter { it.kind != RepKind.OUTGOING && it.kind != RepKind.SCREENED }
        if (incoming.isEmpty()) return emptyList()
        return answerReasons(incoming) + habitReasons(incoming, zone)
    }

    /** How you answered (or didn't): short rings, declines, calls ended at once, never answered. */
    private fun answerReasons(incoming: List<RepCall>): List<RepReason> {
        val out = ArrayList<RepReason>()
        val answered = incoming.filter { it.kind == RepKind.ANSWERED }
        val declined = incoming.count { it.kind == RepKind.DECLINED }
        val shortRings = incoming.count { it.kind == RepKind.MISSED && it.ringMillis != null && it.ringMillis < SHORT_RING_MS }
        if (shortRings > 0) out += RepReason(RepSignal.SHORT_RINGS, shortRings, (shortRings * 15).coerceAtMost(30))
        if (declined >= 2 && answered.isEmpty()) out += RepReason(RepSignal.ALWAYS_DECLINED, declined, 25)
        val hangups = answered.count { it.durationSec <= SHORT_CALL_SEC }
        if (hangups > 0) out += RepReason(RepSignal.SHORT_HANGUPS, hangups, (hangups * 20).coerceAtMost(40))
        if (incoming.size >= 2 && answered.isEmpty()) out += RepReason(RepSignal.NEVER_ANSWERED, incoming.size, if (incoming.size >= 4) 30 else 20)
        return out
    }

    /** How they call: never a voicemail, mostly at odd hours. */
    private fun habitReasons(incoming: List<RepCall>, zone: ZoneId): List<RepReason> {
        val out = ArrayList<RepReason>()
        val unanswered = incoming.count { it.kind == RepKind.MISSED || it.kind == RepKind.DECLINED }
        if (unanswered >= 2 && incoming.none { it.kind == RepKind.VOICEMAIL }) out += RepReason(RepSignal.NO_VOICEMAIL, unanswered, 10)
        val odd = incoming.count { oddHour(it.time, zone) }
        if (odd > 0 && odd * 2 >= incoming.size) out += RepReason(RepSignal.ODD_HOURS, odd, 10)
        return out
    }

    /** The range's signals for one of its lines ([exclude], whose own calls are scored by [lineReasons]) or for the range. */
    private fun rangeReasons(lines: List<String>, byLine: Map<String, List<RepCall>>, blocked: Set<String>, exclude: String?): List<RepReason> {
        val out = ArrayList<RepReason>()
        val incoming = lines.flatMap { l -> byLine[l].orEmpty() }.filter { it.kind != RepKind.OUTGOING && it.kind != RepKind.SCREENED }
        val burst = maxDistinctInWindow(incoming)
        if (burst >= 3) out += RepReason(RepSignal.RANGE_BURST, burst, if (burst >= 5) 45 else 35)
        val others = incoming.filter { it.line != exclude }
        if (others.map { it.line }.distinct().size >= 2 && others.size >= 3 && incoming.none { it.kind == RepKind.ANSWERED }) {
            out += RepReason(RepSignal.RANGE_UNANSWERED, incoming.size, 15)
        }
        if (blocked.isNotEmpty()) out += RepReason(RepSignal.RANGE_BLOCKED, blocked.size, if (blocked.size >= 2) 30 else 20)
        return out
    }

    /** The most distinct lines calling within any [BURST_WINDOW_MS]. */
    private fun maxDistinctInWindow(calls: List<RepCall>): Int {
        val sorted = calls.sortedBy { it.time }
        var best = 0
        var start = 0
        val counts = HashMap<String, Int>()
        for (c in sorted) {
            counts[c.line] = (counts[c.line] ?: 0) + 1
            while (c.time - sorted[start].time > BURST_WINDOW_MS) {
                val l = sorted[start].line
                val n = (counts[l] ?: 1) - 1
                if (n == 0) counts.remove(l) else counts[l] = n
                start++
            }
            best = maxOf(best, counts.size)
        }
        return best
    }

    private fun oddHour(time: Long, zone: ZoneId): Boolean {
        val t = Instant.ofEpochMilli(time).atZone(zone)
        val m = t.hour * 60 + t.minute
        return m >= ODD_START_MINUTE || m < ODD_END_MINUTE
    }

    private fun reputation(reasons: List<RepReason>): Reputation =
        Reputation(reasons.sumOf { it.points }.coerceAtMost(100), reasons.sortedBy { it.signal.ordinal })

    // ---- Storage form (the store seals it; keys are already keyed fingerprints there)

    private val CODEC = Codecs.stored
    private val MAP = MapSerializer(String.serializer(), Reputation.serializer())

    fun encode(entries: Map<String, Reputation>): String = CODEC.encodeToString(MAP, entries)

    /** Reads [encode]'s output; broken input gives nothing (never a wrong tag). */
    fun decode(text: String?): Map<String, Reputation> =
        if (text.isNullOrBlank()) emptyMap() else runCatching { CODEC.decodeFromString(MAP, text) }.getOrDefault(emptyMap())
}

private const val DAY_MS = 86_400_000L
