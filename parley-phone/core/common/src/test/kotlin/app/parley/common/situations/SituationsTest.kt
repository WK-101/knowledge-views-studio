package app.parley.common.situations

import app.parley.common.AllowReason
import app.parley.common.BlockAction
import app.parley.common.BlockReason
import app.parley.common.BlockRule
import app.parley.common.CallPolicy
import app.parley.common.Decision
import app.parley.common.IncomingCallFacts
import app.parley.common.OffHours
import app.parley.common.OffHoursAllow
import app.parley.common.PolicyClock
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.Schedule
import app.parley.common.ScreeningSettings
import app.parley.common.calls.SpeakerDefault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneOffset

class SituationsTest {
    private val replies = mapOf(SituationKind.DRIVING to "Driving, call you back", SituationKind.MEETING to "In a meeting")
    private val defaults = Situations.builtIns { replies[it] }
    private fun s(id: String) = defaults.first { it.id == id }

    private val mine = Behaviour(
        offHours = OffHours(enabled = true, schedule = Schedule(Schedule.WEEKDAYS, 23 * 60, 6 * 60), allow = OffHoursAllow.CONTACTS, action = BlockAction.REJECT),
        driveAnnounce = false,
        autoAnswerHeadset = true,
        speaker = SpeakerDefault.UNKNOWN_NUMBERS,
        quickReplies = listOf("Can't talk now", "In a meeting"),
        busyReplyText = "Later",
        localSimHint = false,
    )

    private fun clock(day: DayOfWeek, h: Int, m: Int = 0) = PolicyClock(0L, day, h * 60 + m)
    private fun sig(day: DayOfWeek, h: Int, m: Int = 0, car: Boolean = false, names: List<String> = emptyList(), bt: Boolean = false) =
        SituationSignals(clock(day, h, m), car, names, bt)

    @Test fun turning_on_applies_the_bundle_and_off_puts_back_exactly_what_was_set() {
        val on = Situations.turnOn(SituationState(), mine, s(Situations.MEETING), SituationCause.MANUAL, 100)
        val b = on.behaviour
        assertTrue(b.offHours.enabled)
        assertEquals(OffHoursAllow.FAVOURITES, b.offHours.allow)
        assertEquals(BlockAction.SILENCE, b.offHours.action)
        assertTrue(b.offHours.schedule.isActive(DayOfWeek.SUNDAY, 12 * 60))
        assertEquals(listOf("In a meeting", "Can't talk now"), b.quickReplies)
        assertTrue(b.busyReply)
        assertEquals("In a meeting", b.busyReplyText)
        // Left alone.
        assertEquals(SpeakerDefault.UNKNOWN_NUMBERS, b.speaker)
        assertEquals(mine.autoAnswerHeadset, b.autoAnswerHeadset)
        assertEquals(Situations.MEETING, on.state.activeId)
        assertEquals(mine, on.state.before)

        val off = Situations.turnOff(on.state, b, byHand = false)
        assertEquals(mine, off.behaviour)
        assertNull(off.state.activeId)
        assertNull(off.state.before)
    }

    @Test fun a_change_made_by_hand_while_on_is_kept_and_the_rest_comes_back() {
        val on = Situations.turnOn(SituationState(), mine, s(Situations.MEETING), SituationCause.MANUAL, 100)
        // While in the meeting: the speaker and the replies are changed by hand.
        val touched = on.behaviour.copy(speaker = SpeakerDefault.ALWAYS, quickReplies = listOf("Edited"))
        val off = Situations.turnOff(on.state, touched, byHand = true)
        assertEquals(SpeakerDefault.ALWAYS, off.behaviour.speaker)
        assertEquals(listOf("Edited"), off.behaviour.quickReplies)
        assertEquals(mine.offHours, off.behaviour.offHours)
        assertEquals(mine.busyReply, off.behaviour.busyReply)
    }

    @Test fun switching_from_one_to_another_keeps_the_first_snapshot() {
        val meeting = Situations.turnOn(SituationState(), mine, s(Situations.MEETING), SituationCause.MANUAL, 100)
        val driving = Situations.turnOn(meeting.state, meeting.behaviour, s(Situations.DRIVING), SituationCause.MANUAL, 200)
        // Meeting's off hours went with it; Driving's own switches are on.
        assertEquals(mine.offHours, driving.behaviour.offHours)
        assertTrue(driving.behaviour.driveAnnounce && driving.behaviour.driveAnswerFavourites)
        assertEquals(listOf("Driving, call you back", "Can't talk now", "In a meeting"), driving.behaviour.quickReplies)
        assertEquals(mine, driving.state.before)
        assertEquals(mine, Situations.turnOff(driving.state, driving.behaviour, byHand = true).behaviour)
    }

