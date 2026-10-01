package app.parley.telecom

import android.media.AudioDeviceInfo
import app.parley.common.calls.CallAudioOutputs
import org.junit.Assert.assertEquals
import org.junit.Test

/** The plain-JVM copies of the platform's output types still match AudioDeviceInfo's. */
class CallAudioOutputsTypesTest {
    @Test fun mirrored_types_match_the_platform() {
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, CallAudioOutputs.WIRED_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, CallAudioOutputs.WIRED_HEADPHONES)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, CallAudioOutputs.BLUETOOTH_SCO)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, CallAudioOutputs.BLUETOOTH_A2DP)
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, CallAudioOutputs.USB_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_HEARING_AID, CallAudioOutputs.HEARING_AID)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, CallAudioOutputs.BLE_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_BLE_SPEAKER, CallAudioOutputs.BLE_SPEAKER)
        assertEquals(AudioDeviceInfo.TYPE_BLE_BROADCAST, CallAudioOutputs.BLE_BROADCAST)
    }
}
