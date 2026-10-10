package app.parley.common.calls

import app.parley.common.calls.RingVibration.Decision
import app.parley.common.calls.RingVibration.Facts
import app.parley.common.calls.RingVibration.Quiet
import app.parley.common.calls.RingVibration.SystemSetting
import app.parley.common.calls.RingVibration.Usage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vibration decision table (docs/SETTINGS.md, "Ringing and vibration"), row by row. */
class RingVibrationTest {
    private val heartbeat = CallerHaptics.repeating(CallerHaptics.Pattern(CallerHaptics.Preset.HEARTBEAT))

    private fun pattern(d: Decision): LongArray? = (d as? Decision.Vibrate)?.timings
    private fun quiet(d: Decision): Quiet? = (d as? Decision.None)?.why

    // ---- The system's "Vibrate for calls" ----

    @Test fun android_13_and_later_go_by_the_ring_vibration_intensity_alone() {
        // The owner's phone: VIBRATE_WHEN_RINGING deprecated and 0 (or never stored), Settings shows vibration on.
        assertTrue(RingVibration.systemVibrates(SystemSetting(sdk = 34, vibrateWhenRinging = 0, ringIntensity = null)))
        assertTrue(RingVibration.systemVibrates(SystemSetting(sdk = 33, vibrateWhenRinging = null, ringIntensity = 2)))
        assertFalse(RingVibration.systemVibrates(SystemSetting(sdk = 34, vibrateWhenRinging = 1, ringIntensity = 0)))
    }

    @Test fun before_android_13_the_switch_or_the_ramping_ringer_decides() {
        assertTrue(RingVibration.systemVibrates(SystemSetting(sdk = 30, vibrateWhenRinging = 1, ringIntensity = null)))
        assertFalse(RingVibration.systemVibrates(SystemSetting(sdk = 30, vibrateWhenRinging = 0, ringIntensity = null)))
        assertFalse(RingVibration.systemVibrates(SystemSetting(sdk = 29, vibrateWhenRinging = null, ringIntensity = null)))
        // "Vibrate first, then ring gradually" vibrates although the switch is off.
        assertTrue(RingVibration.systemVibrates(SystemSetting(sdk = 31, vibrateWhenRinging = 0, ringIntensity = null, rampingRinger = true)))
        assertFalse(RingVibration.systemVibrates(SystemSetting(sdk = 32, vibrateWhenRinging = 1, ringIntensity = 0)))
    }

    // ---- Ringer mode and the system setting ----

    @Test fun normal_mode_follows_vibrate_for_calls() {
        assertArrayEquals(RingVibration.USUAL, pattern(RingVibration.decide(Facts(RingerMode.NORMAL, systemVibrates = true))))
        assertEquals(Quiet.VIBRATION_OFF, quiet(RingVibration.decide(Facts(RingerMode.NORMAL, systemVibrates = false))))
    }

    @Test fun vibrate_mode_always_vibrates_and_silent_mode_never() {
        assertArrayEquals(RingVibration.USUAL, pattern(RingVibration.decide(Facts(RingerMode.VIBRATE, systemVibrates = false))))
        assertEquals(Quiet.PHONE_SILENT, quiet(RingVibration.decide(Facts(RingerMode.SILENT, systemVibrates = true))))
        // A Rescue call on silent stays silent too: only the screen shows it.
        assertEquals(Quiet.PHONE_SILENT, quiet(RingVibration.decide(Facts(RingerMode.SILENT, systemVibrates = true, rescue = true))))
        // An unknown ringer mode is read as normal.
        assertArrayEquals(RingVibration.USUAL, pattern(RingVibration.decide(Facts(RingerMode.UNKNOWN, systemVibrates = true))))
    }

    @Test fun no_vibrator_never_vibrates() {
        assertEquals(Quiet.NO_VIBRATOR, quiet(RingVibration.decide(Facts(RingerMode.VIBRATE, systemVibrates = true, hasVibrator = false))))
    }

    // ---- The caller's choice ----

    @Test fun the_callers_pattern_replaces_the_usual_one() {
        assertArrayEquals(heartbeat, pattern(RingVibration.decide(Facts(RingerMode.NORMAL, systemVibrates = true, pattern = heartbeat))))
        assertArrayEquals(heartbeat, pattern(RingVibration.decide(Facts(RingerMode.VIBRATE, systemVibrates = false, pattern = heartbeat))))
    }

