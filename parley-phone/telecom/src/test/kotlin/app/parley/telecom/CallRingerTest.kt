package app.parley.telecom

import android.app.Application
import android.media.AudioManager
import android.os.VibratorManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Parley's own ringer never leaves a vibration running that nothing can stop. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class CallRingerTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val am: AudioManager get() = context.getSystemService(AudioManager::class.java)
    private val scope = TestScope(StandardTestDispatcher())
    private val vibrating: Boolean get() = shadowOf(context.getSystemService(VibratorManager::class.java).defaultVibrator).isVibrating

    @Before fun normalRinger() {
        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    private fun vibrateFirst(ringer: CallRinger, s: CallSession, ringing: () -> Boolean = { true }) =
        ringer.vibrateFirst(context, s, pattern = { null }, otherCallActive = false, stillRinging = ringing, uri = { null }) {}

    @Test fun vibrate_first_stops_vibrating_when_the_phone_goes_silent_before_the_tone() {
        val ringer = CallRinger(scope) {}
        vibrateFirst(ringer, CallSession("a"))
        scope.advanceTimeBy(CallRinger.RINGER_STOP_MIN_MS + 10)
        scope.runCurrent()
        assertTrue("vibrates alone first", vibrating)
        am.ringerMode = AudioManager.RINGER_MODE_SILENT
        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertFalse("silent mode is silent, as Telecom would make it", vibrating)
    }

    @Test fun another_call_taking_the_ringer_stops_what_it_played_for_the_first() {
        val ringer = CallRinger(scope) {}
        vibrateFirst(ringer, CallSession("a"))
        scope.advanceTimeBy(CallRinger.RINGER_STOP_MIN_MS + 10)
        scope.runCurrent()
        assertTrue(vibrating)
        // A second ringing call's haptic caller ID claims the ringer, then gives up (it stopped ringing).
        am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        ringer.vibrateOnly(context, CallSession("b"), longArrayOf(0, 200, 200), otherCallActive = false, stillRinging = { false }) {}
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
        assertNull(ringer.toneFor)
        assertFalse("nothing keeps vibrating unclaimed", vibrating)
    }
}
