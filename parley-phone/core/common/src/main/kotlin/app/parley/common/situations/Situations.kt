package app.parley.common.situations

import app.parley.common.BlockAction
import app.parley.common.Codecs
import app.parley.common.OffHours
import app.parley.common.OffHoursAllow
import app.parley.common.PolicyClock
import app.parley.common.Schedule
import app.parley.common.calls.SpeakerDefault
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The four Situations Parley starts with, and [CUSTOM] for the ones people make. */
enum class SituationKind { DRIVING, MEETING, NIGHT, TRAVELLING, CUSTOM }

/** Who may ring while a Situation is on: it sets off hours for all day, for as long as it is on. */
enum class SituationRing {
    /** Everyone rings: off hours is off while the Situation is on. */
    EVERYONE,
    CONTACTS,
    FAVOURITES,

    /** The people in [Situation.ringLabel]. */
    LABEL,
}

/** A connection that switches a Situation on by itself, besides its time window. */
enum class DeviceTrigger {
    /** A car marked in the drive profile connects, or the phone goes into car mode (Android Auto, a car dock). */
    CAR,

    /** The Bluetooth audio device called [Situation.deviceName] connects. */
    NAMED,

    /** Any car or Bluetooth audio device connects (or car mode). */
    ANY_BLUETOOTH,
}

/** How the Situation on now was switched on. */
enum class SituationCause { MANUAL, SCHEDULE, DEVICE }

/**
 * One moment ("Driving", "Meeting"…): a named bundle of behaviours Parley already has. Each behaviour is null when the
 * Situation leaves it as it is. While it is on, its values are written into the stores that own them (off hours, the
 * drive profile's switches, auto-answer, the speaker, quick replies, the abroad switches), so the call path reads them
 * as usual and an emergency call or a "who can always reach me" rule is never stopped by one: screening keeps its
 * order (emergency, contacts' allow labels, allow rules, then off hours). The SIM is the one exception: it is read
 * live ([Situations.activeSim]), after a number's own SIM and its label's.
 */
@Serializable
data class Situation(
    val id: String,
    val kind: SituationKind = SituationKind.CUSTOM,
    /** The name given to it; blank keeps a built-in one's own name. */
    val name: String = "",
    val ring: SituationRing? = null,
    /** The label that may ring with [SituationRing.LABEL] (its title, as off hours keeps it). */
    val ringLabel: String? = null,
    /** The drive profile's switches (they act only while the car is connected, as always). */
    val driveAnnounce: Boolean? = null,
    val driveAnswerFavourites: Boolean? = null,
    val driveSilenceUnknown: Boolean? = null,
    /** Auto-answer with a headset or Bluetooth, and for the people chosen on their pages. */
    val autoAnswerHeadset: Boolean? = null,
    val autoAnswerChosen: Boolean? = null,
    val speaker: SpeakerDefault? = null,
    /** A reply offered first when declining a call (put at the top of the quick replies). */
    val reply: String? = null,
    /** Offer [reply] (one tap, through the SMS app) to people off hours silenced. */
    val replyToSilenced: Boolean? = null,
    val assistedDialling: Boolean? = null,
    val localSimHint: Boolean? = null,
    /** The SIM for calls that have no SIM of their own (a number's remembered SIM and a label's still win). */
    val simId: String? = null,
    /** The SIM's name when it was chosen, shown when this phone doesn't have it. */
    val simLabel: String? = null,
    /** Switches on by itself in this window (and off after it). */
    val schedule: Schedule? = null,
    /** Switches on by itself while this is connected (and off once it isn't). */
    val device: DeviceTrigger? = null,
    val deviceName: String? = null,
) {
    val builtIn: Boolean get() = kind != SituationKind.CUSTOM

    /** Whether it can switch itself on. */
    val automatic: Boolean get() = schedule != null || device != null

    /** Whether it changes anything at all. */
    val changesSomething: Boolean
        get() = listOf(
            ring, driveAnnounce, driveAnswerFavourites, driveSilenceUnknown, autoAnswerHeadset, autoAnswerChosen, speaker,
            reply?.takeIf { it.isNotBlank() }, replyToSilenced, assistedDialling, localSimHint, simId,
        ).any { it != null }
}