    @Test fun ring_choices_set_off_hours_and_a_missing_label_falls_back_to_favourites() {
        val everyone = Situations.apply(mine, Situation("x", ring = SituationRing.EVERYONE))
        assertFalse(everyone.offHours.enabled)
        val label = Situations.apply(mine, Situation("x", ring = SituationRing.LABEL, ringLabel = "Family"))
        assertEquals(OffHoursAllow.LABEL, label.offHours.allow)
        assertEquals("Family", label.offHours.labelTitle)
        val noLabel = Situations.apply(mine, Situation("x", ring = SituationRing.LABEL))
        assertEquals(OffHoursAllow.FAVOURITES, noLabel.offHours.allow)
        val noReply = Situations.apply(mine, Situation("x", replyToSilenced = false))
        assertFalse(noReply.busyReply)
    }

    /** A Situation only sets off hours: emergency calls and "who can always reach me" still ring. */
    @Test fun emergency_calls_and_always_allowed_people_are_never_stopped() {
        val night = Situations.apply(Behaviour(), s(Situations.NIGHT))
        val settings = ScreeningSettings(offHours = night.offHours)
        val clock = PolicyClock(1_000L, DayOfWeek.TUESDAY, 3 * 60)
        val stranger = IncomingCallFacts(number = "+33699999999", hidden = false, isContact = false, countryIso = "FR")
        val blocked = CallPolicy.decide(stranger, emptyList(), settings, clock).decision
        assertTrue(blocked is Decision.Block && blocked.reason == BlockReason.OFF_HOURS && blocked.action == BlockAction.SILENCE)
        val emergency = stranger.copy(number = "112", isEmergency = true)
        assertEquals(AllowReason.EMERGENCY, CallPolicy.decide(emergency, emptyList(), settings, clock).allowedBy)
        val callBack = stranger.copy(inEmergencyWindow = true)
        assertEquals(Decision.Allow, CallPolicy.decide(callBack, emptyList(), settings, clock).decision)
        val always = BlockRule(pattern = "+33699999999", type = RuleType.EXACT, kind = RuleKind.ALLOW)
        assertEquals(AllowReason.RULE, CallPolicy.decide(stranger, listOf(always), settings, clock).allowedBy)
        val family = BlockRule(pattern = "Family", type = RuleType.LABEL, kind = RuleKind.ALLOW, label = "Family")
        val cousin = stranger.copy(isContact = true, contactLabels = setOf("Family"))
        assertEquals(Decision.Allow, CallPolicy.decide(cousin, listOf(family), settings, clock).decision)
        val favourite = stranger.copy(isContact = true, contactStarred = true)
        assertEquals(Decision.Allow, CallPolicy.decide(favourite, emptyList(), settings, clock).decision)
    }

    @Test fun a_window_switches_on_and_off_by_itself() {
        val night = s(Situations.NIGHT).copy(schedule = Situations.NIGHT_WINDOW)
        val list = defaults.map { if (it.id == night.id) night else it }
        val evening = Situations.plan(SituationState(), list, sig(DayOfWeek.MONDAY, 22, 30))
        assertEquals(Situations.Step.On(night, SituationCause.SCHEDULE), evening.step)
        val on = Situations.turnOn(SituationState(), mine, night, SituationCause.SCHEDULE, 0).state
        assertEquals(Situations.Step.Keep, Situations.plan(on, list, sig(DayOfWeek.TUESDAY, 6, 59)).step)
        assertEquals(Situations.Step.Off, Situations.plan(on, list, sig(DayOfWeek.TUESDAY, 7, 0)).step)
    }

    @Test fun switched_on_by_hand_it_stays_until_switched_off_by_hand() {
        val night = s(Situations.NIGHT).copy(schedule = Situations.NIGHT_WINDOW)
        val on = Situations.turnOn(SituationState(), mine, night, SituationCause.MANUAL, 0).state
        assertEquals(Situations.Step.Keep, Situations.plan(on, listOf(night), sig(DayOfWeek.TUESDAY, 12, 0)).step)
    }

