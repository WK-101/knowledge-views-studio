package app.parley.common.situations

import app.parley.common.BlockAction
import app.parley.common.Codecs
import app.parley.common.LabelRefs
import app.parley.common.OffHours
import app.parley.common.OffHoursAllow
import app.parley.common.PolicyClock
import app.parley.common.Schedule
import app.parley.common.calls.SpeakerDefault
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

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

    /** The phone is on a network in another country (roaming abroad; national roaming doesn't count). */
    ROAMING,
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
    /**
     * The label's group row on this phone, so a rename made anywhere is followed ([Situations.followLabel]). Row ids
     * are this phone's own: left out of backups, where the title is matched instead.
     */
    val ringLabelId: Long? = null,
    /** The label it names was deleted: Favourites ring instead until a label is chosen again (the summary says so). */
    val ringLabelGone: Boolean = false,
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
) {
    fun encode(): String = Codecs.full.encodeToString(serializer(), this)

    companion object {
        /** Reads [encode]d behaviours; null when unreadable. */
        fun decode(text: String?): Behaviour? =
            if (text.isNullOrBlank()) null else runCatching { Codecs.fullTolerant.decodeFromString(serializer(), text) }.getOrNull()
    }
}

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
    /**
     * Set while [applied] is being written: a process death in between leaves the stores half switched (or with the
     * Situation before's values), so the next look writes [applied] again before anything else.
     */
    val pending: Boolean = false,
    /**
     * When a Situation switched on by hand goes off by itself ("For 1 hour", "Until 18:00"), or null: until it is
     * switched off by hand. Chosen each time it is switched on, never a setting.
     */
    val until: Long? = null,
) {
    fun encode(): String = Codecs.full.encodeToString(serializer(), this)

    companion object {
        /** Reads [encode]d state; unreadable input reads as nothing on (and nothing to restore). */
        fun decode(text: String?): SituationState =
            if (text.isNullOrBlank()) {
                SituationState()
            } else {
                runCatching { Codecs.fullTolerant.decodeFromString(serializer(), text) }.getOrDefault(SituationState())
            }
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
    /** A SIM is on a network in another country. */
    val roaming: Boolean = false,
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
        s.ring?.let { out = out.copy(offHours = ringOffHours(b.offHours, it, s.ringLabel.takeUnless { s.ringLabelGone })) }
        s.driveAnnounce?.let { out = out.copy(driveAnnounce = it) }
        s.driveAnswerFavourites?.let { out = out.copy(driveAnswerFavourites = it) }
        s.driveSilenceUnknown?.let { out = out.copy(driveSilenceUnknown = it) }
        s.autoAnswerHeadset?.let { out = out.copy(autoAnswerHeadset = it) }
        s.autoAnswerChosen?.let { out = out.copy(autoAnswerChosen = it) }
        s.speaker?.let { out = out.copy(speaker = it) }
        out = withReply(out, s)
        s.assistedDialling?.let { out = out.copy(assistedDialling = it) }
        s.localSimHint?.let { out = out.copy(localSimHint = it) }
        return out
    }

    /** The reply offered first, and whether it is offered to people silenced. */
    private fun withReply(b: Behaviour, s: Situation): Behaviour {
        val reply = s.reply?.trim()?.takeIf { it.isNotEmpty() }
        val out = if (reply == null) b else b.copy(quickReplies = listOf(reply) + b.quickReplies.filter { it.trim() != reply })
        return when (s.replyToSilenced) {
            true -> out.copy(busyReply = true, busyReplyText = reply ?: out.busyReplyText)
            false -> out.copy(busyReply = false)
            null -> out
        }
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
     * ([current] no longer what was [applied]), when the change is kept. Off hours is compared field by field, so a
     * change to one of its fields (by hand, or a label rename Parley followed) keeps that field only and the rest
     * still comes back.
     */
    fun restore(before: Behaviour, applied: Behaviour, current: Behaviour): Behaviour {
        return Behaviour(
            offHours = restoreOffHours(before.offHours, applied.offHours, current.offHours),
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

    private fun <T> back(now: T, set: T, was: T): T = if (now == set) was else now

    private fun restoreOffHours(before: OffHours, applied: OffHours, current: OffHours): OffHours {
        val out = OffHours(
            enabled = back(current.enabled, applied.enabled, before.enabled),
            schedule = back(current.schedule, applied.schedule, before.schedule),
            allow = back(current.allow, applied.allow, before.allow),
            labelId = back(current.labelId, applied.labelId, before.labelId),
            labelTitle = back(current.labelTitle, applied.labelTitle, before.labelTitle),
            action = back(current.action, applied.action, before.action),
        )
        // "Only this label" with no label would silence everyone: off hours switches itself off, as when a label goes.
        return if (out.allow == OffHoursAllow.LABEL && out.labelTitle.isNullOrBlank()) LabelRefs.labelGone(out) else out
    }

    /** The outcome of switching: the state to keep and the behaviours to write. */
    data class Outcome(val state: SituationState, val behaviour: Behaviour)

    /** What was there before any Situation: [current] with the one on now taken back out. */
    fun base(state: SituationState, current: Behaviour): Behaviour {
        val before = state.before ?: return current
        val applied = state.applied ?: return current
        return if (state.activeId == null) current else restore(before, applied, current)
    }

    /**
     * Switches [s] on. Another one on now is taken out first, so the snapshot is always what was set before any
     * Situation, and turning this one off puts that back. Switched on by hand over another one, that other one is held
     * off ([SituationState.held]) like one switched off by hand: moving away from it (the tile's next Situation, then
     * Off) must not let its window or device switch it straight back on. [plan] lets go once its trigger stops.
     */
    fun turnOn(state: SituationState, current: Behaviour, s: Situation, cause: SituationCause, now: Long, until: Long? = null): Outcome {
        val before = base(state, current)
        val applied = apply(before, s)
        val held = if (cause == SituationCause.MANUAL) {
            (state.held - s.id + listOfNotNull(state.activeId?.takeIf { it != s.id })).distinct()
        } else {
            state.held
        }
        val end = until?.takeIf { cause == SituationCause.MANUAL && it > now }
        return Outcome(SituationState(s.id, cause, now, before, applied, held, until = end), applied)
    }

    /**
     * Switches off the one on now and puts back what was there. [byHand] holds it off while its window or device still
     * holds (so it doesn't switch itself straight back on); [plan] lets go once that stops.
     */
    fun turnOff(state: SituationState, current: Behaviour, byHand: Boolean): Outcome {
        val id = state.activeId ?: return Outcome(state, current)
        val held = if (byHand) (state.held + id).distinct() else state.held
        return Outcome(SituationState(held = held), base(state, current))
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
        DeviceTrigger.ROAMING -> sig.roaming
        DeviceTrigger.NAMED -> s.deviceName?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { n -> sig.audioNames.any { it.trim().equals(n, ignoreCase = true) } } == true
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
            compareBy<Triple<Situation, SituationCause, Int>> { c -> c.first.schedule?.let { minutesIntoWindow(it, sig.clock) } ?: Int.MAX_VALUE }
                .thenBy { it.third },
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
     * What the triggers ask for now. A Situation switched on by hand stays on until the end chosen for it
     * ([SituationState.until]) or, without one, until it is switched off by hand (or deleted); at its end it is held
     * off like one switched off by hand, so its own window or device doesn't switch it straight back on. One switched
     * on by itself goes off when its window ends or its device goes, or gives way to one the triggers want more.
     */
    fun plan(state: SituationState, list: List<Situation>, sig: SituationSignals): Plan {
        val held = state.held.filter { id -> list.firstOrNull { it.id == id }?.let { trigger(it, sig) } != null }
        val active = state.activeId?.let { id -> list.firstOrNull { it.id == id } }
        if (state.activeId != null && active == null) return Plan(Step.Off, held)
        if (active != null && state.cause == SituationCause.MANUAL) return byHand(state, active, sig, held)
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
     * [plan] for [active], switched on by hand: kept until its chosen end, then off and held while its own window or
     * device still holds.
     */
    private fun byHand(state: SituationState, active: Situation, sig: SituationSignals, held: List<String>): Plan {
        val ended = state.until?.let { sig.clock.millis >= it } == true
        if (!ended) return Plan(Step.Keep, held)
        return Plan(Step.Off, if (trigger(active, sig) != null) (held + active.id).distinct() else held)
    }

    /**
     * The next time a window starts or ends after [now] (within the next eight days), or the Situation on now reaches
     * the end chosen for it ([until]), so the triggers are looked at again then; null when there is neither. Each edge is a wall-clock time in [zone] on its own day, so a
     * day of 23 or 25 hours (a clock change) still has its 22:00 at 22:00; an edge in the hour a clock skips is at the
     * first minute after it.
     */
    fun nextChange(list: List<Situation>, now: Long, zone: ZoneId, until: Long? = null): Long? {
        val end = until?.takeIf { it > now }
        val windows = list.mapNotNull { it.schedule }
        if (windows.isEmpty()) return end
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val minutes = windows.flatMap { listOf(it.startMinute, it.endMinute) }.map { it.coerceIn(0, MINUTES_A_DAY) }.distinct()
        return (0..LOOK_AHEAD_DAYS).asSequence()
            .flatMap { d ->
                val day = today.plusDays(d.toLong())
                minutes.asSequence().map { m ->
                    // 24:00 is the next day's midnight.
                    val edge = if (m >= MINUTES_A_DAY) day.plusDays(1).atStartOfDay(zone) else day.atTime(LocalTime.of(m / 60, m % 60)).atZone(zone)
                    edge.toInstant().toEpochMilli()
                }
            }
            .filter { it > now }
            .plus(listOfNotNull(end))
            .minOrNull()
    }

    /**
     * When to look again at a Situation on now because a SIM roams abroad ([DeviceTrigger.ROAMING]), or null. Android
     * sends no broadcast without a permission when the phone is back on its home network (and coming home within the
     * same time zone changes no clock), so while one is on by roaming the triggers are looked at again every
     * [ROAMING_RECHECK_MS]: it goes off within the hour of coming home rather than staying on until the next call.
     */
    fun roamingRecheck(state: SituationState, list: List<Situation>, now: Long): Long? {
        if (state.cause != SituationCause.DEVICE) return null
        val on = state.activeId?.let { id -> list.firstOrNull { it.id == id } } ?: return null
        return if (on.device == DeviceTrigger.ROAMING) now + ROAMING_RECHECK_MS else null
    }

    const val ROAMING_RECHECK_MS = 3_600_000L

    /** When the notice says the Situation on now ends ([noticeEnd]). */
    sealed interface NoticeEnd {
        /** At this time of day ([minute] past midnight, in the phone's zone). */
        data class At(val minute: Int) : NoticeEnd

        /** When it is switched off by hand. */
        data object WhenTurnedOff : NoticeEnd

        /** When what switched it on goes (its device, or the SIM back home). */
        data object WhileTriggered : NoticeEnd
    }

    /**
     * What the silent notice says about [s]'s end while it is on with [state]: switched on by hand, its chosen end
     * ([untilMinute], the time of day of [SituationState.until]) or when turned off; by its window, the window's end;
     * by a device or roaming, while that lasts. Never "until you turn it off" for one that goes off by itself.
     */
    fun noticeEnd(state: SituationState, s: Situation, untilMinute: Int?): NoticeEnd = when (state.cause) {
        SituationCause.MANUAL -> untilMinute?.takeIf { state.until != null }?.let { NoticeEnd.At(it) } ?: NoticeEnd.WhenTurnedOff
        SituationCause.SCHEDULE -> s.schedule?.takeIf { it.startMinute != it.endMinute }
            ?.let { NoticeEnd.At(it.endMinute % MINUTES_A_DAY) } ?: NoticeEnd.WhileTriggered
        SituationCause.DEVICE -> NoticeEnd.WhileTriggered
    }

    // ------------------------------------------------------------------ switched on by hand

    /** How long a Situation switched on by hand stays on: asked each time, never a setting. */
    sealed interface End {
        /** When it goes off by itself; null: when switched off by hand. */
        val at: Long?

        /** For 1 hour. */
        data class ForAnHour(override val at: Long) : End

        /** Until the end of its window, or 18:00 for one without a window ([minute]: that time of day). */
        data class UntilTime(override val at: Long, val minute: Int) : End

        /** Until it is switched off by hand. */
        data object UntilTurnedOff : End {
            override val at: Long? get() = null
        }
    }

    /** The end of a working day, offered to a Situation with no window of its own. */
    const val DAY_END_MINUTE = 18 * 60

    /**
     * The ends offered when [s] is switched on by hand at [now]: For 1 hour; Until the end of its window (or 18:00
     * today when it has none), when that is still to come and not the same as in an hour; Until I turn it off.
     */
    fun endChoices(s: Situation, now: Long, zone: ZoneId): List<End> {
        val hour = now + HOUR_MS
        val minute = s.schedule?.takeIf { it.startMinute != it.endMinute }?.endMinute?.rem(MINUTES_A_DAY)
        val until = if (minute != null) {
            nextTimeOfDay(minute, now, zone)
        } else {
            // 18:00 today only: after it, the working day has ended already.
            atMinute(0, DAY_END_MINUTE, now, zone).takeIf { it > now }
        }
        return listOfNotNull(
            End.ForAnHour(hour),
            until?.takeIf { it != hour }?.let { End.UntilTime(it, minute ?: DAY_END_MINUTE) },
            End.UntilTurnedOff,
        )
    }

    /** The time [days] after [now]'s day when the clock in [zone] reads [minute]. */
    private fun atMinute(days: Long, minute: Int, now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(days)
            .atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()

    /** The next time the clock in [zone] reads [minute] after [now]. */
    private fun nextTimeOfDay(minute: Int, now: Long, zone: ZoneId): Long =
        atMinute(0, minute, now, zone).takeIf { it > now } ?: atMinute(1, minute, now, zone)

    private const val HOUR_MS = 3_600_000L

    /**
     * Whether [s] silences anyone while it is on: it lets only some people ring (the others ring silently and show as
     * missed calls). While the one on now does, a silent ongoing notice says so, with Turn off.
     */
    fun silencesAnyone(s: Situation?): Boolean = s != null && s.ring != null && s.ring != SituationRing.EVERYONE

    private const val LOOK_AHEAD_DAYS = 8

    /** The SIM for calls without one of their own while [state] is on, or null. */
    fun activeSim(state: SituationState, list: List<Situation>): String? =
        state.activeId?.let { id -> list.firstOrNull { it.id == id } }?.simId?.takeIf { it.isNotBlank() }

    /**
     * A restored backup's Situations merged with this phone's: one that is only in the backup is added; where both
     * have one, this phone's wins unless it is still a built-in as it came ([defaults]). The backup's label rows mean
     * nothing here: its labels are matched by title.
     */
    fun merge(here: List<Situation>, backup: List<Situation>, defaults: List<Situation>): List<Situation> {
        val fresh = defaults.associateBy { it.id }
        val theirs = backup.map { it.copy(ringLabelId = null) }.associateBy { it.id }
        val kept = here.map { h -> if (fresh[h.id] == h) theirs[h.id] ?: h else h }
        val added = theirs.values.filter { b -> here.none { it.id == b.id } }
        return normalise(kept + added, defaults)
    }

    // ------------------------------------------------------------------ labels

    /**
     * [s] with its "who may ring" label checked against the labels on this phone ([labels]: group row → title; null
     * when they can't be read, which changes nothing): its row's current title (a rename made in any app), else the
     * row of a label with its title (a backup, a label made again), else gone, when Favourites ring instead.
     */
    fun followLabel(s: Situation, labels: Map<Long, String>?): Situation {
        if (s.ring != SituationRing.LABEL || labels == null) return s
        val title = s.ringLabel?.let(LabelRefs::key)?.takeIf { it.isNotEmpty() } ?: return s
        s.ringLabelId?.let { labels[it] }?.let(LabelRefs::key)?.takeIf { it.isNotEmpty() }?.let { return s.copy(ringLabel = it, ringLabelGone = false) }
        val match = labels.entries.firstOrNull { LabelRefs.key(it.value) == title }
        return if (match != null) {
            s.copy(ringLabel = title, ringLabelId = match.key, ringLabelGone = false)
        } else {
            s.copy(ringLabelId = null, ringLabelGone = true)
        }
    }

    /** Labels renamed or merged in Parley ([renames]: old title → new title): the Situations naming them follow. */
    fun labelsRenamed(list: List<Situation>, renames: Map<String, String>): List<Situation> {
        val m = renames.mapKeys { LabelRefs.key(it.key) }.mapValues { LabelRefs.key(it.value) }
        return list.map { s -> s.ringLabel?.let { m[LabelRefs.key(it)] }?.let { to -> s.copy(ringLabel = to, ringLabelGone = false) } ?: s }
    }

    /** Labels deleted in Parley: a Situation letting one of them ring lets Favourites ring instead, and says so. */
    fun labelsDeleted(list: List<Situation>, titles: Set<String>): List<Situation> = list.map { s ->
        if (s.ring == SituationRing.LABEL && LabelRefs.refersTo(s.ringLabel, titles)) s.copy(ringLabelId = null, ringLabelGone = true) else s
    }

    /**
     * The snapshot after Parley followed a label rename in off hours: [SituationState.before] and
     * [SituationState.applied] are renamed the same way, so the rename counts neither as a change made by hand nor as
     * one to undo.
     */
    fun stateLabelsRenamed(state: SituationState, renames: Map<String, String>): SituationState = state.copy(
        before = state.before?.let { it.copy(offHours = LabelRefs.renameOffHours(it.offHours, renames)) },
        applied = state.applied?.let { it.copy(offHours = LabelRefs.renameOffHours(it.offHours, renames)) },
    )

    /** The snapshot after a label was deleted: what off hours had for it goes the way off hours itself went. */
    fun stateLabelsDeleted(state: SituationState, titles: Set<String>): SituationState {
        fun gone(o: OffHours) = if (o.allow == OffHoursAllow.LABEL && LabelRefs.refersTo(o.labelTitle, titles)) LabelRefs.labelGone(o) else o
        return state.copy(
            before = state.before?.let { it.copy(offHours = gone(it.offHours)) },
            applied = state.applied?.let { it.copy(offHours = gone(it.offHours)) },
        )
    }
}