    @Test fun the_phones_usual_vibration_is_the_platform_pattern_never_none() {
        val usual = RingVibration.decide(Facts(RingerMode.NORMAL, systemVibrates = true, pattern = null))
        assertArrayEquals(longArrayOf(0, 1000, 1000), pattern(usual))
    }

    @Test fun an_empty_or_broken_pattern_falls_back_to_the_usual_one() {
        listOf(longArrayOf(), longArrayOf(0), longArrayOf(0, 0, 900), longArrayOf(0, -5, 100), longArrayOf(500, 0)).forEach { bad ->
            assertNull(RingVibration.usable(bad))
            assertArrayEquals(RingVibration.USUAL, pattern(RingVibration.decide(Facts(RingerMode.NORMAL, systemVibrates = true, pattern = bad))))
        }
    }

    // ---- Do Not Disturb, Situations and silencing ----

    @Test fun do_not_disturb_keeps_a_call_still_unless_it_lets_the_call_through() {
        val total = Facts(RingerMode.NORMAL, systemVibrates = true, dnd = DndState.TOTAL_SILENCE)
        assertEquals(Quiet.DND, quiet(RingVibration.decide(total)))
        assertEquals(Quiet.DND, quiet(RingVibration.decide(total.copy(dnd = DndState.ALARMS))))
        assertEquals(Quiet.DND, quiet(RingVibration.decide(total.copy(dnd = DndState.PRIORITY, dndAllowsCall = null))))
        val starred = RingVibration.decide(total.copy(dnd = DndState.PRIORITY, dndAllowsCall = true))
        assertEquals(Usage.RINGTONE, (starred as Decision.Vibrate).usage)
    }

    @Test fun a_rescue_call_breaks_through_do_not_disturb_as_an_alarm() {
        val d = RingVibration.decide(Facts(RingerMode.NORMAL, systemVibrates = true, dnd = DndState.PRIORITY, rescue = true, pattern = heartbeat))
        assertEquals(Usage.ALARM, (d as Decision.Vibrate).usage)
        assertArrayEquals(heartbeat, d.timings)
        // Without Do Not Disturb it rings like any call.
        val plain = RingVibration.decide(Facts(RingerMode.NORMAL, systemVibrates = true, rescue = true))
        assertEquals(Usage.RINGTONE, (plain as Decision.Vibrate).usage)
        // It still follows "Vibrate for calls".
        assertEquals(Quiet.VIBRATION_OFF, quiet(RingVibration.decide(Facts(RingerMode.NORMAL, systemVibrates = false, rescue = true))))
    }

    @Test fun a_silenced_call_never_vibrates() {
        // A rule, an allowance used up, a Situation's off hours, the drive profile or the user's Silence.
        assertEquals(Quiet.SILENCED, quiet(RingVibration.decide(Facts(RingerMode.VIBRATE, systemVibrates = true, silenced = true))))
        assertEquals(Quiet.SILENCED, quiet(RingVibration.decide(Facts(RingerMode.NORMAL, systemVibrates = true, silenced = true, rescue = true))))
    }

    // ---- Call waiting ----

    @Test fun a_call_waiting_vibrates_gently_with_the_callers_rhythm() {
        val usual = pattern(RingVibration.decide(Facts(RingerMode.NORMAL, systemVibrates = true, callWaiting = true)))
        assertArrayEquals(RingVibration.WAITING, usual)
        val theirs = pattern(RingVibration.decide(Facts(RingerMode.NORMAL, systemVibrates = true, callWaiting = true, pattern = heartbeat)))!!
        assertEquals(heartbeat.size, theirs.size)
        assertEquals(RingVibration.WAITING_PAUSE_MS, theirs.last())
        assertArrayEquals(heartbeat.copyOf(heartbeat.size - 1), theirs.copyOf(theirs.size - 1))
        // A rhythm without a trailing pause gets one.
        assertArrayEquals(longArrayOf(0, 200, RingVibration.WAITING_PAUSE_MS), RingVibration.waiting(longArrayOf(0, 200)))
        // Still nothing with vibration off or on silent.
        assertEquals(Quiet.VIBRATION_OFF, quiet(RingVibration.decide(Facts(RingerMode.NORMAL, systemVibrates = false, callWaiting = true))))
        assertEquals(Quiet.PHONE_SILENT, quiet(RingVibration.decide(Facts(RingerMode.SILENT, systemVibrates = true, callWaiting = true))))
    }
}
