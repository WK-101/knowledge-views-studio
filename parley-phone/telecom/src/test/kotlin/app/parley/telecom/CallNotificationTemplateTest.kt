package app.parley.telecom

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The one call-notification shape that real and rescue calls share: what each kind always carries. */
@RunWith(RobolectricTestRunner::class)
class CallNotificationTemplateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tap = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
    private val public = CallNotificationTemplate.publicVersion(context, CallNotifier.CH_INCOMING, "AL", "Incoming call")

    private fun person(uri: String? = "tel:+442079460000") = CallNotificationTemplate.person("Ada Lovelace", uri, null)

    @Test fun incoming_rings_full_screen_with_the_person_and_its_public_version() {
        val n = CallNotificationTemplate.incoming(
            context, CallNotifier.CH_INCOMING, "Ada Lovelace", "Mobile", person(), NotificationCompat.VISIBILITY_PUBLIC, public, tap, tap, tap,
        ).build()
        assertEquals(Notification.CATEGORY_CALL, n.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.fullScreenIntent != null)
        assertEquals("AL", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val caller = n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
        assertEquals("Ada Lovelace", caller?.name.toString())
        assertEquals("tel:+442079460000", caller?.uri)
    }

    @Test fun ongoing_is_silent_and_keeps_the_chosen_visibility() {
        val n = CallNotificationTemplate.ongoing(
            context, CallNotifier.CH_ONGOING, "Ada Lovelace", "Ongoing call", person(null), NotificationCompat.VISIBILITY_PRIVATE, public, tap, tap,
        ).build()
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
        val caller = n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
        assertNull("no URI given, none made up", caller?.uri)
    }

    @Test fun the_public_version_names_only_what_it_is_given() {
        val e = public.extras
        assertEquals("AL", e.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Incoming call", e.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.CATEGORY_CALL, public.category)
        assertNull(public.publicVersion)
    }
}
