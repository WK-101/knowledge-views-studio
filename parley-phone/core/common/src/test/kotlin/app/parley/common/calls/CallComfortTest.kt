package app.parley.common.calls

import app.parley.common.calls.SpeakerOnStart.Facts
import app.parley.common.calls.SpeakerOnStart.Route
import app.parley.common.calls.SpeakerOnStart.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallComfortTest {
    private fun facts(
        choice: SpeakerDefault = SpeakerDefault.ALWAYS,
        started: Boolean = true,
        emergency: Boolean = false,
        saved: Boolean? = false,
        otherCall: Boolean = false,
        route: Route = Route.EARPIECE,
        speaker: Boolean = true,
        holdMode: Boolean = false,
        conference: Boolean = false,
    ) = Facts(choice, started, emergency, saved, otherCall, route, speaker, holdMode, conference)

    @Test fun off_never_touches_the_audio() {
        assertEquals(Step.LEAVE, SpeakerOnStart.step(facts(choice = SpeakerDefault.OFF)))
    }

    @Test fun always_turns_the_speaker_on_once_the_call_starts() {
        assertEquals(Step.WAIT, SpeakerOnStart.step(facts(started = false)))
        assertEquals(Step.TURN_ON, SpeakerOnStart.step(facts()))
        assertEquals(Step.TURN_ON, SpeakerOnStart.step(facts(saved = true)))
        assertEquals(Step.TURN_ON, SpeakerOnStart.step(facts(saved = null)))
    }

    @Test fun it_only_replaces_the_earpiece() {
        assertEquals(Step.LEAVE, SpeakerOnStart.step(facts(route = Route.HEADSET)))
        assertEquals(Step.LEAVE, SpeakerOnStart.step(facts(route = Route.SPEAKER)))
        assertEquals(Step.LEAVE, SpeakerOnStart.step(facts(speaker = false)))
        // Routes not reported yet: ask again later.
        assertEquals(Step.WAIT, SpeakerOnStart.step(facts(route = Route.UNKNOWN)))
    }

    @Test fun never_for_emergency_calls_a_second_call_or_hold_mode() {
        assertEquals(Step.LEAVE, SpeakerOnStart.step(facts(emergency = true)))
        assertEquals(Step.LEAVE, SpeakerOnStart.step(facts(otherCall = true)))
        assertEquals(Step.LEAVE, SpeakerOnStart.step(facts(holdMode = true)))
        // Merging calls keeps the audio where the merged calls had it.
        assertEquals(Step.LEAVE, SpeakerOnStart.step(facts(conference = true)))
    }

    @Test fun unknown_numbers_waits_for_the_lookup() {
        val u = SpeakerDefault.UNKNOWN_NUMBERS
        assertEquals(Step.WAIT, SpeakerOnStart.step(facts(choice = u, saved = null)))
        assertEquals(Step.LEAVE, SpeakerOnStart.step(facts(choice = u, saved = true)))
        assertEquals(Step.TURN_ON, SpeakerOnStart.step(facts(choice = u, saved = false)))
        assertEquals(Step.LEAVE, SpeakerOnStart.step(facts(choice = u, saved = false, route = Route.HEADSET)))
    }

    @Test fun screen_at_ear_modes() {
        assertEquals(ScreenAtEar.Mode.OFF, ScreenAtEar.mode(enabled = false, onceAnswered = true))
        assertEquals(ScreenAtEar.Mode.DURING_CALLS, ScreenAtEar.mode(enabled = true, onceAnswered = false))
        assertEquals(ScreenAtEar.Mode.ONCE_ANSWERED, ScreenAtEar.mode(enabled = true, onceAnswered = true))
    }

    @Test fun screen_at_ear_while_dialling_only_during_calls() {
        val during = ScreenAtEar.Mode.DURING_CALLS
        val once = ScreenAtEar.Mode.ONCE_ANSWERED
        assertTrue(ScreenAtEar.holds(during, connected = false, dialling = true, earpiece = true, screenInFront = true))
        assertFalse(ScreenAtEar.holds(once, connected = false, dialling = true, earpiece = true, screenInFront = true))
        assertTrue(ScreenAtEar.holds(once, connected = true, dialling = false, earpiece = true, screenInFront = true))
        // Never on the speaker or a headset, behind another app, or switched off.
        assertFalse(ScreenAtEar.holds(during, connected = true, dialling = false, earpiece = false, screenInFront = true))
        assertFalse(ScreenAtEar.holds(during, connected = true, dialling = false, earpiece = true, screenInFront = false))
        assertFalse(ScreenAtEar.holds(ScreenAtEar.Mode.OFF, connected = true, dialling = false, earpiece = true, screenInFront = true))
        // Ringing is neither connected nor dialling: the screen stays on to show who is calling.
        assertFalse(ScreenAtEar.holds(during, connected = false, dialling = false, earpiece = true, screenInFront = true))
    }

    private val up = Triple(0f, 0f, 9.81f)
    private val down = Triple(0.5f, -0.4f, -9.7f)
    private val shaking = Triple(9f, 9f, -12f)

    private fun FlipDetector.feed(r: Triple<Float, Float, Float>, at: Long) = onSample(r.first, r.second, r.third, at)

    @Test fun turning_the_phone_over_silences_after_it_stays_down() {
        val d = FlipDetector()
        assertFalse(d.feed(up, 0))
        assertFalse(d.feed(down, 100))
        assertFalse(d.feed(down, 100 + FlipDetector.HOLD_MS - 1))
        assertTrue(d.feed(down, 100 + FlipDetector.HOLD_MS))
        // Once per ring.
        assertFalse(d.feed(down, 2000))
    }

    @Test fun a_phone_already_face_down_keeps_ringing() {
        val d = FlipDetector()
        assertFalse(d.feed(down, 0))
        assertFalse(d.feed(down, 5000))
        // Picked up and put down again: that counts.
        assertFalse(d.feed(up, 6000))
        assertFalse(d.feed(down, 6100))
        assertTrue(d.feed(down, 6800))
    }

    @Test fun a_glance_face_down_or_a_shake_is_not_a_flip() {
        val d = FlipDetector()
        d.feed(up, 0)
        assertFalse(d.feed(down, 100))
        assertFalse(d.feed(up, 300))
        assertFalse(d.feed(down, 400))
        assertFalse(d.feed(shaking, 700))
        assertFalse(d.feed(down, 900))
        assertFalse(d.feed(down, 1400))
        assertTrue(d.feed(down, 1500))
    }

    @Test fun tilted_more_than_about_35_degrees_is_not_face_down() {
        assertTrue(FlipDetector.faceDown(0f, 4f, -9f))
        assertFalse(FlipDetector.faceDown(0f, 7f, -6.9f))
        assertFalse(FlipDetector.faceDown(0f, 0f, 9.81f))
        assertFalse(FlipDetector.faceDown(0f, 0f, -2f))
    }

    @Test fun reset_starts_over_for_the_next_call() {
        val d = FlipDetector()
        d.feed(up, 0)
        d.feed(down, 10)
        assertTrue(d.feed(down, 700))
        d.reset()
        assertFalse(d.feed(down, 800))
        assertFalse(d.feed(down, 2000))
    }

    @Test fun new_call_switches_default_off_and_survive_the_codec() {
        val d = CallExtrasConfig()
        assertEquals(SpeakerDefault.OFF, d.speakerDefault)
        assertFalse(d.flipToSilence)
        assertFalse(d.proximityOnceAnswered)
        val c = d.copy(speakerDefault = SpeakerDefault.UNKNOWN_NUMBERS, flipToSilence = true, proximityOnceAnswered = true)
        assertEquals(c, CallExtrasConfig.decode(CallExtrasConfig.encode(c)))
        // A choice from a newer version reads as off, and the rest of the document is kept.
        val newer = CallExtrasConfig.decode("""{"speakerDefault":"SOMETHING_NEW","pocketGuard":false}""")
        assertEquals(SpeakerDefault.OFF, newer.speakerDefault)
        assertFalse(newer.pocketGuard)
    }
}
