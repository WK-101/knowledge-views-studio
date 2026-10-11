package app.parley.ui.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Make a favourite" is on every row of the sheet: TalkBack says whose, so the buttons are told apart. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DndStarredSpeechTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun each_make_a_favourite_button_names_its_person() {
        assertEquals("Make Ben a favourite", context.getString(R.string.fav_dnd_make_a11y, "Ben"))
        assertNotEquals(context.getString(R.string.fav_dnd_make_a11y, "Ben"), context.getString(R.string.fav_dnd_make_a11y, "Cy"))
    }
}
