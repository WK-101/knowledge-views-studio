package app.parley.common.circle

import app.parley.common.CallEntry
import app.parley.common.CallType
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * X1: a good time to call someone, from your own history with them: the hours when they answer your calls or call
 * you. Worked out on the phone, in their local time when it's known (from the number), and only once there are
 * at least [MIN_ANSWERED] answered calls.
 */
object GoodTime {
    const val MIN_ANSWERED = 8

    /** The window is this many hours long. */
    const val WINDOW_HOURS = 3

    /** The window must hold at least this share of the calls, or there's no clear pattern. */
    const val MIN_SHARE = 0.4

    /** Only the last two years count. */
    const val WINDOW_DAYS = 730L

    /** Hours [startHour] (inclusive) to [endHour] (exclusive; past 24 when it runs past midnight), in their time. */
    data class Window(val startHour: Int, val endHour: Int) {
        fun contains(hour: Int): Boolean = Math.floorMod(hour - startHour, 24) < endHour - startHour

        /** The end as an hour of the day (0–23). */
        val endOfDay: Int get() = endHour % 24
    }

    /**
     * The best [WINDOW_HOURS]-hour window from [calls] with one person (any of their numbers), in [zone] (theirs,
     * or yours when theirs isn't known). Calls that show they were free: your answered calls and every call they
     * made (answered, missed or declined). Null with too little history or no clear pattern.
     */
    fun window(calls: List<CallEntry>, zone: ZoneId, now: Long): Window? {
        val since = now - WINDOW_DAYS * NaturalRhythm.DAY
        val recent = calls.filter { it.date in since..now }
        val answered = recent.count { it.durationSec > 0 && (it.type == CallType.INCOMING || it.type == CallType.OUTGOING) }
        if (answered < MIN_ANSWERED) return null
        val free = recent.filter {
            (it.type == CallType.OUTGOING && it.durationSec > 0) || it.type == CallType.INCOMING || it.type == CallType.MISSED || it.type == CallType.REJECTED
        }
        if (free.isEmpty()) return null
        val hours = IntArray(24)
        free.forEach { hours[Instant.ofEpochMilli(it.date).atZone(zone).hour]++ }
        // Circular: a window can run past midnight. Ties go to the earliest start, so the result is stable.
        val (start, count) = (0 until 24).map { s -> s to (0 until WINDOW_HOURS).sumOf { hours[(s + it) % 24] } }.maxWith(compareBy<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
        if (count < free.size * MIN_SHARE) return null
        return Window(start, start + WINDOW_HOURS)
    }

    /**
     * Their time zone from the zones a number's region can be in ([ids], e.g. from libphonenumber): one zone, or
     * several that are all at the same offset now. Null when unknown or when the country spans several offsets.
     */
    fun zoneOf(ids: List<String>, now: Long): ZoneId? {
        val zones = ids.mapNotNull { id -> runCatching { ZoneId.of(id) }.getOrNull() }
        if (zones.isEmpty()) return null
        val offsets = zones.map { it.rules.getOffset(Instant.ofEpochMilli(now)) }.toSet()
        return if (offsets.size == 1) zones.first() else null
    }

    /** Whether "it's 7:40 pm there" adds anything: their offset differs from [mine] right now. */
    fun differs(theirs: ZoneId?, mine: ZoneId, now: Long): Boolean {
        theirs ?: return false
        val at = Instant.ofEpochMilli(now)
        val a: ZoneOffset = theirs.rules.getOffset(at)
        return a != mine.rules.getOffset(at)
    }
}
