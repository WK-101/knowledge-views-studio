package app.parley.common.circle

import app.parley.common.CallEntry
import app.parley.common.CallType

/**
 * R6: the "People" card in Insights, worked out from history alone (the call log and logged interactions; no
 * snapshot table). Everything here is pure: the app hands in [Touch]es and today's Circle.
 */
object PeopleInsights {
    private const val DAY = NaturalRhythm.DAY

    /** "This month" for reach: the last 30 days, compared with the 30 before. */
    const val REACH_DAYS = 30

    /** Open loops older than this fade out quietly (no growing list of things you didn't do). */
    const val LOOP_DAYS = 30

    /** Year in review needs at least this many entries in the last year. */
    const val REVIEW_MIN_ENTRIES = 20

    /** Who reaches out first needs at least this many conversations. */
    const val FIRST_MOVER_MIN = 4

    /** Calls less than this far apart belong to one conversation (a call back, a second try). */
    const val CONVERSATION_GAP_MS = 12 * 3_600_000L

    enum class TouchKind {
        /** An answered call they made. */
        CALL_IN,

        /** An answered call you made. */
        CALL_OUT,

        /** Their call you missed or declined. */
        MISSED_IN,

        /** Your call they didn't answer. */
        UNANSWERED_OUT,

        /** A logged meeting, message, video call… (R2). */
        LOGGED,

        /** "Mark as wished" (R5). */
        WISHED,
        ;

        /** You were in touch (a conversation happened). */
        val inTouch: Boolean get() = this == CALL_IN || this == CALL_OUT || this == LOGGED || this == WISHED
    }

    data class Touch(val key: String, val time: Long, val kind: TouchKind)

    /** A call as a [Touch] (null for blocked, voicemail and other kinds). */
    fun touchOf(key: String, c: CallEntry): Touch? {
        val kind = when (c.type) {
            CallType.INCOMING -> if (c.durationSec > 0) TouchKind.CALL_IN else TouchKind.MISSED_IN
            CallType.OUTGOING -> if (c.durationSec > 0) TouchKind.CALL_OUT else TouchKind.UNANSWERED_OUT
            CallType.MISSED, CallType.REJECTED -> TouchKind.MISSED_IN
            else -> null
        } ?: return null
        return Touch(key, c.date, kind)
    }

    enum class Trend { UP, DOWN, SAME }

    /** "You were in touch with [now] of [circle] in your circle this month" ([before]: the 30 days before). */
    data class Reach(val now: Int, val before: Int, val circle: Int) {
        val trend: Trend get() = when {
            now > before -> Trend.UP
            now < before -> Trend.DOWN
            else -> Trend.SAME
        }
    }

    fun reach(circle: Set<String>, touches: List<Touch>, now: Long): Reach {
        val start = now - REACH_DAYS * DAY
        val prev = start - REACH_DAYS * DAY
        val inTouch = touches.filter { it.kind.inTouch && it.key in circle }
        val a = inTouch.filter { it.time in start..now }.map { it.key }.toSet().size
        val b = inTouch.filter { it.time in prev until start }.map { it.key }.toSet().size
        return Reach(a, b, circle.size)
    }

    enum class LoopKind { THEIR_CALL, YOUR_TRY }

    /** An open loop: the newest thing between you is [kind], [count] times in a row, last at [time]. */
    data class Loop(val key: String, val kind: LoopKind, val time: Long, val count: Int)

    /**
     * Open loops: someone whose newest entry is their missed call (you haven't called back or been in touch
     * since), or your call they didn't answer (nothing from them since). Any later contact closes it by itself.
     * Newest first; loops older than [LOOP_DAYS] are left out.
     */
    fun openLoops(touches: List<Touch>, now: Long): List<Loop> =
        touches.groupBy { it.key }.mapNotNull { (key, list) ->
            val sorted = list.sortedByDescending { it.time }
            val last = sorted.first()
            val kind = when (last.kind) {
                TouchKind.MISSED_IN -> LoopKind.THEIR_CALL
                TouchKind.UNANSWERED_OUT -> LoopKind.YOUR_TRY
                else -> return@mapNotNull null
            }
            if (now - last.time > LOOP_DAYS * DAY) return@mapNotNull null
            Loop(key, kind, last.time, sorted.takeWhile { it.kind == last.kind }.size)
        }.sortedByDescending { it.time }

    enum class FirstMover { THEM, YOU, BOTH }

    /**
     * Who usually starts a conversation with one person: the first call of each conversation (calls less than
     * [CONVERSATION_GAP_MS] apart are one), answered or not. Logged interactions have no direction and don't count.
     * Null with fewer than [FIRST_MOVER_MIN] conversations.
     */
    fun firstMover(touches: List<Touch>): FirstMover? {
        val calls = touches.filter { it.kind != TouchKind.LOGGED && it.kind != TouchKind.WISHED }.sortedBy { it.time }
        var theirs = 0
        var yours = 0
        var lastTime: Long? = null
        for (t in calls) {
            if (lastTime == null || t.time - lastTime >= CONVERSATION_GAP_MS) {
                if (t.kind == TouchKind.CALL_IN || t.kind == TouchKind.MISSED_IN) theirs++ else yours++
            }
            lastTime = t.time
        }
        val total = theirs + yours
        if (total < FIRST_MOVER_MIN) return null
        val share = theirs.toDouble() / total
        return when {
            share >= 0.65 -> FirstMover.THEM
            share <= 0.35 -> FirstMover.YOU
            else -> FirstMover.BOTH
        }
    }

    /**
     * Year in review over the last 365 days: who you were most in touch with (up to three, with how often), the
     * longest gap between two contacts with someone in your circle, and the occasions you acknowledged.
     */
    data class Review(
        val entries: Int,
        val most: List<Pair<String, Int>>,
        /** Circle member and the gap in whole days. */
        val longestGap: Pair<String, Int>?,
        val occasions: Int,
    )

    /** Null below [REVIEW_MIN_ENTRIES] entries. */
    fun yearInReview(touches: List<Touch>, circle: Set<String>, now: Long): Review? {
        val year = touches.filter { it.kind.inTouch && it.time in (now - 365 * DAY)..now }
        if (year.size < REVIEW_MIN_ENTRIES) return null
        val most = year.groupingBy { it.key }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(3).map { it.key to it.value }
        val gap = year.filter { it.key in circle }.groupBy { it.key }.mapNotNull { (key, list) ->
            val times = list.map { it.time }.sorted()
            times.zipWithNext { a, b -> b - a }.maxOrNull()?.let { key to (it / DAY).toInt() }
        }.filter { it.second > 0 }.maxWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenByDescending { it.first })
        return Review(year.size, most, gap, year.count { it.kind == TouchKind.WISHED })
    }
}
