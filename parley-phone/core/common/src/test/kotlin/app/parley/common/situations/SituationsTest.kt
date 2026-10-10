package app.parley.common.situations

import app.parley.common.AllowReason
import app.parley.common.BlockAction
import app.parley.common.BlockReason
import app.parley.common.BlockRule
import app.parley.common.CallPolicy
import app.parley.common.Decision
import app.parley.common.IncomingCallFacts
import app.parley.common.LabelRefs
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
        offHours = OffHours(
            enabled = true, schedule = Schedule(Schedule.WEEKDAYS, 23 * 60, 6 * 60), allow = OffHoursAllow.CONTACTS, action = BlockAction.REJECT,
        ),
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

    // ------------------------------------------------------------------ labels, the tile, clock changes

    /** Off hours 22–07 for the label "Family" (the user's own), switched off. */
    private val family = mine.copy(
        offHours = OffHours(enabled = false, schedule = Situations.NIGHT_WINDOW, allow = OffHoursAllow.LABEL, labelTitle = "Family"),
    )

    @Test fun a_label_renamed_while_on_is_no_change_by_hand_and_all_day_silencing_goes() {
        val on = Situations.turnOn(SituationState(), family, s(Situations.MEETING), SituationCause.MANUAL, 0)
        // The Situation's off hours still carry the user's label title.
        assertEquals("Family", on.behaviour.offHours.labelTitle)
        // Family becomes Home: Parley renames off hours' label (LabelReferences) and the snapshot alike.
        val renames = mapOf("Family" to "Home")
        val current = on.behaviour.copy(offHours = LabelRefs.renameOffHours(on.behaviour.offHours, renames))
        val state = Situations.stateLabelsRenamed(on.state, renames)
        val off = Situations.turnOff(state, current, byHand = true).behaviour.offHours
        assertEquals(family.offHours.copy(labelTitle = "Home"), off)
        assertFalse(off.enabled)
        // Even had the snapshot not followed, the field changed is the only one kept: no all-day silencing remains.
        val old = Situations.turnOff(on.state, current, byHand = true).behaviour.offHours
        assertEquals(family.offHours.copy(labelTitle = "Home"), old)
    }

    @Test fun off_hours_come_back_field_by_field() {
        val on = Situations.turnOn(SituationState(), mine, s(Situations.NIGHT), SituationCause.MANUAL, 0)
        // Only the window is changed by hand while Night is on: it stays; Favourites and the silencing go.
        val evenings = Schedule(Schedule.WEEKDAYS, 21 * 60, 6 * 60)
        val touched = on.behaviour.copy(offHours = on.behaviour.offHours.copy(schedule = evenings))
        val back = Situations.turnOff(on.state, touched, byHand = true).behaviour.offHours
        assertEquals(mine.offHours.copy(schedule = evenings), back)
        assertEquals(BlockAction.REJECT, back.action)
        // A label kept by hand without a title can't stand: off hours switches itself off rather than silence everyone.
        val ownOn = family.copy(offHours = family.offHours.copy(enabled = true))
        val label = Situations.turnOn(SituationState(), ownOn, s(Situations.NIGHT), SituationCause.MANUAL, 0)
        val cleared = label.behaviour.copy(offHours = label.behaviour.offHours.copy(labelTitle = null))
        val restored = Situations.turnOff(label.state, cleared, byHand = true).behaviour.offHours
        assertFalse(restored.enabled)
        assertEquals(OffHoursAllow.CONTACTS, restored.allow)
    }

    @Test fun a_label_deleted_while_on_leaves_no_all_day_silencing_and_the_situation_falls_back() {
        val gym = Situation("gym", ring = SituationRing.LABEL, ringLabel = "Family", ringLabelId = 7)
        val ownOn = family.copy(offHours = family.offHours.copy(enabled = true))
        val on = Situations.turnOn(SituationState(), ownOn, gym, SituationCause.MANUAL, 0)
        assertEquals(OffHoursAllow.LABEL, on.behaviour.offHours.allow)
        // Family is deleted: off hours goes the way LabelReferences takes it (labelGone), and so does the snapshot.
        val titles = setOf("Family")
        val current = on.behaviour.copy(offHours = LabelRefs.labelGone(on.behaviour.offHours))
        val state = Situations.stateLabelsDeleted(on.state, titles)
        val list = Situations.labelsDeleted(listOf(gym), titles)
        assertTrue(list.single().ringLabelGone)
        // The one on takes its new bundle: Favourites all day, not "everyone" and not "nobody".
        val again = Situations.turnOn(state, current, list.single(), SituationCause.MANUAL, 0)
        assertTrue(again.behaviour.offHours.enabled)
        assertEquals(OffHoursAllow.FAVOURITES, again.behaviour.offHours.allow)
        // Turned off: the user's own off hours (which let only Family ring) are off, as when the label goes without one.
        val off = Situations.turnOff(again.state, again.behaviour, byHand = true).behaviour.offHours
        assertEquals(LabelRefs.labelGone(ownOn.offHours), off)
        assertFalse(off.enabled)
    }

    @Test fun the_label_is_followed_by_its_row_then_by_title_and_falls_back_to_favourites() {
        val night = Situation("n", ring = SituationRing.LABEL, ringLabel = "Family", ringLabelId = 7)
        // Renamed in another app: the row still says which label it is.
        assertEquals("Home", Situations.followLabel(night, mapOf(7L to "Home ")).ringLabel)
        // A new phone (no row): matched by title, and the row kept from then on.
        val restored = Situations.followLabel(night.copy(ringLabelId = null), mapOf(3L to "Family"))
        assertEquals(3L, restored.ringLabelId)
        assertFalse(restored.ringLabelGone)
        // Gone: Favourites ring, and the title is kept for the summary (and for a label made again).
        val gone = Situations.followLabel(night, mapOf(3L to "Work"))
        assertTrue(gone.ringLabelGone)
        assertEquals("Family", gone.ringLabel)
        assertEquals(OffHoursAllow.FAVOURITES, Situations.apply(mine, gone).offHours.allow)
        assertFalse(Situations.followLabel(gone, mapOf(9L to "Family")).ringLabelGone)
        // Labels that can't be read change nothing.
        assertEquals(night, Situations.followLabel(night, null))
        // Renamed or merged in Parley: the title follows.
        assertEquals("Home", Situations.labelsRenamed(listOf(night), mapOf("Family" to "Home")).single().ringLabel)
        // Backups don't carry this phone's rows.
        val merged = Situations.merge(defaults, listOf(night), defaults)
        assertNull(merged.first { it.id == "n" }.ringLabelId)
    }

    @Test fun moving_away_from_one_that_came_on_by_itself_holds_it_off() {
        val night = s(Situations.NIGHT).copy(schedule = Situations.NIGHT_WINDOW)
        val list = listOf(night, s(Situations.TRAVELLING))
        val auto = Situations.turnOn(SituationState(), mine, night, SituationCause.SCHEDULE, 0)
        // The tile: Travelling by hand, then Off.
        val travelling = Situations.turnOn(auto.state, auto.behaviour, s(Situations.TRAVELLING), SituationCause.MANUAL, 1)
        assertEquals(listOf(night.id), travelling.state.held)
        val off = Situations.turnOff(travelling.state, travelling.behaviour, byHand = true)
        assertEquals(mine, off.behaviour)
        // Still inside Night's window: it stays off.
        assertEquals(Situations.Step.Keep, Situations.plan(off.state, list, sig(DayOfWeek.MONDAY, 23)).step)
        // Next night it comes on again.
        val morning = Situations.plan(off.state, list, sig(DayOfWeek.TUESDAY, 8))
        val tomorrow = Situations.plan(off.state.copy(held = morning.held), list, sig(DayOfWeek.TUESDAY, 22))
        assertEquals(Situations.Step.On(night, SituationCause.SCHEDULE), tomorrow.step)
    }

    @Test fun window_edges_are_wall_clock_times_on_days_the_clocks_change() {
        val zone = java.time.ZoneId.of("Europe/London")
        val night = s(Situations.NIGHT).copy(schedule = Situations.NIGHT_WINDOW)
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
        // Clocks go back on 25 October 2026 (a 25-hour day): Night still starts at 22:00 that evening.
        assertEquals(at(2026, 10, 25, 22), Situations.nextChange(listOf(night), at(2026, 10, 25, 12), zone))
        assertEquals(at(2026, 10, 26, 7), Situations.nextChange(listOf(night), at(2026, 10, 25, 22, 1), zone))
        // Clocks go forward on 29 March 2026 (a 23-hour day): 22:00, then 07:00.
        assertEquals(at(2026, 3, 29, 22), Situations.nextChange(listOf(night), at(2026, 3, 29, 12), zone))
        assertEquals(at(2026, 3, 29, 7), Situations.nextChange(listOf(night), at(2026, 3, 29, 3), zone))
        // An edge at 24:00 is the next midnight.
        val late = Situation("x", ring = SituationRing.EVERYONE, schedule = Schedule(Schedule.ALL_DAYS, 20 * 60, 24 * 60))
        assertEquals(at(2026, 10, 26, 0), Situations.nextChange(listOf(late), at(2026, 10, 25, 21), zone))
    }

    @Test fun a_switch_cut_short_is_marked_pending_in_what_is_kept() {
        val st = SituationState(activeId = "x", applied = mine, pending = true)
        assertEquals(st, SituationState.decode(st.encode()))
        // Older state without the mark reads as finished.
        assertFalse(SituationState.decode("""{"activeId":"x"}""").pending)
    }

    // ------------------------------------------------------------------ switched on by hand: how long, and the notice

    private val utc = ZoneOffset.UTC
    private fun at(day: Int, h: Int, m: Int = 0): Long = LocalDateTime.of(2026, 10, day, h, m).toEpochSecond(utc) * 1000
    private fun sigAt(millis: Long, roaming: Boolean = false) = SituationSignals(PolicyClock.of(millis, utc), roaming = roaming)

    @Test fun switching_on_by_hand_offers_an_hour_the_end_of_its_window_and_until_turned_off() {
        // Monday 12 October 2026, 10:00. Meeting has no window: an hour, or 18:00 today.
        val now = at(12, 10)
        val meeting = Situations.endChoices(s(Situations.MEETING), now, utc)
        assertEquals(
            listOf(Situations.End.ForAnHour(at(12, 11)), Situations.End.UntilTime(at(12, 18), 18 * 60), Situations.End.UntilTurnedOff),
            meeting,
        )
        // Night with its window ends at 07:00 the next morning.
        val night = s(Situations.NIGHT).copy(schedule = Situations.NIGHT_WINDOW)
        assertEquals(Situations.End.UntilTime(at(13, 7), 7 * 60), Situations.endChoices(night, at(12, 23), utc)[1])
        // After 18:00 a day's end has passed: an hour, or until turned off.
        assertEquals(listOf(Situations.End.ForAnHour(at(12, 20)), Situations.End.UntilTurnedOff), Situations.endChoices(s(Situations.MEETING), at(12, 19), utc))
        // At 17:00 "until 18:00" is the same as an hour: offered once.
        assertEquals(2, Situations.endChoices(s(Situations.MEETING), at(12, 17), utc).size)
    }

    @Test fun a_situation_on_for_an_hour_goes_off_by_itself_and_its_end_is_looked_at_then() {
        val now = at(12, 10)
        val on = Situations.turnOn(SituationState(), mine, s(Situations.MEETING), SituationCause.MANUAL, now, until = at(12, 11))
        assertEquals(at(12, 11), on.state.until)
        val list = defaults
        assertEquals(Situations.Step.Keep, Situations.plan(on.state, list, sigAt(at(12, 10, 59))).step)
        assertEquals(Situations.Step.Off, Situations.plan(on.state, list, sigAt(at(12, 11))).step)
        // The window job wakes at the end even when no Situation has a window.
        assertEquals(at(12, 11), Situations.nextChange(list, now, utc, on.state.until))
        assertNull(Situations.nextChange(list, now, utc, null))
        // Off puts back what was set, and the end goes with it.
        val off = Situations.turnOff(on.state, on.behaviour, byHand = false)
        assertEquals(mine, off.behaviour)
        assertNull(off.state.until)
        // Without an end it stays on, as before.
        val forever = Situations.turnOn(SituationState(), mine, s(Situations.MEETING), SituationCause.MANUAL, now)
        assertEquals(Situations.Step.Keep, Situations.plan(forever.state, list, sigAt(at(13, 10))).step)
        // Only a Situation switched on by hand has an end of its own; one in the past is none.
        assertNull(Situations.turnOn(SituationState(), mine, s(Situations.MEETING), SituationCause.SCHEDULE, now, until = at(12, 11)).state.until)
        assertNull(Situations.turnOn(SituationState(), mine, s(Situations.MEETING), SituationCause.MANUAL, now, until = now - 1).state.until)
        // The end survives a restart.
        assertEquals(on.state, SituationState.decode(on.state.encode()))
    }

    @Test fun at_its_end_a_situation_whose_window_still_holds_stays_off_until_the_window_ends() {
        val night = s(Situations.NIGHT).copy(schedule = Situations.NIGHT_WINDOW)
        val on = Situations.turnOn(SituationState(), mine, night, SituationCause.MANUAL, at(12, 22), until = at(12, 23))
        val plan = Situations.plan(on.state, listOf(night), sigAt(at(12, 23)))
        assertEquals(Situations.Step.Off, plan.step)
        assertEquals(listOf(night.id), plan.held)
        val off = Situations.turnOff(on.state.copy(held = plan.held), on.behaviour, byHand = false).state
        // Still in its window: not switched straight back on.
        assertEquals(Situations.Step.Keep, Situations.plan(off, listOf(night), sigAt(at(12, 23, 5))).step)
    }

    @Test fun travelling_can_switch_itself_on_abroad() {
        val travelling = s(Situations.TRAVELLING).copy(device = DeviceTrigger.ROAMING)
        assertEquals(SituationCause.DEVICE, Situations.trigger(travelling, sigAt(at(12, 10), roaming = true)))
        assertNull(Situations.trigger(travelling, sigAt(at(12, 10))))
        val plan = Situations.plan(SituationState(), listOf(travelling), sigAt(at(12, 10), roaming = true))
        assertEquals(Situations.Step.On(travelling, SituationCause.DEVICE), plan.step)
        // Back home it goes off.
        val on = Situations.turnOn(SituationState(), mine, travelling, SituationCause.DEVICE, at(12, 10))
        assertEquals(Situations.Step.Off, Situations.plan(on.state, listOf(travelling), sigAt(at(14, 10))).step)
    }

    @Test fun one_on_by_roaming_is_looked_at_again_within_the_hour_so_it_never_stays_on_at_home() {
        val travelling = s(Situations.TRAVELLING).copy(device = DeviceTrigger.ROAMING)
        val now = at(12, 10)
        val on = Situations.turnOn(SituationState(), mine, travelling, SituationCause.DEVICE, now)
        // No window and no chosen end: without the re-look nothing would wake it after coming home.
        assertNull(Situations.nextChange(listOf(travelling), now, utc, on.state.until))
        assertEquals(now + Situations.ROAMING_RECHECK_MS, Situations.roamingRecheck(on.state, listOf(travelling), now))
        // Switched on by hand, or by another trigger, or off: no re-look needed.
        val byHand = Situations.turnOn(SituationState(), mine, travelling, SituationCause.MANUAL, now)
        assertNull(Situations.roamingRecheck(byHand.state, listOf(travelling), now))
        val car = s(Situations.DRIVING).copy(device = DeviceTrigger.CAR)
        assertNull(Situations.roamingRecheck(Situations.turnOn(SituationState(), mine, car, SituationCause.DEVICE, now).state, listOf(car), now))
        assertNull(Situations.roamingRecheck(SituationState(), listOf(travelling), now))
    }

    @Test fun the_notice_never_says_until_turned_off_for_one_that_ends_by_itself() {
        val night = s(Situations.NIGHT).copy(schedule = Situations.NIGHT_WINDOW)
        val bySchedule = Situations.turnOn(SituationState(), mine, night, SituationCause.SCHEDULE, at(12, 23)).state
        assertEquals(Situations.NoticeEnd.At(Situations.NIGHT_WINDOW.endMinute % 1440), Situations.noticeEnd(bySchedule, night, null))
        val travelling = s(Situations.TRAVELLING).copy(device = DeviceTrigger.ROAMING)
        val abroad = Situations.turnOn(SituationState(), mine, travelling, SituationCause.DEVICE, at(12, 10)).state
        assertEquals(Situations.NoticeEnd.WhileTriggered, Situations.noticeEnd(abroad, travelling, null))
        val meeting = s(Situations.MEETING)
        val forAnHour = Situations.turnOn(SituationState(), mine, meeting, SituationCause.MANUAL, at(12, 10), until = at(12, 11)).state
        assertEquals(Situations.NoticeEnd.At(11 * 60), Situations.noticeEnd(forAnHour, meeting, 11 * 60))
        val byHand = Situations.turnOn(SituationState(), mine, meeting, SituationCause.MANUAL, at(12, 10)).state
        assertEquals(Situations.NoticeEnd.WhenTurnedOff, Situations.noticeEnd(byHand, meeting, null))
    }

    @Test fun the_notice_shows_only_while_a_situation_lets_some_people_ring() {
        assertTrue(Situations.silencesAnyone(s(Situations.MEETING)))
        assertTrue(Situations.silencesAnyone(s(Situations.NIGHT)))
        assertFalse(Situations.silencesAnyone(s(Situations.DRIVING)))
        assertFalse(Situations.silencesAnyone(s(Situations.TRAVELLING)))
        assertFalse(Situations.silencesAnyone(s(Situations.MEETING).copy(ring = SituationRing.EVERYONE)))
        assertFalse(Situations.silencesAnyone(null))
    }
}
