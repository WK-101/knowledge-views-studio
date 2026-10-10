package app.parley.common.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The volume keys silence a ringing call, and do nothing else to it. */
class RingKeysTest {
    private val volumeUp = 24
    private val volumeDown = 25
    private val mute = 164
    private val power = 26

    @Test fun a_volume_key_silences_a_ringing_call() {
        listOf(volumeUp, volumeDown, mute).forEach { k -> assertTrue(RingKeys.silences(k, down = true, ringing = true, silenced = false, otherCall = false)) }
    }

    @Test fun only_on_key_down_and_only_volume_keys() {
        assertFalse(RingKeys.silences(volumeDown, down = false, ringing = true, silenced = false, otherCall = false))
        assertFalse(RingKeys.silences(power, down = true, ringing = true, silenced = false, otherCall = false))
    }

    @Test fun keys_keep_their_usual_job_otherwise() {
        // Not ringing: the in-call volume.
        assertFalse(RingKeys.silences(volumeUp, down = true, ringing = false, silenced = false, otherCall = false))
        // Already silenced: nothing more to do.
        assertFalse(RingKeys.silences(volumeUp, down = true, ringing = true, silenced = true, otherCall = false))
        // A call waiting during another call: the keys set that call's volume.
        assertFalse(RingKeys.silences(volumeDown, down = true, ringing = true, silenced = false, otherCall = true))
    }
}
