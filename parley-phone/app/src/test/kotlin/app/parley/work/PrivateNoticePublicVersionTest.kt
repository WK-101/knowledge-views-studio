package app.parley.work

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.parley.common.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every notice's lock-screen version is made in one place, and says no more than its neutral title. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PrivateNoticePublicVersionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val icon = app.parley.ui.R.drawable.ic_stat_missed

    @Test fun a_notice_is_private_and_its_public_version_has_only_the_neutral_title() {
        val n = PrivateNotice.builder(context, NotificationChannels.JOBS, icon, "Exported Ada.vcf", "Parley finished", "Saved to Downloads").build()
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val e = n.publicVersion.extras
        assertEquals("Parley finished", e.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(e.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun missed_calls_keep_their_category_and_count_on_the_lock_screen() {
        val p = PrivateNotice.publicVersion(context, NotificationChannels.JOBS, icon, "3 missed calls", NotificationCompat.CATEGORY_MISSED_CALL, 3)
        assertEquals(Notification.CATEGORY_MISSED_CALL, p.category)
        assertEquals(3, p.number)
        assertNull(p.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun no_category_or_count_unless_given() {
        val p = PrivateNotice.publicVersion(context, NotificationChannels.JOBS, icon, "Exporting")
        assertNull(p.category)
        assertEquals(0, p.number)
    }
}
