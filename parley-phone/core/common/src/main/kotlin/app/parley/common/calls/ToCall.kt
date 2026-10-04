package app.parley.common.calls

import app.parley.common.CallEntry
import app.parley.common.Codecs
import app.parley.common.ux.CallClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Where a stored "To call" item came from. */
@Serializable
enum class ToCallSource {
    /** "Remind me" on a declined call, a missed-call notification, the post-call card or the list itself. */
    REMINDER,

    /** "Follow up in a week / a month" from "Anything to remember?" after a call. */
    FOLLOW_UP,
}

/**
 * A call you said you'd make. Kept by number ([key] is the line key, see PhoneIdentity.key), never by contact, so it
 * follows a contact that becomes private or visible again without re-keying, and a private contact's item holds no
 * name. [at] is the time you picked; [dueAt] moves it into the evening where they are when [theirEvening] is on.
 */
@Serializable
data class ToCallItem(
    val key: String,
    val number: String,
    val source: ToCallSource = ToCallSource.REMINDER,
    /** When the call became owed: a call with them after this settles it. */
    val since: Long,
    /** The time picked for the reminder. */
    val at: Long,
    /** Remind only once it's evening for them ([ToCall.EVENING_HOUR] to [ToCall.EVENING_END_HOUR] in [zone]). */
    val theirEvening: Boolean = false,
    /** Their time-zone id, when the number tells it. */
    val zone: String? = null,
    /** The SIM the call came in on, so a national number is read with its country. */
    val accountId: String? = null,
    /** The reminder was shown; it isn't shown again unless the item is moved to a new time. */
    val notified: Boolean = false,
) {
    /** When the item is due (and its notification shows). */
    @Transient
    val dueAt: Long = ToCall.dueAt(at, theirEvening, zone)
}

/** Everything the list keeps: the items, and which missed calls were already dealt with. */
@Serializable
data class ToCallState(
    val items: List<ToCallItem> = emptyList(),
    /** Line key → the time the caller's missed calls were dealt with (done, removed, or moved into an item). */
    val handledMissed: Map<String, Long> = emptyMap(),
)

/** What a row of the list is. */
enum class ToCallKind { REMINDER, FOLLOW_UP, MISSED }

/** An unreturned missed call (as Recents works it out: each number's latest, within a week, not blocked). */
data class OwedMissedCall(val key: String, val number: String, val date: Long, val accountId: String?)

/** A call that settles what you owe someone: you called them (answered or not: you tried), or you talked. */
data class SettlingCall(val key: String, val date: Long)

/** One row of the "To call" list: a stored item, or an unreturned missed call. */
data class ToCallEntry(
    val key: String,
    val number: String,
    val kind: ToCallKind,
    /** When it became owed. */
    val since: Long,
    /** When it's due; null for a missed call (due at once). */
    val dueAt: Long?,
    val theirEvening: Boolean = false,
    val zone: String? = null,
    val accountId: String? = null,
    /** A missed call from them came after the item was set. */
    val missedAt: Long? = null,
) {
    fun isDue(now: Long): Boolean = dueAt == null || dueAt <= now
}

/** The fixed times of "Remind me" (no location, so no "when I leave"). */
enum class RemindTime { IN_AN_HOUR, THIS_EVENING, TOMORROW_MORNING }

/** The quiet strip at the top of Recents: how many are due now, and how many later. */
data class ToCallCount(val due: Int, val later: Int) {
    val total: Int get() = due + later
}

/**
 * The "To call" list (I9): one list of the calls you owe, fed by "Remind me" (on a declined call, a missed-call
 * notification, the post-call card), follow-ups set after a call, and unreturned missed calls. Pure: the app stores
 * [ToCallState], feeds in the missed calls and the calls that settle items, and schedules one inexact reminder at
 * [nextAlarm].
 */
object ToCall {
    /** "This evening", and the start of "after 6 pm their time". */
    const val EVENING_HOUR = 18

    /** Their evening ends here: later than this, the reminder waits for their next evening. */
    const val EVENING_END_HOUR = 21

    /** "Tomorrow morning", and when a follow-up in N days is due. */
    const val MORNING_HOUR = 9

