package app.parley.common.situations

import app.parley.common.AppSettings
import app.parley.common.BlockAction
import app.parley.common.OffHours
import app.parley.common.OffHoursAllow
import app.parley.common.PolicyClock
import app.parley.common.Schedule
import app.parley.common.ScreeningSettings
import app.parley.common.blocking.ScreeningPreset
import app.parley.common.calls.SpeakerDefault
import app.parley.common.who.Who
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/** The one "who may ring" model: Quiet nights is the Night Situation, and a Situation in control says so. */
class OneNightTest {
    private val defaults = Situations.builtIns()
    private val night = defaults.first { it.id == Situations.NIGHT }
    private val quietNights = OffHours(enabled = true, schedule = Situations.NIGHT_WINDOW, allow = OffHoursAllow.CONTACTS)

    @Test fun the_quiet_nights_preset_moves_into_night() {
        val moved = Situations.moveQuietNights(quietNights, defaults, night, SituationState())
        assertNotNull(moved)
        val (offHours, n) = moved!!
        assertFalse(offHours.enabled)
        assertEquals(Situations.NIGHT_WINDOW, n.schedule)
        assertEquals(Who.CONTACTS, n.who)
        assertEquals(n, Situations.quietNights(night))
    }

    @Test fun moving_loses_no_behaviour() {
        val n = Situations.moveQuietNights(quietNights, defaults, night, SituationState())!!.second
        val base = Behaviour(offHours = quietNights.copy(enabled = false))
        // In the window Night is on, and what it writes rings and silences as Quiet nights did: contacts only, silenced.
        val sig = SituationSignals(PolicyClock(0, DayOfWeek.MONDAY, 23 * 60 + 30))
        assertEquals(SituationCause.SCHEDULE, Situations.trigger(n, sig))
        val on = Situations.apply(base, n).offHours
        assertTrue(on.enabled)
        assertEquals(OffHoursAllow.CONTACTS, on.allow)
        assertEquals(BlockAction.SILENCE, on.action)
        assertTrue(on.schedule.isActive(sig.clock.day, sig.clock.minuteOfDay))
        // Outside it, nothing switches on, and off hours stays off as before the window.
        val day = SituationSignals(PolicyClock(0, DayOfWeek.MONDAY, 12 * 60))
        assertNull(Situations.trigger(n, day))
    }

    @Test fun anything_else_stays_as_it_is() {
        // Off hours of one's own (other times, favourites, declined).
        assertNull(Situations.moveQuietNights(quietNights.copy(schedule = Schedule(Schedule.ALL_DAYS, 23 * 60, 6 * 60)), defaults, night, SituationState()))
        assertNull(Situations.moveQuietNights(quietNights.copy(allow = OffHoursAllow.FAVOURITES), defaults, night, SituationState()))
        assertNull(Situations.moveQuietNights(quietNights.copy(action = BlockAction.REJECT), defaults, night, SituationState()))
        assertNull(Situations.moveQuietNights(quietNights.copy(enabled = false), defaults, night, SituationState()))
        // Night already changed, or a Situation on now (the stores hold its values).
        val changed = defaults.map { if (it.id == Situations.NIGHT) it.copy(reply = "Asleep") else it }
        assertNull(Situations.moveQuietNights(quietNights, changed, night, SituationState()))
        assertNull(Situations.moveQuietNights(quietNights, defaults, night, SituationState(activeId = Situations.MEETING)))
    }

    @Test fun the_presets_set_night() {
        assertEquals(Situations.quietNights(night), ScreeningPreset.NIGHTS.situation(night))
        // Quiet nights no longer writes off hours; the Night Situation carries it.
        assertEquals(AppSettings(), ScreeningPreset.NIGHTS.apply(AppSettings()))
        // "Everyone can ring" stops Night switching on by itself; it can still be switched on by hand.
        assertNull(ScreeningPreset.EVERYONE.situation(night.copy(schedule = Situations.NIGHT_WINDOW))!!.schedule)
        assertNull(ScreeningPreset.EVERYONE.situation(night))
        assertNull(ScreeningPreset.KNOWN.situation(night))
        // What's in use: Night on by itself is Quiet nights, and then not "Everyone can ring".
        assertEquals(listOf(ScreeningPreset.NIGHTS), ScreeningPreset.current(ScreeningSettings(), nightScheduled = true))
        assertEquals(listOf(ScreeningPreset.EVERYONE), ScreeningPreset.current(ScreeningSettings()))
        assertTrue(Situations.nightScheduled(listOf(Situations.quietNights(night))))
        assertFalse(Situations.nightScheduled(defaults))
    }

    @Test fun a_situation_says_which_pages_it_controls() {
        val meeting = Situations.builtIns { "In a meeting" }.first { it.id == Situations.MEETING }
        assertTrue(Situations.overrides(meeting, SituationArea.SCREENING))
        assertTrue(Situations.overrides(meeting, SituationArea.ANSWERING))
        assertFalse(Situations.overrides(meeting, SituationArea.DURING))
        assertFalse(Situations.overrides(meeting, SituationArea.SIMS))
        val driving = defaults.first { it.id == Situations.DRIVING }
        assertTrue(Situations.overrides(driving, SituationArea.DRIVE))
        assertFalse(Situations.overrides(driving, SituationArea.SCREENING))
        assertTrue(Situations.overrides(Situation("x", speaker = SpeakerDefault.ALWAYS), SituationArea.DURING))
        assertTrue(Situations.overrides(Situation("x", simId = "sim2"), SituationArea.SIMS))
        assertFalse(Situations.overrides(null, SituationArea.SCREENING))
    }
}