/**
 * The behaviours a Situation can change, as they are in their stores. A snapshot of these is kept while a Situation is
 * on ([SituationState.before]) and put back when it goes off.
 */
@Serializable
data class Behaviour(
    val offHours: OffHours = OffHours(),
    val driveAnnounce: Boolean = true,
    val driveAnswerFavourites: Boolean = false,
    val driveSilenceUnknown: Boolean = false,
    val autoAnswerHeadset: Boolean = false,
    val autoAnswerChosen: Boolean = false,
    val speaker: SpeakerDefault = SpeakerDefault.OFF,
    val quickReplies: List<String> = emptyList(),
    val busyReply: Boolean = false,
    val busyReplyText: String = "",
    val assistedDialling: Boolean = true,
    val localSimHint: Boolean = true,
)

/**
 * The Situation on now, how it came on, and what to put back: [before] is what was set before it, [applied] what it
 * set. Kept on this phone (a moment, not a preference), written before anything changes so a process death or a reboot
 * in between still knows what to restore. [held]: automatic Situations turned off by hand while their window or device
 * still holds; they stay off until it stops holding.
 */
@Serializable
data class SituationState(
    val activeId: String? = null,
    val cause: SituationCause = SituationCause.MANUAL,
    val since: Long = 0,
    val before: Behaviour? = null,
    val applied: Behaviour? = null,
    val held: List<String> = emptyList(),
) {
    fun encode(): String = Codecs.full.encodeToString(serializer(), this)

    companion object {
        /** Reads [encode]d state; unreadable input reads as nothing on (and nothing to restore). */
        fun decode(text: String?): SituationState =
            if (text.isNullOrBlank()) SituationState() else runCatching { Codecs.fullTolerant.decodeFromString(serializer(), text) }.getOrDefault(SituationState())
    }
}

/** What the triggers see now. */
data class SituationSignals(
    val clock: PolicyClock,
    /** A car marked in the drive profile is connected, or car mode is on. */
    val car: Boolean = false,
    /** The names of the Bluetooth audio devices connected now. */
    val audioNames: List<String> = emptyList(),
    /** Any Bluetooth audio device is connected. */
    val bluetoothAudio: Boolean = false,
)

/** Pure rules of Situations: switching on and off, the snapshot, the triggers and the next time to look again. */
object Situations {
    const val DRIVING = "driving"
    const val MEETING = "meeting"
    const val NIGHT = "night"
    const val TRAVELLING = "travelling"

    /** At most this many Situations (the four built in and those made). */
    const val MAX = 12

