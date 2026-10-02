package app.parley.ui.contact

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.calls.CallerTune
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A tune made from a name is kept once in Parley's files and handed out through its FileProvider (manifest paths). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallerTunesTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun saves_a_wav_served_by_parleys_provider() = runBlocking {
        val uri = CallerTunes.save(context, "Ana Lima", 1)
        assertNotNull(uri)
        assertEquals("content", uri!!.scheme)
        assertEquals(context.packageName + ".files", uri.authority)
        assertTrue(CallerTunes.isOurs(context, uri.toString()))
        assertFalse(uri.toString().contains("ana", ignoreCase = true))
        val file = File(File(context.filesDir, "tunes"), CallerTune.fileName("Ana Lima", 1))
        assertArrayEquals(CallerTune.wav(CallerTune.render(CallerTune.compose("Ana Lima", 1))), file.readBytes())
        // The same pair again reuses the file and the URI.
        assertEquals(uri, CallerTunes.save(context, "ana lima", 1))
        CallerTunes.regrant(context)
    }

    @Test fun other_ringtones_are_not_ours() {
        assertFalse(CallerTunes.isOurs(context, null))
        assertFalse(CallerTunes.isOurs(context, "content://media/internal/audio/media/12"))
        assertFalse(CallerTunes.isOurs(context, "content://${context.packageName}.files/share/x.wav"))
        assertFalse(CallerTunes.isOurs(context, "file:///sdcard/tune.wav"))
    }
}