    const val HOUR_MS = 3_600_000L
    private const val MINUTE_MS = 60_000L

    /** "Not now" on the reminder moves it this far. */
    const val NOT_NOW_MS = HOUR_MS

    /** Handled missed calls are remembered a little longer than Recents counts a call as unreturned (7 days). */
    const val HANDLED_KEEP_MS = 8 * 24 * HOUR_MS

    /** An item this close to its time counts as due (WorkManager runs a little early or late). */
    const val DUE_SLACK_MS = MINUTE_MS

    /** Items kept at most; the oldest go first (a list nobody works through shouldn't grow forever). */
    const val MAX_ITEMS = 200

    private val json = Codecs.tolerant

    fun encode(state: ToCallState): String = json.encodeToString(ToCallState.serializer(), state)

    fun decode(stored: String?): ToCallState =
        stored?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(ToCallState.serializer(), it) }.getOrNull() } ?: ToCallState()

    // ---------------------------------------------------------------- Times

    /** The choices that make sense now: "This evening" only while there's still an hour to go before it. */
    fun choices(now: Long, zone: ZoneId): List<RemindTime> {
        val hour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).hour
        return RemindTime.entries.filter { it != RemindTime.THIS_EVENING || hour < EVENING_HOUR - 1 }
    }

    /** The time a choice means, in the user's own [zone]. "This evening" once it's evening is an hour from now. */
    fun at(choice: RemindTime, now: Long, zone: ZoneId): Long {
        val here = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        return when (choice) {
            RemindTime.IN_AN_HOUR -> roundUpToMinute(now + HOUR_MS)
            RemindTime.THIS_EVENING -> {
                val evening = here.truncatedTo(ChronoUnit.DAYS).withHour(EVENING_HOUR)
                if (evening.toInstant().toEpochMilli() > now + MINUTE_MS) evening.toInstant().toEpochMilli() else roundUpToMinute(now + HOUR_MS)
            }
            RemindTime.TOMORROW_MORNING -> here.truncatedTo(ChronoUnit.DAYS).plusDays(1).withHour(MORNING_HOUR).toInstant().toEpochMilli()
        }
    }

    /** A follow-up in [days] days: that morning. */
    fun inDays(days: Int, now: Long, zone: ZoneId): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).truncatedTo(ChronoUnit.DAYS)
            .plusDays(days.coerceAtLeast(1).toLong()).withHour(MORNING_HOUR).toInstant().toEpochMilli()

    private fun roundUpToMinute(t: Long): Long = (t + MINUTE_MS - 1) / MINUTE_MS * MINUTE_MS

    /**
     * The first moment at or after [at] when it's evening for them ([EVENING_HOUR] to [EVENING_END_HOUR] in [their]):
     * [at] itself when it already is, that day's [EVENING_HOUR] when it's earlier, the next day's when it's later.
     */
    fun theirEvening(at: Long, their: ZoneId): Long {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), their)
        val start = t.truncatedTo(ChronoUnit.DAYS).withHour(EVENING_HOUR)
        return when {
            t.hour < EVENING_HOUR -> start.toInstant().toEpochMilli()
            t.hour >= EVENING_END_HOUR -> start.plusDays(1).toInstant().toEpochMilli()
            else -> at
        }
    }

    /** When an item picked for [at] is due, with their evening applied when asked and their zone is known. */
    fun dueAt(at: Long, theirEvening: Boolean, zone: String?): Long {
        if (!theirEvening) return at
        val z = zoneOf(zone) ?: return at
        return theirEvening(at, z)
    }

    fun zoneOf(id: String?): ZoneId? = id?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    /**
     * Whether "after 6 pm their time" is worth offering: their zone is known and its clock differs from the user's
     * now (otherwise "This evening" says the same).
     */
    fun offersTheirEvening(zone: String?, local: ZoneId, now: Long): Boolean {
        val z = zoneOf(zone) ?: return false
        val instant = Instant.ofEpochMilli(now)
        return z.rules.getOffset(instant) != local.rules.getOffset(instant)
    }

    // ---------------------------------------------------------------- Changes

    /**
     * Puts [number] on the list for [at] (one item per number: a newer "Remind me" moves the earlier one, keeping
     * when the call became owed). Missed calls from them until now are part of it.
     */
    @Suppress("LongParameterList") // One item's fields, named at every call.
    fun remind(
        state: ToCallState, key: String, number: String, at: Long, now: Long,
        source: ToCallSource = ToCallSource.REMINDER, since: Long = now, theirEvening: Boolean? = null,
        zone: String? = null, accountId: String? = null,
    ): ToCallState {
        if (key.isEmpty() || number.isBlank()) return state
        val old = state.items.firstOrNull { it.key == key }
        val item = ToCallItem(
            key = key, number = number,
            // A follow-up never turns an explicit reminder into a follow-up.
            source = if (old?.source == ToCallSource.REMINDER) ToCallSource.REMINDER else source,
            since = minOf(since, old?.since ?: since),
            at = at,
            theirEvening = theirEvening ?: old?.theirEvening ?: false,
            zone = zone ?: old?.zone,
            accountId = accountId ?: old?.accountId,
        )
        val items = (state.items.filter { it.key != key } + item).sortedBy { it.since }.takeLast(MAX_ITEMS)
        return state.copy(items = items, handledMissed = state.handledMissed + (key to now))
    }

    /** Done or removed: the item goes, and missed calls from them until now no longer ask to be returned. */
    fun done(state: ToCallState, key: String, now: Long): ToCallState =
        state.copy(items = state.items.filter { it.key != key }, handledMissed = state.handledMissed + (key to now))

    /**
     * Undo of [done] for [key]: its item and missed-call mark as they were in [before], leaving every other change
     * made since alone.
     */
    fun undoDone(state: ToCallState, before: ToCallState, key: String): ToCallState {
        val item = before.items.firstOrNull { it.key == key }
        val items = if (item != null && state.items.none { it.key == key }) (state.items + item).sortedBy { it.since } else state.items
        val handled = before.handledMissed[key]?.let { state.handledMissed + (key to it) } ?: (state.handledMissed - key)
        return ToCallState(items, handled)
    }

    /**
     * Undo of [remind] for [key]: its item and missed-call mark as they were in [before] (no item then, none now),
     * leaving every other change made since alone.
     */
    fun undoRemind(state: ToCallState, before: ToCallState, key: String): ToCallState {
        val item = before.items.firstOrNull { it.key == key }
        val items = (state.items.filter { it.key != key } + listOfNotNull(item)).sortedBy { it.since }
        val handled = before.handledMissed[key]?.let { state.handledMissed + (key to it) } ?: (state.handledMissed - key)
        return ToCallState(items, handled)
    }

    /** Moves [entry] (an item, or a missed call that becomes one) to [at]. */
    fun snooze(state: ToCallState, entry: ToCallEntry, at: Long, now: Long): ToCallState =
        remind(state, entry.key, entry.number, at, now, since = entry.since, accountId = entry.accountId, zone = entry.zone)

    /** "Not now" on the reminder: the shown items come back in [NOT_NOW_MS], quietly and once. */
    fun notNow(state: ToCallState, keys: Set<String>, now: Long): ToCallState =
        state.copy(items = state.items.map { if (it.key in keys) it.copy(at = now + NOT_NOW_MS, theirEvening = false, notified = false) else it })

    /** Turns "after 6 pm their time" on or off for [entry] (a missed call becomes an item due now, or in their evening). */
    fun setTheirEvening(state: ToCallState, entry: ToCallEntry, on: Boolean, now: Long): ToCallState {
        val old = state.items.firstOrNull { it.key == entry.key }
        return if (old != null) {
            state.copy(items = state.items.map { if (it.key == entry.key) it.copy(theirEvening = on, notified = false) else it })
        } else {
            remind(state, entry.key, entry.number, now, now, since = entry.since, theirEvening = on, zone = entry.zone, accountId = entry.accountId)
        }
    }

    /** Whether [e] settles what's owed: you called them (answered or not: you tried), or you talked. */
    fun settles(e: CallEntry): Boolean =
        !e.presentationHidden && CallClass.of(e) in setOf(CallClass.OUTGOING, CallClass.NO_ANSWER, CallClass.INCOMING, CallClass.ANSWERED_ELSEWHERE)

    /**
     * Items settled by a later call with the person go, and handled missed calls too old to matter are forgotten.
     * Returns [state] itself when nothing changed.
     */
    fun settle(state: ToCallState, calls: List<SettlingCall>, now: Long): ToCallState {
        val latest = HashMap<String, Long>()
        calls.forEach { c -> if (c.date > (latest[c.key] ?: Long.MIN_VALUE)) latest[c.key] = c.date }
        val items = state.items.filter { i -> (latest[i.key] ?: Long.MIN_VALUE) <= i.since }
        val settled = state.items.filter { it !in items }.associate { it.key to now }
        val handled = (state.handledMissed + settled).filterValues { it >= now - HANDLED_KEEP_MS }
        return if (items.size == state.items.size && handled == state.handledMissed) state else ToCallState(items, handled)
    }

    // ---------------------------------------------------------------- Reading

    /**
     * The list: stored items and the unreturned [missed] calls not dealt with yet, one row per number. Due rows come
     * first (the newest first), then the later ones by time.
     */
    fun entries(state: ToCallState, missed: List<OwedMissedCall>, now: Long): List<ToCallEntry> {
        val byKey = missed.groupBy { it.key }.mapValues { (_, l) -> l.maxBy { it.date } }
        val stored = state.items.map { i ->
            val m = byKey[i.key]?.takeIf { it.date > i.since }
            ToCallEntry(
                i.key, i.number, if (i.source == ToCallSource.FOLLOW_UP) ToCallKind.FOLLOW_UP else ToCallKind.REMINDER,
                i.since, i.dueAt, i.theirEvening, i.zone, i.accountId, missedAt = m?.date,
            )
        }
        val have = state.items.map { it.key }.toSet()
        val owed = byKey.values.filter { m -> m.key !in have && m.date > (state.handledMissed[m.key] ?: Long.MIN_VALUE) }
            .map { m -> ToCallEntry(m.key, m.number, ToCallKind.MISSED, m.date, null, accountId = m.accountId) }
        val all = stored + owed
        val (due, later) = all.partition { it.isDue(now) }
        return due.sortedByDescending { it.dueAt ?: it.since } + later.sortedBy { it.dueAt }
    }

    fun count(entries: List<ToCallEntry>, now: Long): ToCallCount = ToCallCount(entries.count { it.isDue(now) }, entries.count { !it.isDue(now) })

    /** Items due now whose reminder hasn't shown yet. */
    fun toNotify(state: ToCallState, now: Long): List<ToCallItem> = state.items.filter { !it.notified && it.dueAt <= now + DUE_SLACK_MS }

    fun markNotified(state: ToCallState, keys: Set<String>): ToCallState =
        state.copy(items = state.items.map { if (it.key in keys) it.copy(notified = true) else it })

    /** When the next reminder is due (never in the past), or null when none is waiting. */
    fun nextAlarm(state: ToCallState, now: Long): Long? = state.items.filter { !it.notified }.minOfOrNull { it.dueAt }?.coerceAtLeast(now)

    // ---------------------------------------------------------------- Backup

    /** [state] without the numbers [leaveOut] (a private contact's number stays out of the settings backup). */
    fun without(state: ToCallState, leaveOut: (String) -> Boolean): ToCallState {
        val dropped = state.items.filter { leaveOut(it.number) }.map { it.key }.toSet()
        return ToCallState(state.items.filterNot { it.key in dropped }, state.handledMissed.filterKeys { it !in dropped })
    }

    /** A restored list added to this phone's: this phone's item wins for a number both have. */
    fun merge(here: ToCallState, restored: ToCallState): ToCallState {
        val keys = here.items.map { it.key }.toSet()
        val items = (here.items + restored.items.filter { it.key !in keys }).sortedBy { it.since }.takeLast(MAX_ITEMS)
        val handled = HashMap(here.handledMissed)
        restored.handledMissed.forEach { (k, v) -> if (v > (handled[k] ?: Long.MIN_VALUE)) handled[k] = v }
        return ToCallState(items, handled)
    }
}
