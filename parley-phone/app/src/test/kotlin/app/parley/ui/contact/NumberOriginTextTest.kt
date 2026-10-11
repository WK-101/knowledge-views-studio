package app.parley.ui.contact

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.people.NumberOrigin
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The number passport's line on a contact's page: every origin has words, and a sync says how the number came. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NumberOriginTextTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun a_number_from_a_sync_says_it_was_added_through_sync() {
        // Sync-origin numbers warn on the call screen, so the page says plainly how they came.
        assertEquals("Added through sync", context.getString(numberOriginText(NumberOrigin.SYNC)))
        assertEquals(NumberOrigin.entries.size, NumberOrigin.entries.map { context.getString(numberOriginText(it)) }.toSet().size)
    }
}
