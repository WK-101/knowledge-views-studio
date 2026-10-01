package app.parley.common.calls

import app.parley.common.calls.CallAudioOutputs.BLE_BROADCAST
import app.parley.common.calls.CallAudioOutputs.BLE_HEADSET
import app.parley.common.calls.CallAudioOutputs.BLE_SPEAKER
import app.parley.common.calls.CallAudioOutputs.BLUETOOTH_A2DP
import app.parley.common.calls.CallAudioOutputs.BLUETOOTH_SCO
import app.parley.common.calls.CallAudioOutputs.HEARING_AID
import app.parley.common.calls.CallAudioOutputs.USB_HEADSET
import app.parley.common.calls.CallAudioOutputs.WIRED_HEADPHONES
import app.parley.common.calls.CallAudioOutputs.WIRED_HEADSET
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioOutputsTest {
    private val builtInSpeaker = 2
    private val earpiece = 1

    @Test fun outputs_that_carry_a_call_count_as_a_headset() {
        listOf(WIRED_HEADSET, WIRED_HEADPHONES, USB_HEADSET, BLUETOOTH_SCO, BLE_HEADSET, HEARING_AID).forEach {
            assertTrue("type $it", CallAudioOutputs.carriesCalls(it))
        }
    }

    @Test fun a_media_only_speaker_watch_or_car_link_does_not() {
        // Music on a living-room speaker must never make the phone answer a call nobody can hear.
        listOf(BLUETOOTH_A2DP, BLE_SPEAKER, BLE_BROADCAST, builtInSpeaker, earpiece).forEach {
            assertFalse("type $it", CallAudioOutputs.carriesCalls(it))
        }
        assertFalse(CallAudioOutputs.headsetConnected(listOf(earpiece, builtInSpeaker, BLUETOOTH_A2DP)))
        assertFalse(CallAudioOutputs.headsetConnected(emptyList()))
    }

    @Test fun a_headset_beside_a_speaker_still_counts() {
        // Earbuds connected with both profiles show as A2DP and SCO: the SCO output is enough.
        assertTrue(CallAudioOutputs.headsetConnected(listOf(earpiece, BLUETOOTH_A2DP, BLUETOOTH_SCO)))
        assertTrue(CallAudioOutputs.headsetConnected(listOf(builtInSpeaker, WIRED_HEADSET)))
    }
}
