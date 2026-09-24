package app.parley.common.calls

import app.parley.common.SettingsSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPressTrackerTest {
    @Test fun tone_starts_on_press_and_lasts_at_least_the_minimum() {
        val k = KeyPressTracker()
        assertEquals(listOf(KeyAction.Press), k.down(1_000))
        // A 40 ms tap: the tone is stopped 110 ms later, so it lasts 150 ms.
        assertEquals(listOf(KeyAction.StopTone(110)), k.up(1_040))
        assertFalse(k.pressed)
    }

    @Test fun a_held_key_stops_right_away_on_release() {
        val k = KeyPressTracker()
        k.down(0)
        assertEquals(listOf(KeyAction.StopTone(0)), k.up(400))
    }

    @Test fun long_press_fires_once_and_stops_the_tone() {
        val k = KeyPressTracker()
        k.down(0)
        assertTrue(k.longPressDue(300).isEmpty())
        assertEquals(listOf(KeyAction.StopTone(0), KeyAction.LongPress), k.longPressDue(500))
        assertTrue(k.longPressed)
        assertTrue(k.longPressDue(900).isEmpty())
        // The tone already stopped: releasing does nothing more.
        assertTrue(k.up(1_000).isEmpty())
    }

    @Test fun sliding_off_cancels_the_long_press_and_stops_the_tone() {
        val k = KeyPressTracker()
        k.down(0)
        assertTrue(k.move(inside = true, now = 50).isEmpty())
        assertEquals(listOf(KeyAction.StopTone(90)), k.move(inside = false, now = 60))
        // Coming back doesn't re-arm it.
        assertTrue(k.move(inside = true, now = 100).isEmpty())
        assertTrue(k.longPressDue(600).isEmpty())
        assertFalse(k.longPressed)
        assertTrue(k.up(700).isEmpty())
    }

    @Test fun keys_roll_over_independently() {
        val one = KeyPressTracker()
        val two = KeyPressTracker()
        one.down(0)
        two.down(80) // second finger before the first is lifted
        assertEquals(listOf(KeyAction.StopTone(30)), one.up(120))
        assertTrue(two.pressed)
        assertEquals(listOf(KeyAction.StopTone(0)), two.up(400))
    }
}

class RingFactsTest {
    private val base = RingFacts(startedAt = 1_000, ringer = RingerMode.NORMAL, dnd = DndState.OFF, ringVolume = 5, ringVolumeMax = 7)

    @Test fun codec_round_trips_and_ignores_garbage() {
        val list = listOf(base, base.copy(startedAt = 2_000, ringtone = RingtoneSource.RULE, ringtoneDetail = "Plumber", outcome = RingOutcome.ANSWERED, answeredRoute = AnswerRoute.BLUETOOTH, answeredDevice = "Car"))
        assertEquals(list, RingFactsCodec.decode(RingFactsCodec.encode(list)))
        assertTrue(RingFactsCodec.decode("not json").isEmpty())
        assertTrue(RingFactsCodec.decode(null).isEmpty())
        // Rows written by a newer version with extra fields still read.
        assertEquals(1, RingFactsCodec.decode("""[{"startedAt":5,"future":"x"}]""").size)
    }

    @Test fun why_no_ring_prefers_parleys_own_reason() {
        assertEquals("Silenced: off hours", RingExplainer.whyNoRing(base.copy(silencedBy = "Off hours")))
        assertEquals("Silenced: off hours", RingExplainer.whyNoRing(null, "Silenced: off hours"))
        assertEquals("Silenced by rule 'Ads'", RingExplainer.whyNoRing(base.copy(silencedBy = "Silenced by rule 'Ads'")))
    }

    @Test fun why_no_ring_from_the_ringer_state() {
        assertEquals("Didn't ring: Do Not Disturb", RingExplainer.whyNoRing(base.copy(dnd = DndState.PRIORITY, dndAllowsCalls = false)))
        assertNull(RingExplainer.whyNoRing(base.copy(dnd = DndState.PRIORITY, dndAllowsCalls = true, ringMillis = 20_000)))
        assertEquals("Didn't ring: phone on silent", RingExplainer.whyNoRing(base.copy(ringer = RingerMode.SILENT)))
        assertEquals("Vibrate only: phone on vibrate", RingExplainer.whyNoRing(base.copy(ringer = RingerMode.VIBRATE)))
        assertEquals("Didn't ring: ring volume at 0", RingExplainer.whyNoRing(base.copy(ringVolume = 0)))
        assertEquals("Rang for 2 s only", RingExplainer.whyNoRing(base.copy(ringMillis = 1_500)))
        assertNull(RingExplainer.whyNoRing(base.copy(ringMillis = 25_000)))
        assertNull(RingExplainer.whyNoRing(null))
    }

    @Test fun audible_only_when_nothing_kept_it_quiet() {
        assertTrue(base.audible)
        assertFalse(base.copy(ringer = RingerMode.VIBRATE).audible)
        assertFalse(base.copy(dnd = DndState.ALARMS).audible)
        assertFalse(base.copy(silencedBy = "Ignored").audible)
        assertFalse(base.copy(ringVolume = 0).audible)
    }

    @Test fun lines_describe_every_fact() {
        val lines = RingExplainer.lines(
            base.copy(vibrate = true, ringtone = RingtoneSource.LABEL, ringtoneDetail = "Family", ringMillis = 8_000, outcome = RingOutcome.ANSWERED, answeredRoute = AnswerRoute.BLUETOOTH, answeredDevice = "Car kit", ringLoud = true),
        )
        assertEquals(
            listOf(
                "Do Not Disturb: off",
                "Ringer: sound · volume 5/7 · raised to full volume",
                "Vibrate for calls: on",
                "Ringtone: label 'Family'",
                "Rang for 8 s",
                "Answered on Car kit",
            ),
            lines,
        )
        assertEquals("Answered on another device", RingExplainer.outcomeText(base.copy(outcome = RingOutcome.ANSWERED_ELSEWHERE)))
    }

