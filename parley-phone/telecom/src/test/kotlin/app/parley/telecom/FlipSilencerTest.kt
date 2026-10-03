package app.parley.telecom

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import app.parley.common.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor

/** Flip to silence listens only while one call rings, and never when the call must be heard. */
@RunWith(RobolectricTestRunner::class)
internal class FlipSilencerTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private var window = false
    private lateinit var flip: FlipSilencer

    @Before fun sensor() {
        shadowOf(context.getSystemService(SensorManager::class.java)).addSensor(ShadowSensor.newInstance(Sensor.TYPE_GRAVITY))
        flip = FlipSilencer(context, inEmergencyWindow = { window }, silence = {})
    }

    private fun call(state: CallState = CallState.RINGING, emergency: Boolean = false) = CallUi(
        id = "t1", state = state, number = if (emergency) "112" else "+15551234567", hidden = false, name = null, label = null,
        photoUri = null, contactId = null, incoming = true, connectTimeMillis = 0, isConference = false, children = emptyList(),
        canHold = false, canMerge = false, canSwap = false, canMute = true, canSeparate = false, canDisconnectChild = false,
        canRespondViaText = false, accountLabel = null, verification = Verification.NOT_VERIFIED, disconnectReason = null,
        postDialWait = null, silenced = false, isEmergency = emergency,
    )

    @Test fun listensWhileACallRings() {
        flip.update(listOf(call()), enabled = true)
        assertEquals("t1", flip.watching)
        flip.update(listOf(call(state = CallState.ACTIVE)), enabled = true)
        assertNull(flip.watching)
    }

    @Test fun neverWhenOff() {
        flip.update(listOf(call()), enabled = false)
        assertNull(flip.watching)
    }

    @Test fun neverForAnEmergencyCall() {
        flip.update(listOf(call(emergency = true)), enabled = true)
        assertNull(flip.watching)
    }

    @Test fun neverDuringTheEmergencyCallBackWindow() {
        window = true
        flip.update(listOf(call()), enabled = true)
        assertNull(flip.watching)
    }
}
