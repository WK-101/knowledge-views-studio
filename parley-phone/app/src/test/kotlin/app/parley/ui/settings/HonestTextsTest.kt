package app.parley.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Texts that make a promise say where it ends (SECURITY: privacy posture). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HonestTextsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun delete_all_data_says_it_leaves_your_own_folders_alone() {
        val text = context.getString(R.string.wipe_outside).lowercase()
        listOf("backups", "exports", "synced folders").forEach { assertTrue(it, it in text) }
        // The Markdown export is gone (Export contacts replaced it): the dialog doesn't name it any more.
        assertTrue("markdown" !in text)
    }

    @Test fun i_m_on_hold_says_what_it_keeps() {
        val text = context.getString(app.parley.telecom.R.string.holdmode_start_explainer)
        assertTrue(text, "case file" in text)
    }
}