    @Test fun switched_off_by_hand_it_stays_off_until_its_window_ends() {
        val night = s(Situations.NIGHT).copy(schedule = Situations.NIGHT_WINDOW)
        val on = Situations.turnOn(SituationState(), mine, night, SituationCause.SCHEDULE, 0)
        val off = Situations.turnOff(on.state, on.behaviour, byHand = true).state
        assertEquals(listOf(night.id), off.held)
        val later = Situations.plan(off, listOf(night), sig(DayOfWeek.TUESDAY, 2))
        assertEquals(Situations.Step.Keep, later.step)
        assertEquals(listOf(night.id), later.held)
        // The window ended: let go, so it comes on again tomorrow night.
        val morning = Situations.plan(off.copy(held = later.held), listOf(night), sig(DayOfWeek.TUESDAY, 8))
        assertEquals(emptyList<String>(), morning.held)
        val tomorrow = Situations.plan(off.copy(held = morning.held), listOf(night), sig(DayOfWeek.TUESDAY, 23))
        assertEquals(Situations.Step.On(night, SituationCause.SCHEDULE), tomorrow.step)
    }

    @Test fun overlapping_windows_the_one_that_began_last_wins_then_the_other_comes_back() {
        val work = Situation("work", name = "Work", ring = SituationRing.CONTACTS, schedule = Schedule(Schedule.WEEKDAYS, 9 * 60, 17 * 60))
        val meeting = s(Situations.MEETING).copy(schedule = Schedule(Schedule.WEEKDAYS, 10 * 60, 11 * 60))
        val list = listOf(meeting, work)
        assertEquals("work", Situations.wanted(list, sig(DayOfWeek.MONDAY, 9, 30), emptyList())?.first?.id)
        assertEquals(Situations.MEETING, Situations.wanted(list, sig(DayOfWeek.MONDAY, 10, 15), emptyList())?.first?.id)
        // Work came on at 9, Meeting takes over at 10 and Work comes back at 11, from the same snapshot.
        val w = Situations.turnOn(SituationState(), mine, work, SituationCause.SCHEDULE, 0)
        val step = Situations.plan(w.state, list, sig(DayOfWeek.MONDAY, 10, 0)).step
        assertEquals(Situations.Step.On(meeting, SituationCause.SCHEDULE), step)
        val m = Situations.turnOn(w.state, w.behaviour, meeting, SituationCause.SCHEDULE, 1)
        assertEquals(OffHoursAllow.FAVOURITES, m.behaviour.offHours.allow)
        assertEquals(Situations.Step.On(work, SituationCause.SCHEDULE), Situations.plan(m.state, list, sig(DayOfWeek.MONDAY, 11, 0)).step)
        val back = Situations.turnOn(m.state, m.behaviour, work, SituationCause.SCHEDULE, 2)
        assertEquals(mine, back.state.before)
        assertEquals(Situations.Step.Off, Situations.plan(back.state, list, sig(DayOfWeek.MONDAY, 17, 0)).step)
        // Same start: the one listed first.
        val twin = work.copy(id = "twin")
        assertEquals("work", Situations.wanted(listOf(work, twin), sig(DayOfWeek.MONDAY, 12), emptyList())?.first?.id)
        // A window over midnight began the day before.
        assertEquals(60 * 3, Situations.minutesIntoWindow(Situations.NIGHT_WINDOW, clock(DayOfWeek.TUESDAY, 1)))
    }

    @Test fun a_connected_car_wins_over_a_window_and_goes_with_the_car() {
        val night = s(Situations.NIGHT).copy(schedule = Situations.NIGHT_WINDOW)
        val driving = s(Situations.DRIVING).copy(device = DeviceTrigger.CAR)
        val list = listOf(driving, night)
        assertEquals(driving to SituationCause.DEVICE, Situations.wanted(list, sig(DayOfWeek.MONDAY, 23, car = true), emptyList()))
        assertEquals(night.id, Situations.wanted(list, sig(DayOfWeek.MONDAY, 23), emptyList())?.first?.id)
        val any = driving.copy(device = DeviceTrigger.ANY_BLUETOOTH)
        assertEquals(SituationCause.DEVICE, Situations.trigger(any, sig(DayOfWeek.MONDAY, 12, bt = true)))
        val named = driving.copy(device = DeviceTrigger.NAMED, deviceName = "My Golf")
        assertEquals(SituationCause.DEVICE, Situations.trigger(named, sig(DayOfWeek.MONDAY, 12, names = listOf("my golf "))))
        assertNull(Situations.trigger(named, sig(DayOfWeek.MONDAY, 12, names = listOf("Earbuds"), bt = true)))
    }

