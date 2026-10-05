package app.parley.telecom

import android.app.Application
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

/** The rescue call's sound: the phone's audio mode is Parley's only while it plays. */
@RunWith(RobolectricTestRunner::class)
class RescueClipTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val audio = context.getSystemService(AudioManager::class.java)

    private fun clip(name: String, info: ShadowMediaPlayer.MediaInfo): String {
        val uri = Uri.parse("content://parley.test/$name")
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), info)
        return uri.toString()
    }

    /** Plays [uri] and hands back the player's shadow, to end the sound as Android would. */
    private fun playing(uri: String): ShadowMediaPlayer {
        var shadow: ShadowMediaPlayer? = null
        ShadowMediaPlayer.setCreateListener { _, s -> shadow = s }
        RescueClip(context).play(uri, speaker = false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        return shadow!!
    }

    @Test fun the_audio_mode_goes_back_when_the_sound_ends() {
        val player = playing(clip("hello.ogg", ShadowMediaPlayer.MediaInfo(1_000, 0)))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        // Played to its end, long before the answered call itself ends.
        player.invokeCompletionListener()
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }

    @Test fun the_audio_mode_goes_back_when_the_sound_fails() {
        val player = playing(clip("broken.ogg", ShadowMediaPlayer.MediaInfo(1_000, 0)))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        player.invokeErrorListener(1, 0)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }

    @Test fun the_audio_mode_goes_back_when_the_call_ends_first() {
        val uri = clip("long.ogg", ShadowMediaPlayer.MediaInfo(600_000, 0))
        val c = RescueClip(context)
        c.play(uri, speaker = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        c.stop()
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }
}
