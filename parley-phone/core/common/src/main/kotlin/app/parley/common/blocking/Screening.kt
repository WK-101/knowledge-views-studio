package app.parley.common.blocking

import app.parley.common.BlockRule
import app.parley.common.CallEntry
import app.parley.common.CallPolicy
import app.parley.common.CallType
import app.parley.common.IncomingCallFacts
import app.parley.common.LineType
import app.parley.common.PhoneNumbers
import app.parley.common.PolicyClock
import app.parley.common.ScreeningResult
import app.parley.common.ScreeningSettings
import java.time.ZoneId

/**
 * Everything screening does besides deciding: hit counters, the screened-call log, notifications.
 * Real screening passes an implementation; the dry run never sees one, so it can't have side effects
 * (SpamBlocker's "simulation" sent real SMS and reports).
 */
interface ScreeningEffects {
    fun onScreened(facts: IncomingCallFacts, result: ScreeningResult)
}

class ScreeningPipeline(private val effects: ScreeningEffects) {

    /** A live call: decide, then hand the result to the effects (which must not block the caller). */
    fun screen(facts: IncomingCallFacts, rules: List<BlockRule>, settings: ScreeningSettings, clock: PolicyClock): ScreeningResult {
        val r = CallPolicy.decide(facts, rules, settings, clock)
        effects.onScreened(facts, r)
        return r
    }

    /** Test one call ("Test this call"): the same decision, no effects. */
    fun test(facts: IncomingCallFacts, rules: List<BlockRule>, settings: ScreeningSettings, clock: PolicyClock): ScreeningResult =
        CallPolicy.decide(facts, rules, settings, clock)

    /** Replays past calls against rules and settings (B13). Pure: no effects are touched. */
    fun dryRun(
        calls: List<ReplayCall>,
        rules: List<BlockRule>,
        settings: ScreeningSettings,
        zone: ZoneId = ZoneId.systemDefault(),
        factsFor: (ReplayCall) -> IncomingCallFacts,
    ): ReplayReport {
        val results = calls.map { c -> ReplayResult(c, CallPolicy.decide(factsFor(c), rules, settings.copy(snoozeUntil = 0), PolicyClock.of(c.time, zone))) }
        return ReplayReport(results)
    }
}

/** A past incoming call, as the dry run sees it. */
data class ReplayCall(val number: String, val time: Long, val type: CallType, val hidden: Boolean, val durationSec: Long = 0, val isContact: Boolean = false)

data class ReplayResult(val call: ReplayCall, val result: ScreeningResult)

data class ReplayReport(val results: List<ReplayResult>) {
    val unknown: List<ReplayResult> get() = results.filter { !it.call.isContact }
    val blocked: Int get() = unknown.count { it.result.blocked }

    /** "Current rules would have blocked 14 of 22 unknown calls." */
    fun summary(): String = if (unknown.isEmpty()) "No calls from unknown numbers in this period" else
        "Would have blocked $blocked of ${unknown.size} calls from unknown numbers"

    /** Calls blocked here but not in [baseline]: what a new rule or list would add. */
    fun newlyBlocked(baseline: ReplayReport): List<ReplayResult> {
        val before = baseline.results.associate { (it.call.time to it.call.number) to it.result.blocked }
        return results.filter { it.result.blocked && before[it.call.time to it.call.number] != true }
    }
}

/** B10: one-ring missed calls from abroad or premium lines ("wangiri") are a call-back scam. */
object WangiriGuard {
    /** About one ring. */
    const val ONE_RING_MS = 6_000L

    fun isSuspect(type: CallType, ringMillis: Long?, lineType: LineType, region: String?, homeRegion: String?): Boolean {
        if (type != CallType.MISSED && type != CallType.REJECTED) return false
        val shortRing = ringMillis != null && ringMillis <= ONE_RING_MS
        val costly = lineType == LineType.PREMIUM_RATE || lineType == LineType.SHARED_COST
        val foreign = region != null && homeRegion != null && !region.equals(homeRegion, ignoreCase = true)
        return (costly && (ringMillis == null || shortRing)) || (foreign && shortRing)
    }
}

/** B12: a local "likely spam for you" score from how you treat a number, with a 1-hour regret window. */
object PersonalReputation {
    const val REGRET_WINDOW_MS = 60 * 60 * 1000L
    const val SHORT_CALL_SEC = 3L
    const val SUGGEST_AT = 50

    data class Suggestion(val number: String, val score: Int, val rejected: Int, val shortAnswered: Int, val lastAt: Long) {
        fun reason(): String = listOfNotNull(
            rejected.takeIf { it > 0 }?.let { "you declined $it ${if (it == 1) "call" else "calls"}" },
            shortAnswered.takeIf { it > 0 }?.let { "$it ${if (it == 1) "call" else "calls"} ended within 3 s" },
        ).joinToString(", ").replaceFirstChar { it.uppercase() }
    }

    /**
     * [calls] newest first. Numbers you saved, dismissed, or already block are excluded by the caller through
     * [exclude]. No suggestion while the regret window runs, or when you called them back, talked to them
     * for longer, or they called again soon after (maybe it was urgent).
     */
    fun suggestions(calls: List<CallEntry>, now: Long, exclude: (String) -> Boolean): List<Suggestion> {
        val byNumber = calls.filter { it.number.isNotBlank() && !it.presentationHidden }.groupBy { PhoneNumbers.matchKey(it.number) }
        val out = ArrayList<Suggestion>()
        for ((_, list) in byNumber) {
            val number = list.first().number
            if (exclude(number)) continue
            val negatives = list.filter { isNegative(it) }
            if (negatives.isEmpty()) continue
            val first = negatives.minOf { it.date }
            val last = negatives.maxOf { it.date }
            if (now - last < REGRET_WINDOW_MS) continue
            val cancelled = list.any { c ->
                c.date > first && (
                    c.type == CallType.OUTGOING ||
                        (c.type == CallType.INCOMING && c.durationSec > SHORT_CALL_SEC) ||
                        (!isNegative(c) && c.type != CallType.BLOCKED && c.date - last in 1..REGRET_WINDOW_MS)
                    )
            }
            if (cancelled) continue
            val rejected = negatives.count { it.type == CallType.REJECTED }
            val short = negatives.count { it.type == CallType.INCOMING }
            val score = (rejected * 30 + short * 25).coerceAtMost(100)
            if (score >= SUGGEST_AT) out += Suggestion(number, score, rejected, short, last)
        }
        return out.sortedByDescending { it.score }
    }

    private fun isNegative(c: CallEntry) =
        c.type == CallType.REJECTED || (c.type == CallType.INCOMING && c.durationSec in 1..SHORT_CALL_SEC)
}
