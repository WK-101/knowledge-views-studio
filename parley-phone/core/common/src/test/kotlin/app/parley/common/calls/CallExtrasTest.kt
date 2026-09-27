package app.parley.common.calls

import app.parley.common.SettingsSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