    /** After a reboot (or a process death) the state is read back and the next look puts back what was set. */
    @Test fun the_snapshot_survives_a_restart_and_is_put_back_when_the_window_ended_meanwhile() {
        val night = s(Situations.NIGHT).copy(schedule = Situations.NIGHT_WINDOW)
        val on = Situations.turnOn(SituationState(), mine, night, SituationCause.SCHEDULE, 5)
        val stored = on.state.encode()
        // The phone restarts the next morning.
        val read = SituationState.decode(stored)
        assertEquals(on.state, read)
        val plan = Situations.plan(read, listOf(night), sig(DayOfWeek.TUESDAY, 9))
        assertEquals(Situations.Step.Off, plan.step)
        assertEquals(mine, Situations.turnOff(read, on.behaviour, byHand = false).behaviour)
        // Unreadable state: nothing on, nothing restored.
        assertEquals(SituationState(), SituationState.decode("{not json"))
    }

    @Test fun the_next_look_is_at_the_next_window_edge() {
        val zone = ZoneOffset.UTC
        val at = LocalDateTime.of(2026, 10, 5, 21, 10).toInstant(zone).toEpochMilli()
        val night = s(Situations.NIGHT).copy(schedule = Situations.NIGHT_WINDOW)
        assertEquals(LocalDateTime.of(2026, 10, 5, 22, 0).toInstant(zone).toEpochMilli(), Situations.nextChange(listOf(night), at, zone))
        val late = LocalDateTime.of(2026, 10, 5, 23, 0).toInstant(zone).toEpochMilli()
        assertEquals(LocalDateTime.of(2026, 10, 6, 7, 0).toInstant(zone).toEpochMilli(), Situations.nextChange(listOf(night), late, zone))
        assertNull(Situations.nextChange(defaults, at, zone))
    }

    @Test fun the_list_keeps_its_built_ins_and_a_backup_merges_in() {
        val made = Situation("custom-1", name = "Gym", ring = SituationRing.FAVOURITES)
        assertEquals(defaults.map { it.id } + "custom-1", Situations.normalise(listOf(made), defaults).map { it.id })
        val list = Situations.normalise(defaults + made, defaults)
        assertEquals(list, Situations.decodeList(Situations.encodeList(list)))
        assertNull(Situations.decodeList("nonsense"))
        // Here: Night changed, Meeting as it came. The backup: its own Meeting, Night and a Situation of its own.
        val here = list.map { if (it.id == Situations.NIGHT) it.copy(ring = SituationRing.CONTACTS) else it }
        val backup = defaults.map {
            when (it.id) {
                Situations.MEETING -> it.copy(speaker = SpeakerDefault.ALWAYS)
                Situations.NIGHT -> it.copy(ring = SituationRing.EVERYONE)
                else -> it
            }
        } + Situation("custom-2", name = "Hospital", ring = SituationRing.LABEL, ringLabel = "Family")
        val merged = Situations.merge(here, backup, defaults)
        assertEquals(SpeakerDefault.ALWAYS, merged.first { it.id == Situations.MEETING }.speaker)
        assertEquals(SituationRing.CONTACTS, merged.first { it.id == Situations.NIGHT }.ring)
        assertEquals(listOf("custom-1", "custom-2"), merged.filterNot { it.builtIn }.map { it.id })
    }

    @Test fun the_sim_is_the_active_ones_and_ids_are_new() {
        val travelling = s(Situations.TRAVELLING).copy(simId = "sim-2")
        val on = Situations.turnOn(SituationState(), mine, travelling, SituationCause.MANUAL, 0).state
        assertEquals("sim-2", Situations.activeSim(on, listOf(travelling)))
        assertNull(Situations.activeSim(SituationState(), listOf(travelling)))
        val id = Situations.newId(36, emptyList())
        assertEquals("custom-10", id)
        assertEquals("custom-11", Situations.newId(36, listOf(id)))
        assertFalse(Situation("x").changesSomething)
        // Nothing to change: never wanted, so it can't switch itself on to do nothing.
        assertNull(Situations.wanted(listOf(Situation("x", schedule = Schedule())), sig(DayOfWeek.MONDAY, 12), emptyList()))
    }
}
