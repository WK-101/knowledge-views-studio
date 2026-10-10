package app.parley.telecom

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.Verification
import app.parley.common.calls.LockScreenCaller
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What the call notifications actually say while the phone is locked: under "Caller on the lock screen" masking,
 * neither the posted notification nor its public version shows the caller's name or number.
 */
@RunWith(RobolectricTestRunner::class)
class CallNotifierLockScreenTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var notifier: CallNotifier? = null

    @After fun tearDown() {
        notifier?.release()
    }

    private fun call(name: String? = "Ada Lovelace", state: CallState = CallState.RINGING, saved: Boolean = name != null) = CallUi(
        id = "n1", state = state, number = "+442079460000", hidden = false, name = name, label = "Mobile",
        photoUri = null, contactId = if (saved) 7L else null, incoming = true, connectTimeMillis = if (state == CallState.ACTIVE) 1_000 else 0,
        isConference = false, children = emptyList(), canHold = true, canMerge = false, canSwap = false, canMute = true,
        canSeparate = false, canDisconnectChild = false, canRespondViaText = true, accountLabel = "SIM", verification = Verification.NOT_VERIFIED,
        disconnectReason = null, postDialWait = null, silenced = false, isEmergency = false, savedCaller = saved,
    )

    private fun post(mode: LockScreenCaller, locked: Boolean, vararg calls: CallUi): List<String> {
        val n = CallNotifier(context, { mode }, { locked })
        notifier = n
        n.update(calls.toList())
        return shadowOf(nm).allNotifications.flatMap { listOfNotNull(it, it.publicVersion) }.flatMap(::texts)
    }

    /** Every line the notification can show: title, text, sub and big text, and the calling person's name. */
    private fun texts(n: Notification): List<String> {
        val e = n.extras
        val person = e.getParcelable("android.callPerson", android.app.Person::class.java)
        return listOfNotNull(
            e.getCharSequence(Notification.EXTRA_TITLE), e.getCharSequence(Notification.EXTRA_TEXT),
            e.getCharSequence(Notification.EXTRA_SUB_TEXT), e.getCharSequence(Notification.EXTRA_BIG_TEXT),
            e.getCharSequence(Notification.EXTRA_INFO_TEXT), person?.name,
        ).map { it.toString() }
    }

    private fun List<String>.mentions(part: String) = any { it.contains(part) }

    @Test fun unlocked_the_notification_shows_the_name_and_number() {
        val shown = post(LockScreenCaller.INITIALS, locked = false, call())
        assertTrue(shown.mentions("Ada Lovelace"))
        assertTrue(shown.mentions("7946"))
    }

    @Test fun initials_show_neither_the_name_nor_the_number() {
        val shown = post(LockScreenCaller.INITIALS, locked = true, call())
        assertTrue(shown.contains("AL"))
        assertFalse(shown.mentions("Ada"))
        assertFalse(shown.mentions("7946"))
        assertFalse(shown.mentions("Mobile"))
    }

    @Test fun incoming_call_shows_neither_the_name_nor_the_number() {
        val shown = post(LockScreenCaller.NONE, locked = true, call())
        assertFalse(shown.mentions("Ada"))
        assertFalse(shown.mentions("AL"))
        assertFalse(shown.mentions("7946"))
    }

    @Test fun incoming_call_hides_an_unknown_number_too() {
        val shown = post(LockScreenCaller.NONE, locked = true, call(name = null))
        assertFalse(shown.mentions("7946"))
    }

    @Test fun the_ongoing_call_notification_is_masked_as_well() {
        val shown = post(LockScreenCaller.INITIALS, locked = true, call(state = CallState.ACTIVE))
        assertFalse(shown.mentions("Ada"))
        assertFalse(shown.mentions("7946"))
    }

    @Test fun a_silenced_call_has_a_lock_screen_version_too() {
        // Posted while unlocked; the lock screen then shows only its public version (as for a ringing call).
        val n = CallNotifier(context, { LockScreenCaller.NONE }, { false })
        notifier = n
        n.update(listOf(call().copy(silenced = true)))
        val posted = shadowOf(nm).allNotifications.single()
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        val public = texts(posted.publicVersion!!)
        assertFalse(public.mentions("Ada"))
        assertFalse(public.mentions("7946"))
        assertTrue(public.mentions("Silenced call"))
    }

    @Test fun initials_keep_an_unknown_number_to_decide_by() {
        val shown = post(LockScreenCaller.INITIALS, locked = true, call(name = null))
        assertTrue(shown.mentions("7946"))
        assertEquals(1, shadowOf(nm).allNotifications.count { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.contains("7946") == true })
    }
}