    @Test fun matches_the_closest_ring_within_the_window() {
        val all = listOf(base.copy(startedAt = 10_000), base.copy(startedAt = 70_000), base.copy(startedAt = 900_000))
        assertEquals(70_000L, RingExplainer.matchFor(all, 65_000)?.startedAt)
        assertNull(RingExplainer.matchFor(all, 500_000))
    }
}

class MissedCallsTest {
    private fun call(n: String, t: Long, sim: String? = null, hidden: Boolean = false) = MissedCall(n, t, sim, hidden, "k$n")

    @Test fun groups_by_caller_newest_first_with_counts() {
        val g = MissedCalls.group(listOf(call("1", 100), call("2", 300, "sim2"), call("1", 200, "sim1"), call("", 50, hidden = true), call("x", 40, hidden = true)))
        assertEquals(listOf("k2", "k1", MissedCalls.HIDDEN), g.map { it.key })
        val one = g[1]
        assertEquals(2, one.count)
        assertEquals(200L, one.latest)
        assertEquals(100L, one.first)
        assertEquals("sim1", one.accountId)
        assertEquals(2, g[2].count)
        assertTrue(g[2].hidden)
    }

    @Test fun titles() {
        assertEquals("Missed call", MissedCalls.title(1))
        assertEquals("3 missed calls", MissedCalls.title(3))
        assertEquals("5 missed calls from 3 callers", MissedCalls.summaryTitle(5, 3))
        assertEquals("2 missed calls", MissedCalls.summaryTitle(2, 1))
    }
}

class CallExtrasTest {
    @Test fun defaults_and_codec() {
        val d = CallExtrasConfig()
        assertTrue(d.proximitySensor)
        assertTrue(d.pocketGuard)
        assertEquals(0, d.missedReAlertMinutes)
        val c = CallExtrasConfig(proximitySensor = false, pocketGuard = false, missedReAlertMinutes = 15)
        assertEquals(c, CallExtrasConfig.decode(CallExtrasConfig.encode(c)))
        assertEquals(d, CallExtrasConfig.decode("{bad"))
        // An interval that isn't offered falls back to off.
        assertEquals(0, CallExtrasConfig.decode("""{"missedReAlertMinutes":7}""").missedReAlertMinutes)
    }

    @Test fun re_alert_schedule_stops_after_a_while() {
        val m = 60_000L
        assertNull(MissedReAlert.nextAt(0, 0, 0, 0))
        assertEquals(10 * m, MissedReAlert.nextAt(10, 0, 0, 0))
        assertNull(MissedReAlert.nextAt(10, 0, MissedReAlert.MAX_ALERTS, 0))
        assertNull(MissedReAlert.nextAt(30, 0, 3, 170 * m))
        assertEquals(180 * m, MissedReAlert.nextAt(30, 0, 3, 150 * m))
    }

    @Test fun re_alert_respects_do_not_disturb_and_stops_when_seen() {
        assertEquals(MissedReAlert.Step.ALERT, MissedReAlert.step(stillUnseen = true, notificationShowing = true, dnd = DndState.OFF))
        assertEquals(MissedReAlert.Step.SKIP, MissedReAlert.step(true, true, DndState.PRIORITY))
        assertEquals(MissedReAlert.Step.SKIP, MissedReAlert.step(true, true, DndState.TOTAL_SILENCE))
        assertEquals(MissedReAlert.Step.STOP, MissedReAlert.step(false, true, DndState.OFF))
        assertEquals(MissedReAlert.Step.STOP, MissedReAlert.step(true, false, DndState.OFF))
    }

    @Test fun pocket_guard_only_for_one_tap_sources() {
        assertTrue(PocketGuard.shouldAsk(true, CallSource.WIDGET, covered = true))
        assertTrue(PocketGuard.shouldAsk(true, CallSource.FAVORITE, covered = true))
        assertFalse(PocketGuard.shouldAsk(true, CallSource.KEYPAD, covered = true))
        assertFalse(PocketGuard.shouldAsk(true, CallSource.SHORTCUT, covered = false))
        assertFalse(PocketGuard.shouldAsk(true, CallSource.SHORTCUT, covered = null))
        assertFalse(PocketGuard.shouldAsk(false, CallSource.SHORTCUT, covered = true))
    }

    @Test fun voicemail_files() {
        assertEquals("amr", VoicemailFiles.extensionFor("audio/amr"))
        assertEquals("m4a", VoicemailFiles.extensionFor("audio/mp4; codecs=aac"))
        assertEquals("amr", VoicemailFiles.extensionFor(null))
        assertEquals("voicemail-2026-09-04-0705.ogg", VoicemailFiles.shareName(2026, 9, 4, 7, 5, "audio/ogg"))
        assertEquals("0:07", VoicemailFiles.clock(7_400))
        assertEquals("12:45", VoicemailFiles.clock(765_000))
    }

    @Test fun new_settings_are_searchable() {
        fun keys(q: String) = SettingsSearch.search(q).map { it.key }
        assertTrue("proximity_sensor" in keys("proximity"))
        assertTrue("pocket_guard" in keys("pocket dial"))
        assertTrue("missed_realert" in keys("missed call reminder"))
        assertTrue("power_button_ends_call" in keys("power button"))
        assertTrue("voicemail" in keys("visual voicemail"))
    }
}
