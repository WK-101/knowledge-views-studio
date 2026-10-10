package app.parley.telecom

import android.app.Application
import android.content.Context
import android.media.AudioManager
import app.parley.common.calls.RingStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** "Increasing" ring volume: it ramps up to the user's volume and always puts it back, without fighting the user. */
@RunWith(RobolectricTestRunner::class)
internal class RingVolumeRampTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val am: AudioManager get() = context.getSystemService(AudioManager::class.java)
    private val ring: Int get() = am.getStreamVolume(AudioManager.STREAM_RING)

    @Before fun ringerAtSix() {
        context.getSharedPreferences("parley_ring_ramp", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("parley_ring_boost", Context.MODE_PRIVATE).edit().clear().commit()
        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        am.setStreamVolume(AudioManager.STREAM_RING, 6, 0)
    }

    @Test fun ramps_from_low_up_to_the_users_volume_and_is_done_there() {
        assertEquals(6, RingVolumeRamp.begin(context, RingStyle.INCREASING))
        assertEquals(1, ring)
        assertTrue(RingVolumeRamp.isRamping(context))
        (2..5).forEach { v -> assertTrue(RingVolumeRamp.advance(context, v)); assertEquals(v, ring) }
        assertFalse("the last step reaches the user's volume", RingVolumeRamp.advance(context, 6))
        assertEquals(6, ring)
        assertFalse(RingVolumeRamp.isRamping(context))
    }

    @Test fun ending_the_ringing_puts_the_volume_back() {
        RingVolumeRamp.begin(context, RingStyle.INCREASING)
        RingVolumeRamp.advance(context, 2)
        // Answered, declined, silenced or gone: the remaining steps go.
        RingVolumeRamp.restore(context)
        assertEquals(6, ring)
        assertFalse(RingVolumeRamp.isRamping(context))
        assertFalse("a late step does nothing", RingVolumeRamp.advance(context, 3))
        assertEquals(6, ring)
    }

    @Test fun a_crash_mid_ramp_is_put_right_by_the_next_call_or_app_start() {
        RingVolumeRamp.begin(context, RingStyle.VIBRATE_FIRST)
        RingVolumeRamp.advance(context, 3)
        // The process died here. The next app start (or call) restores both Ring loud and the ramp.
        RingBoost.restore(context)
        assertEquals(6, ring)
        assertFalse(RingVolumeRamp.isRamping(context))
    }

    @Test fun the_user_moving_the_volume_stops_the_ramp_and_keeps_theirs() {
        RingVolumeRamp.begin(context, RingStyle.INCREASING)
        RingVolumeRamp.advance(context, 2)
        am.setStreamVolume(AudioManager.STREAM_RING, 4, 0)
        assertFalse(RingVolumeRamp.advance(context, 3))
        assertEquals(4, ring)
        RingVolumeRamp.restore(context)
        assertEquals("their choice stays", 4, ring)
    }

    @Test fun on_vibrate_the_restore_waits_and_happens_later() {
        RingVolumeRamp.begin(context, RingStyle.INCREASING)
        am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        RingVolumeRamp.restore(context)
        assertTrue("kept for later", RingVolumeRamp.isRamping(context))
        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        am.setStreamVolume(AudioManager.STREAM_RING, 1, 0)
        RingVolumeRamp.restore(context)
        assertEquals(6, ring)
    }

    @Test fun no_ramp_for_normal_style_vibrate_mode_or_a_low_volume() {
        assertNull(RingVolumeRamp.begin(context, RingStyle.NORMAL))
        am.setStreamVolume(AudioManager.STREAM_RING, 1, 0)
        assertNull(RingVolumeRamp.begin(context, RingStyle.INCREASING))
        am.setStreamVolume(AudioManager.STREAM_RING, 6, 0)
        am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertNull(RingVolumeRamp.begin(context, RingStyle.INCREASING))
        assertFalse(RingVolumeRamp.isRamping(context))
    }

    @Test fun ring_loud_takes_over_from_a_ramp_and_saves_the_users_volume() {
        RingVolumeRamp.begin(context, RingStyle.INCREASING)
        RingBoost.boost(context)
        assertEquals(am.getStreamMaxVolume(AudioManager.STREAM_RING), ring)
        assertFalse(RingVolumeRamp.isRamping(context))
        RingBoost.restore(context)
        assertEquals("the user's volume, not the ramp's", 6, ring)
    }
}