    /** The default window offered for Night, 22:00–07:00. */
    val NIGHT_WINDOW = Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60)

    /**
     * The built-in Situations with their suggested bundles; [replies] gives each kind's suggested reply (the app's
     * words). Nothing switches on by itself until chosen.
     */
    fun builtIns(replies: (SituationKind) -> String? = { null }): List<Situation> = listOf(
        Situation(DRIVING, SituationKind.DRIVING, driveAnnounce = true, driveAnswerFavourites = true, reply = replies(SituationKind.DRIVING)),
        Situation(MEETING, SituationKind.MEETING, ring = SituationRing.FAVOURITES, reply = replies(SituationKind.MEETING), replyToSilenced = true),
        Situation(NIGHT, SituationKind.NIGHT, ring = SituationRing.FAVOURITES),
        Situation(
            TRAVELLING, SituationKind.TRAVELLING, assistedDialling = true, localSimHint = true, reply = replies(SituationKind.TRAVELLING),
        ),
    )

    /** [list] made sound: built-ins first and never missing, ids once, at most [MAX]. */
    fun normalise(list: List<Situation>, defaults: List<Situation>): List<Situation> {
        val byId = list.filter { it.id.isNotBlank() }.distinctBy { it.id }.associateBy { it.id }
        val builtIn = defaults.map { d -> byId[d.id]?.copy(kind = d.kind) ?: d }
        val ids = defaults.map { it.id }.toSet()
        val made = list.filter { it.id.isNotBlank() && it.id !in ids }.distinctBy { it.id }.map { it.copy(kind = SituationKind.CUSTOM) }
        return (builtIn + made).take(MAX)
    }

    fun encodeList(list: List<Situation>): String = Codecs.full.encodeToString(LIST, list)

    fun decodeList(text: String?): List<Situation>? =
        if (text.isNullOrBlank()) null else runCatching { Codecs.fullTolerant.decodeFromString(LIST, text) }.getOrNull()

    private val LIST = kotlinx.serialization.builtins.ListSerializer(Situation.serializer())

    /** A new id for a Situation made at [now], not one of [taken]. */
    fun newId(now: Long, taken: Collection<String>): String {
        var n = now
        while ("custom-${n.toString(RADIX)}" in taken) n++
        return "custom-${n.toString(RADIX)}"
    }

    private const val RADIX = 36

    // ------------------------------------------------------------------ the bundle

    /** [b] with [s]'s values in it. */
    fun apply(b: Behaviour, s: Situation): Behaviour {
        var out = b
        s.ring?.let { out = out.copy(offHours = ringOffHours(b.offHours, it, s.ringLabel)) }
        s.driveAnnounce?.let { out = out.copy(driveAnnounce = it) }
        s.driveAnswerFavourites?.let { out = out.copy(driveAnswerFavourites = it) }
        s.driveSilenceUnknown?.let { out = out.copy(driveSilenceUnknown = it) }
        s.autoAnswerHeadset?.let { out = out.copy(autoAnswerHeadset = it) }
        s.autoAnswerChosen?.let { out = out.copy(autoAnswerChosen = it) }
        s.speaker?.let { out = out.copy(speaker = it) }
        val reply = s.reply?.trim()?.takeIf { it.isNotEmpty() }
        if (reply != null) out = out.copy(quickReplies = listOf(reply) + out.quickReplies.filter { it.trim() != reply })
        when (s.replyToSilenced) {
            true -> out = out.copy(busyReply = true, busyReplyText = reply ?: out.busyReplyText)
            false -> out = out.copy(busyReply = false)
            null -> Unit
        }
        s.assistedDialling?.let { out = out.copy(assistedDialling = it) }
        s.localSimHint?.let { out = out.copy(localSimHint = it) }
        return out
    }

    /**
     * Off hours for all day with [ring]; silenced, never rejected, so the calls still show as missed. A label that's
     * gone (or none chosen) lets Favourites ring rather than nobody.
     */
    private fun ringOffHours(o: OffHours, ring: SituationRing, label: String?): OffHours {
        val allDay = Schedule(Schedule.ALL_DAYS, 0, 0)
        return when (ring) {
            SituationRing.EVERYONE -> o.copy(enabled = false)
            SituationRing.CONTACTS -> o.copy(enabled = true, schedule = allDay, allow = OffHoursAllow.CONTACTS, action = BlockAction.SILENCE)
            SituationRing.FAVOURITES -> o.copy(enabled = true, schedule = allDay, allow = OffHoursAllow.FAVOURITES, action = BlockAction.SILENCE)
            SituationRing.LABEL -> label?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { o.copy(enabled = true, schedule = allDay, allow = OffHoursAllow.LABEL, labelTitle = it, labelId = null, action = BlockAction.SILENCE) }
                ?: o.copy(enabled = true, schedule = allDay, allow = OffHoursAllow.FAVOURITES, action = BlockAction.SILENCE)
        }
    }

    /**
     * What to put back: each behaviour as it was [before], unless it was changed by hand since the Situation set it
     * ([current] no longer what was [applied]), when the change is kept.
     */
    fun restore(before: Behaviour, applied: Behaviour, current: Behaviour): Behaviour {
        fun <T> back(now: T, set: T, was: T): T = if (now == set) was else now
        return Behaviour(
            offHours = back(current.offHours, applied.offHours, before.offHours),
            driveAnnounce = back(current.driveAnnounce, applied.driveAnnounce, before.driveAnnounce),
            driveAnswerFavourites = back(current.driveAnswerFavourites, applied.driveAnswerFavourites, before.driveAnswerFavourites),
            driveSilenceUnknown = back(current.driveSilenceUnknown, applied.driveSilenceUnknown, before.driveSilenceUnknown),
            autoAnswerHeadset = back(current.autoAnswerHeadset, applied.autoAnswerHeadset, before.autoAnswerHeadset),
            autoAnswerChosen = back(current.autoAnswerChosen, applied.autoAnswerChosen, before.autoAnswerChosen),
            speaker = back(current.speaker, applied.speaker, before.speaker),
            quickReplies = back(current.quickReplies, applied.quickReplies, before.quickReplies),
            busyReply = back(current.busyReply, applied.busyReply, before.busyReply),
            busyReplyText = back(current.busyReplyText, applied.busyReplyText, before.busyReplyText),
            assistedDialling = back(current.assistedDialling, applied.assistedDialling, before.assistedDialling),
            localSimHint = back(current.localSimHint, applied.localSimHint, before.localSimHint),
        )
    }

    /** The outcome of switching: the state to keep and the behaviours to write. */
    data class Switch(val state: SituationState, val behaviour: Behaviour)

    /** What was there before any Situation: [current] with the one on now taken back out. */
    fun base(state: SituationState, current: Behaviour): Behaviour {
        val before = state.before ?: return current
        val applied = state.applied ?: return current
        return if (state.activeId == null) current else restore(before, applied, current)
    }

    /**
     * Switches [s] on. Another one on now is taken out first, so the snapshot is always what was set before any
     * Situation, and turning this one off puts that back.
     */
    fun turnOn(state: SituationState, current: Behaviour, s: Situation, cause: SituationCause, now: Long): Switch {
        val before = base(state, current)
        val applied = apply(before, s)
        val held = if (cause == SituationCause.MANUAL) state.held - s.id else state.held
        return Switch(SituationState(s.id, cause, now, before, applied, held), applied)
    }

    /**
     * Switches off the one on now and puts back what was there. [byHand] holds it off while its window or device still
     * holds (so it doesn't switch itself straight back on); [plan] lets go once that stops.
     */
    fun turnOff(state: SituationState, current: Behaviour, byHand: Boolean): Switch {
        val id = state.activeId ?: return Switch(state, current)
        val held = if (byHand) (state.held + id).distinct() else state.held
        return Switch(SituationState(held = held), base(state, current))
    }

    // ------------------------------------------------------------------ triggers

    /** Why [s] wants to be on now, or null: a connected device first, then its window. */
    fun trigger(s: Situation, sig: SituationSignals): SituationCause? = when {
        deviceHolds(s, sig) -> SituationCause.DEVICE
        s.schedule?.isActive(sig.clock.day, sig.clock.minuteOfDay) == true -> SituationCause.SCHEDULE
        else -> null
    }

    private fun deviceHolds(s: Situation, sig: SituationSignals): Boolean = when (s.device) {
        null -> false
        DeviceTrigger.CAR -> sig.car
        DeviceTrigger.ANY_BLUETOOTH -> sig.car || sig.bluetoothAudio
        DeviceTrigger.NAMED -> s.deviceName?.trim()?.takeIf { it.isNotEmpty() }?.let { n -> sig.audioNames.any { it.trim().equals(n, ignoreCase = true) } } == true
    }

    /** Minutes since [s]'s window began, while it is in it (0 at its start); null outside it or without one. */
    fun minutesIntoWindow(s: Schedule, clock: PolicyClock): Int? {
        if (!s.isActive(clock.day, clock.minuteOfDay)) return null
        val start = s.startMinute.coerceIn(0, MINUTES_A_DAY - 1)
        val allDay = s.startMinute == s.endMinute || (start == 0 && s.endMinute >= MINUTES_A_DAY)
        return when {
            allDay -> clock.minuteOfDay
            clock.minuteOfDay >= start -> clock.minuteOfDay - start
            // After midnight, in a window that began the day before.
            else -> clock.minuteOfDay + MINUTES_A_DAY - start
        }
    }

    private const val MINUTES_A_DAY = 1440

    /**
     * The Situation the triggers want now, or null. A connected device wins over a time window; of overlapping windows
     * the one that began last wins (a meeting inside a working day), and on a tie the one listed first. Held ones
     * ([SituationState.held]) don't count.
     */
    fun wanted(list: List<Situation>, sig: SituationSignals, held: Collection<String>): Pair<Situation, SituationCause>? {
        val candidates = list.filter { it.id !in held && it.changesSomething }.mapIndexedNotNull { i, s -> trigger(s, sig)?.let { Triple(s, it, i) } }
        val device = candidates.firstOrNull { it.second == SituationCause.DEVICE }
        if (device != null) return device.first to device.second
        return candidates.minWithOrNull(
            compareBy<Triple<Situation, SituationCause, Int>> { c -> c.first.schedule?.let { minutesIntoWindow(it, sig.clock) } ?: Int.MAX_VALUE }.thenBy { it.third },
        )?.let { it.first to it.second }
    }

    /** What [plan] says to do. */
    sealed interface Step {
        data object Keep : Step
        data object Off : Step
        data class On(val situation: Situation, val cause: SituationCause) : Step
    }

    /** A [Step] and the held list it leaves (holds whose trigger stopped are released). */
    data class Plan(val step: Step, val held: List<String>)

    /**
     * What the triggers ask for now. A Situation switched on by hand stays on until it is switched off by hand (or
     * deleted). One switched on by itself goes off when its window ends or its device goes, or gives way to one the
     * triggers want more.
     */
    fun plan(state: SituationState, list: List<Situation>, sig: SituationSignals): Plan {
        val held = state.held.filter { id -> list.firstOrNull { it.id == id }?.let { trigger(it, sig) } != null }
        val active = state.activeId?.let { id -> list.firstOrNull { it.id == id } }
        if (state.activeId != null && active == null) return Plan(Step.Off, held)
        if (active != null && state.cause == SituationCause.MANUAL) return Plan(Step.Keep, held)
        val want = wanted(list, sig, held)
        val step = when {
            active == null -> want?.let { Step.On(it.first, it.second) } ?: Step.Keep
            want == null -> Step.Off
            want.first.id != active.id -> Step.On(want.first, want.second)
            // Still wanted; its cause may change (the car connected inside its window), which changes nothing to undo.
            else -> Step.Keep
        }
        return Plan(step, held)
    }

    /**
     * The next time a window starts or ends after [now] (within the next eight days), so the triggers are looked at
     * again then; null when no Situation has a window.
     */
    fun nextChange(list: List<Situation>, now: Long, zone: ZoneId): Long? {
        val windows = list.mapNotNull { it.schedule }
        if (windows.isEmpty()) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).truncatedTo(ChronoUnit.DAYS)
        var best: Long? = null
        for (d in 0..LOOK_AHEAD_DAYS) {
            val day = today.plusDays(d.toLong())
            for (w in windows) {
                for (m in listOf(w.startMinute, w.endMinute)) {
                    val t = day.plusMinutes(m.coerceIn(0, MINUTES_A_DAY).toLong()).toInstant().toEpochMilli()
                    if (t > now && (best == null || t < best)) best = t
                }
            }
        }
        return best
    }

    private const val LOOK_AHEAD_DAYS = 8

    /** The SIM for calls without one of their own while [state] is on, or null. */
    fun activeSim(state: SituationState, list: List<Situation>): String? =
        state.activeId?.let { id -> list.firstOrNull { it.id == id } }?.simId?.takeIf { it.isNotBlank() }

    /**
     * A restored backup's Situations merged with this phone's: one that is only in the backup is added; where both
     * have one, this phone's wins unless it is still a built-in as it came ([defaults]).
     */
    fun merge(here: List<Situation>, backup: List<Situation>, defaults: List<Situation>): List<Situation> {
        val fresh = defaults.associateBy { it.id }
        val theirs = backup.associateBy { it.id }
        val kept = here.map { h -> if (fresh[h.id] == h) theirs[h.id] ?: h else h }
        val added = backup.filter { b -> here.none { it.id == b.id } }
        return normalise(kept + added, defaults)
    }
}
