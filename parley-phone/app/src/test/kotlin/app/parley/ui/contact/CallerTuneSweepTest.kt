package app.parley.ui.contact

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.data.StartGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The ringtone sweep at start, kept off the start-up path: it reads what is in use (the private contacts' ringtones list the vault)
 * only when there is a tune to look at, and runs only after the full app has started and its first screens are drawn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallerTuneSweepTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val dir get() = File(context.filesDir, "tunes")

    @Before fun setUp() {
        dir.deleteRecursively()
    }

    @Test fun with_no_tunes_nothing_is_read() = runBlocking {
        var reads = 0
        val gone = CallerTunes.sweep(context, contacts = { reads++; emptyList() }, private = { reads++; emptyList() }, labels = { reads++; emptyList() })
        assertEquals(0, gone)
        assertEquals("no provider query, no vault listing", 0, reads)
        // An empty folder is the same as none.
        dir.mkdirs()
        CallerTunes.sweep(context, contacts = { reads++; emptyList() }, private = { reads++; emptyList() }, labels = { reads++; emptyList() })
        assertEquals(0, reads)
    }

    @Test fun with_tunes_unused_ones_go_and_used_ones_stay() = runBlocking {
        dir.mkdirs()
        File(dir, "parley-tune-0123456789abcdef.wav").writeText("x")
        File(dir, "parley-tune-fedcba9876543210.wav").writeText("x")
        val used = "content://${context.packageName}.files/tunes/parley-tune-0123456789abcdef.wav"
        var privateRead = false
        CallerTunes.sweep(context, contacts = { emptyList() }, private = { privateRead = true; listOf(used) }, labels = { emptyList() })
        assertTrue(privateRead)
        assertTrue(File(dir, "parley-tune-0123456789abcdef.wav").exists())
        assertFalse(File(dir, "parley-tune-fedcba9876543210.wav").exists())
    }

    @Test fun a_ringtone_that_cant_be_read_keeps_every_tune() = runBlocking {
        dir.mkdirs()
        File(dir, "parley-tune-0123456789abcdef.wav").writeText("x")
        CallerTunes.sweep(context, contacts = { emptyList() }, private = { null }, labels = { emptyList() })
        assertTrue(File(dir, "parley-tune-0123456789abcdef.wav").exists())
    }

    @Test fun the_sweep_waits_for_the_full_start_and_the_first_screens() = runTest {
        val gate = StartGate()
        var ran = false
        launch { CallerTunes.afterStart(gate, afterMs = 20_000L) { ran = true } }
        advanceTimeBy(60_000L)
        runCurrent()
        assertFalse("a process started for a call never sweeps", ran)
        gate.open()
        runCurrent()
        advanceTimeBy(19_000L)
        runCurrent()
        assertFalse("not while the first screens are drawn", ran)
        advanceTimeBy(1_001L)
        runCurrent()
        assertTrue(ran)
    }
}
