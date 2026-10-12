package app.parley.work

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.shortcuts.ShortcutActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The weekly digest's Call for someone quieter than usual dials this week's person, never last week's. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DigestCallTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun a_new_digest_calls_its_own_person_while_last_weeks_is_still_shown() {
        val lastWeek = RemindersWorker.driftCall(context, CODE, "+447700900001", 1)
        val thisWeek = RemindersWorker.driftCall(context, CODE, "+447700900002", 2)
        assertEquals("+447700900002", shadowOf(thisWeek).savedIntent.getStringExtra(ShortcutActivity.EXTRA_NUMBER))
        // Same request code: one intent, brought up to date.
        assertEquals(lastWeek, thisWeek)
    }

    private companion object {
        const val CODE = 4242
    }
}
