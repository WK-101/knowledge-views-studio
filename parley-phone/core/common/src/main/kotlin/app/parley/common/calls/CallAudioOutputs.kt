package app.parley.common.calls

/**
 * Which connected audio outputs count as "a headset" for auto-answer: only outputs that carry a call both ways,
 * so an answered call is heard on the person's head or in their car's hands-free, never on the earpiece of a phone
 * nobody holds. A Bluetooth speaker, a watch or a car's media link (A2DP, BLE speaker, BLE broadcast) plays music but
 * not calls, so it doesn't count.
 *
 * The values are android.media.AudioDeviceInfo's TYPE_* constants (stable platform API), mirrored here so the rule is
 * plain JVM code with tests; the telecom module's test checks they still match.
 */
object CallAudioOutputs {
    const val WIRED_HEADSET = 3
    const val WIRED_HEADPHONES = 4
    const val BLUETOOTH_SCO = 7
    const val BLUETOOTH_A2DP = 8
    const val USB_HEADSET = 22
    const val HEARING_AID = 23
    const val BLE_HEADSET = 26
    const val BLE_SPEAKER = 27
    const val BLE_BROADCAST = 30

    /** Outputs that carry a call: wired headsets and headphones, a USB headset, Bluetooth hands-free, LE Audio headsets, hearing aids. */
    private val CALL_CAPABLE = setOf(WIRED_HEADSET, WIRED_HEADPHONES, USB_HEADSET, BLUETOOTH_SCO, BLE_HEADSET, HEARING_AID)

    fun carriesCalls(type: Int): Boolean = type in CALL_CAPABLE

    /** Whether any of the connected outputs [types] carries a call. */
    fun headsetConnected(types: Iterable<Int>): Boolean = types.any(::carriesCalls)
}
