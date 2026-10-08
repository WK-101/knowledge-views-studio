package app.parley.common.calls

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** When a rescue call rings: now, in a few minutes, or at a time of day. */
enum class RescueWhen(val minutes: Int) {
    NOW(0),
    IN_1(1),
    IN_5(5),
    IN_15(15),

    /** At a chosen time of day ([RescuePlan.fireAt] takes its minute of the day). */
    AT_TIME(-1),
}

/**
 * One rescue call waiting to ring: who it shows ([name], and [number] when it is someone saved, looked up again as it
 * rings so it shows as their real call would) and when ([atMillis], wall clock). [id] tells it apart from an earlier
 * one, so a late alarm for a cancelled or replaced call rings nothing.
 */
data class RescueRequest(val id: String, val name: String, val number: String?, val atMillis: Long)

/**
 * Rescue call: a believable incoming call on Parley's own call screen, to leave a situation. Nothing here is a real
 * call: no Telecom call, no call log, nothing kept afterwards. These are the rules for when it rings and when it
 * gives way; the call screen and the ringing live in the telecom module, the scheduling in the app.
 *
 * Parley has no exact-alarm permission and adds none, so a call more than a moment away is an inexact alarm: on time
 * while Parley keeps the phone awake for it (a short wait, [keepsAwake]), otherwise when Android delivers the alarm,
 * which can be a few minutes late.
 */
object RescuePlan {
    /** A rescue call rings this long unanswered, about as long as a real one before voicemail. */
    const val RING_MS = 45_000L

    /** An answered rescue call ends by itself after this long, in case it is forgotten. */
    const val MAX_CALL_MS = 60 * 60_000L

    /** The longest wait Parley keeps the phone awake for, so the call rings on time (the 15-minute choice and a little). */
    const val AWAKE_WAIT_MAX_MS = 16 * 60_000L

    /** An alarm this late (the phone was off, Android held it back for long) rings nothing: the moment has passed. */
    const val STALE_MS = 15 * 60_000L

    /** The longest name shown. */
    const val MAX_NAME = 40

    /** The wall-clock time a call chosen [choice] rings, for [minuteOfDay] with [RescueWhen.AT_TIME]. */
    fun fireAt(choice: RescueWhen, nowMillis: Long, minuteOfDay: Int = 0, zone: ZoneId = ZoneId.systemDefault()): Long = when (choice) {
        RescueWhen.AT_TIME -> nextTime(minuteOfDay, nowMillis, zone)
        else -> nowMillis + choice.minutes * 60_000L
    }

    /** The next moment the clock reads [minuteOfDay] after [nowMillis]: later today, else tomorrow (a whole minute ahead at least). */
    fun nextTime(minuteOfDay: Int, nowMillis: Long, zone: ZoneId): Long {
        val m = minuteOfDay.coerceIn(0, MINUTES_A_DAY - 1)
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val today = now.with(LocalTime.of(m / 60, m % 60)).withSecond(0).withNano(0)
        val at = if (today.toInstant().toEpochMilli() > nowMillis) today else today.plusDays(1)
        // ZonedDateTime keeps the wall time through a clock change; a time that doesn't exist that day moves forward.
        return at.toInstant().toEpochMilli()
    }

    /** What an alarm for [firedId] does when it arrives at [nowMillis], with [pending] the call still waiting (or none). */
    fun onAlarm(pending: RescueRequest?, firedId: String, nowMillis: Long): Due = when {
        pending == null || pending.id != firedId -> Due.GONE
        nowMillis + EARLY_MS < pending.atMillis -> Due.EARLY
        nowMillis - pending.atMillis > STALE_MS -> Due.STALE
        else -> Due.RING
    }

    /**
     * Whether a call read back from storage can still ring: set since the phone last started ([storedBoot] and
     * [bootNow] are Android's boot counts, -1 when unknown; a restart drops its alarm, and Parley isn't told of
     * restarts) and not past its time by more than [STALE_MS]. One that can't is dropped, never shown as waiting.
     */
    fun stillWaiting(pending: RescueRequest, storedBoot: Int, bootNow: Int, nowMillis: Long): Boolean {
        val sameBoot = storedBoot < 0 || bootNow < 0 || storedBoot == bootNow
        return sameBoot && nowMillis - pending.atMillis <= STALE_MS
    }

    /** What an alarm finds: ring now, too early (wait on), too late, or nothing waiting any more. */
    enum class Due { RING, EARLY, STALE, GONE }

    /** Whether Parley keeps the phone awake to ring on time after [delayMs] (only short waits; never for "now"). */
    fun keepsAwake(delayMs: Long): Boolean = delayMs in 1..AWAKE_WAIT_MAX_MS

    /**
     * A real call always wins: a rescue call never starts while one is ringing, dialling or connected, and one that
     * rings or is answered gives way the moment a real call arrives (it ends, leaving nothing behind).
     */
    fun mayRing(realCallsLive: Int, systemInCall: Boolean): Boolean = realCallsLive == 0 && !systemInCall

    /** The name to show: what was typed (trimmed, at most [MAX_NAME]), else the saved person's, else none. */
    fun shownName(typed: String?, saved: String?): String? =
        typed?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() } ?: saved?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Where a call waits. A duress unlock hides the call that was waiting ([FIRST]) and it still rings: one set while
     * hiding waits beside it ([SECOND]), so nothing set then can cancel or replace the hidden one.
     */
    enum class Slot { FIRST, SECOND }

    /** Where a call set later goes: beside the hidden one while a duress unlock hides things. */
    fun slotFor(hiding: Boolean): Slot = if (hiding) Slot.SECOND else Slot.FIRST

    /**
     * The calls waiting that setting another one ([ringsNow] or later) replaces. Outside a duress session the screen
     * speaks for every call waiting, so any new choice replaces them all; while hiding, only one set later replaces
     * the one set earlier while hiding, and the hidden call is never touched.
     */
    fun replaces(hiding: Boolean, ringsNow: Boolean): Set<Slot> = when {
        !hiding -> setOf(Slot.FIRST, Slot.SECOND)
        ringsNow -> emptySet()
        else -> setOf(Slot.SECOND)
    }

    /** What "Cancel" on the screen cancels: everything waiting, or while hiding only what was set while hiding. */
    fun cancels(hiding: Boolean): Set<Slot> = if (hiding) setOf(Slot.SECOND) else setOf(Slot.FIRST, Slot.SECOND)

    /** The call the screen shows as waiting: while hiding only one set while hiding; otherwise the first, else the other. */
    fun <T> shown(hiding: Boolean, first: T?, second: T?): T? = if (hiding) second else first ?: second

    /** An alarm may come a moment early (batched): ringing this much before the time counts as on time. */
    private const val EARLY_MS = 30_000L
    private const val MINUTES_A_DAY = 24 * 60
}
