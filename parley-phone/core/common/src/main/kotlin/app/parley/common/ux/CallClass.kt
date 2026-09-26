package app.parley.common.ux

import app.parley.common.CallEntry
import app.parley.common.CallType
import kotlin.math.ln

/** R4 (v3.3): how Recents rows look. Rich: shapes, tints, sequence dots and a Call back pill. Simple: the U3 icons. */
enum class RecentsStyle { RICH, SIMPLE }

/**
 * R4 (v3.3): what a call was, finer than [CallType] (an outgoing call nobody answered is its own class), each with a
 * badge that tells it apart without colour: a [fill] and a [form] for the badge and a [glyph] inside it. The colour
 * ([hue]) comes on top, so the classes stay distinct for colour-blind users, in grey scale and in dark mode.
 */
enum class CallClass(val hue: CallHue, val fill: Fill, val form: Form, val glyph: Glyph) {
    /** Solid, loud: the one to notice. */
    MISSED(CallHue.MISSED, Fill.SOLID, Form.ROUND, Glyph.ARROW_MISSED),
    /** You declined it: solid but square, with the hang-up handset. */
    DECLINED(CallHue.MISSED, Fill.SOLID, Form.SQUARE, Glyph.HANG_UP),
    INCOMING(CallHue.INCOMING, Fill.TONAL, Form.ROUND, Glyph.ARROW_IN),
    /** Answered on another device (a watch, a linked phone): dashed, it didn't happen here. */
    ANSWERED_ELSEWHERE(CallHue.INCOMING, Fill.DASHED, Form.ROUND, Glyph.OTHER_DEVICE),
    VOICEMAIL(CallHue.INCOMING, Fill.TONAL, Form.SQUARE, Glyph.VOICEMAIL),
    /** Outgoing calls are outlined: you started them, nothing to act on. */
    OUTGOING(CallHue.OUTGOING, Fill.OUTLINE, Form.ROUND, Glyph.ARROW_OUT),
    /** Outgoing, nobody answered: dashed outline ("No answer"). */
    NO_ANSWER(CallHue.OUTGOING, Fill.DASHED, Form.ROUND, Glyph.ARROW_OUT_UNANSWERED),
    /** Crossed out, square. */
    BLOCKED(CallHue.BLOCKED, Fill.OUTLINE, Form.SQUARE, Glyph.BLOCK),
    UNKNOWN(CallHue.NEUTRAL, Fill.TONAL, Form.ROUND, Glyph.PHONE);

    enum class Fill { SOLID, TONAL, OUTLINE, DASHED }
    enum class Form { ROUND, SQUARE }
    enum class Glyph { ARROW_IN, ARROW_OUT, ARROW_OUT_UNANSWERED, ARROW_MISSED, HANG_UP, BLOCK, VOICEMAIL, OTHER_DEVICE, PHONE }

    /** Calls that were talked on ([durationBar] applies). */
    val answered: Boolean get() = this == INCOMING || this == OUTGOING

    companion object {
        /** The class of a call; [durationSec] tells an answered outgoing call from one nobody picked up. */
        fun of(type: CallType, durationSec: Long): CallClass = when (type) {
            CallType.MISSED -> MISSED
            CallType.REJECTED -> DECLINED
            CallType.INCOMING -> INCOMING
            CallType.ANSWERED_EXTERNALLY -> ANSWERED_ELSEWHERE
            CallType.VOICEMAIL -> VOICEMAIL
            CallType.OUTGOING -> if (durationSec > 0) OUTGOING else NO_ANSWER
            CallType.BLOCKED -> BLOCKED
            CallType.UNKNOWN -> UNKNOWN
        }

        fun of(e: CallEntry): CallClass = of(e.type, e.durationSec)
    }
}

/** R4 (v3.3): pure helpers behind the rich Recents rows. */
object CallGlance {
    /** Most dots in a row's call sequence. */
    const val MAX_DOTS = 4

    /** A talk of an hour or more fills the whole duration bar. */
    private const val FULL_BAR_SEC = 3600L

    /**
     * The classes of a row's calls for the sequence dots, oldest first (reading order), at most [max]: the latest
     * calls of the group. [calls] come newest first, like Recents. A single call has no sequence.
     */
    fun sequence(calls: List<CallEntry>, max: Int = MAX_DOTS): List<CallClass> =
        if (calls.size < 2) emptyList() else calls.take(max).map(CallClass::of).reversed()

    /**
     * The width of the duration bar, 0..1, on a log scale so a 2-minute call is visibly longer than a 20-second one
     * while an hour-long call doesn't dwarf everything. Never less than a sliver for a call that connected.
     */
    fun durationFraction(durationSec: Long): Float {
        if (durationSec <= 0) return 0f
        val f = (ln(1.0 + durationSec) / ln(1.0 + FULL_BAR_SEC)).toFloat()
        return f.coerceIn(0.08f, 1f)
    }

    /** R4: a missed call older than this no longer asks to be returned (the Missed chip would only grow). */
    const val UNRETURNED_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Ids of missed calls not returned yet: no outgoing call to the number (answered or not: you tried) and no answered
     * call from it came after. Only each number's latest missed call counts, and only within [maxAgeMs] of [now].
     * Private and withheld numbers can't be called back, and numbers that are [excluded] (blocked, or marked as spam)
     * aren't worth it: neither counts. [calls] are newest first (Recents' merged list, not sorted again); [key]
     * gives a number's match key. One pass, and [excluded] is asked only about the candidates.
     */
    fun unreturnedMissed(
        calls: List<CallEntry>, key: (String) -> String, now: Long,
        maxAgeMs: Long = UNRETURNED_MAX_AGE_MS, excluded: (String) -> Boolean = { false },
    ): Set<Long> {
        val cutoff = now - maxAgeMs
        val handled = HashSet<String>()
        val out = LinkedHashSet<Long>()
        for (e in calls) {
            // Newest first: everything from here on is too old to count (or to have returned a call that counts).
            if (e.date < cutoff) break
            if (e.presentationHidden || e.number.none { it.isDigit() }) continue
            val k = key(e.number)
            if (k in handled) continue
            when (CallClass.of(e)) {
                CallClass.OUTGOING, CallClass.NO_ANSWER, CallClass.INCOMING, CallClass.ANSWERED_ELSEWHERE -> handled += k
                CallClass.MISSED -> {
                    if (!excluded(e.number)) out += e.id
                    handled += k
                }
                else -> Unit
            }
        }
        return out
    }

    /** People (numbers) with a missed call still to return, for the Missed chip's count. */
    fun unreturnedCount(calls: List<CallEntry>, key: (String) -> String, now: Long): Int = unreturnedMissed(calls, key, now).size
}
