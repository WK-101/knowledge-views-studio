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

    @Test fun unused_tunes_are_deleted_only_when_every_use_is_known() {
        // Written directly: FileProvider keeps the first test's files directory for the whole run.
        val dir = File(context.filesDir, "tunes").apply { mkdirs() }
        val keptFile = File(dir, CallerTune.fileName("Ana Lima", 0)).apply { writeBytes(byteArrayOf(1)) }
        val replacedFile = File(dir, CallerTune.fileName("Bo Chen", 0)).apply { writeBytes(byteArrayOf(2)) }
        val kept = "content://${context.packageName}.files/tunes/${keptFile.name}"
        // A source that couldn't be read: nothing goes.
        assertEquals(0, CallerTunes.prune(context, null))
        assertTrue(replacedFile.exists())
        // Bo's ringtone was replaced by a system one: his tune (and its grants) go, Ana's stays.
        assertEquals(1, CallerTunes.prune(context, listOf(kept, "content://media/internal/audio/media/12")))
        assertTrue(keptFile.exists())
        assertFalse(replacedFile.exists())
    }

    @Test fun other_ringtones_are_not_ours() {
        assertFalse(CallerTunes.isOurs(context, null))
        assertFalse(CallerTunes.isOurs(context, "content://media/internal/audio/media/12"))
        assertFalse(CallerTunes.isOurs(context, "content://${context.packageName}.files/share/x.wav"))
        assertFalse(CallerTunes.isOurs(context, "file:///sdcard/tune.wav"))
    }
}
