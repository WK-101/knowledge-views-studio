package app.parley.rescue

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.parley.R
import app.parley.common.NotificationIds
import app.parley.common.calls.RescuePlan
import app.parley.common.calls.RescueWhen
import app.parley.data.StatePrefs
import app.parley.data.security.RecordCrypto
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.telecom.CallState
import app.parley.telecom.RescueCall
import app.parley.telecom.RescueCaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Check-in, Rescue call's second mode: an unanswered call offers the helper, an answer says "I'm fine", nothing is sent. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CheckInTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val realClock = RescueCalls.clock
    private val realLookUp = RescueCalls.lookUp
    private val realIo = RescueCalls.io
    private val realHelper = RescueCalls.helperFor
    private val realNotice = RescueCalls.notice
    private val sam = "+447700900001"

    /** The helper each unanswered check-in offered (null: none could be named). */
    private val offered = mutableListOf<CheckInHelper?>()

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        RescueCalls.helperFor = { _, chosen -> CheckInHelper("Sam", chosen) }
        RescueCalls.notice = { _, h -> offered += h }
    }

    @After fun tearDown() {
        RescueCalls.cancel(context)
        RescueCall.yieldToRealCall()
        RescueCalls.clock = realClock
        RescueCalls.lookUp = realLookUp
        RescueCalls.io = realIo
        RescueCalls.helperFor = realHelper
        RescueCalls.notice = realNotice
        RescueCalls.forgetForTest()
    }

    private fun onVirtualTime(block: suspend TestScope.() -> Unit) {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        RescueCalls.io = main
        val base = 1_790_000_000_000L
        RescueCalls.clock = { base + main.scheduler.currentTime }
        RescueCalls.lookUp = { _, r, clip -> RescueCaller(name = r.name, clip = clip) }
        try {
            runTest(main) { block() }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private val checkIn = RescueCalls.Choices(name = "Check-in", whenChoice = RescueWhen.IN_1, checkIn = true, helper = sam)

    private suspend fun TestScope.ringIt() {
        RescueCalls.set(context, checkIn)
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(CallState.RINGING, RescueCall.state.value?.call?.state)
    }

    @Test fun declined_it_offers_the_helper_once() = onVirtualTime {
        ringIt()
        assertTrue(offered.isEmpty())
        RescueCall.end()
        runCurrent()
        assertEquals(listOf(CheckInHelper("Sam", sam)), offered)
    }

    @Test fun answered_it_says_fine_and_offers_nothing() = onVirtualTime {
        ringIt()
        RescueCall.answer()
        RescueCall.end()
        runCurrent()
        assertTrue(offered.isEmpty())
    }

    @Test fun a_plain_rescue_call_never_offers_a_helper() = onVirtualTime {
        RescueCalls.set(context, checkIn.copy(checkIn = false))
        advanceTimeBy(60_001)
        runCurrent()
        RescueCall.end()
        runCurrent()
        assertTrue(offered.isEmpty())
    }

    @Test fun too_late_to_ring_it_still_offers_the_helper() = runTest {
        val now = System.currentTimeMillis()
        RescueCalls.set(context, checkIn.copy(whenChoice = RescueWhen.IN_15), now)
        val p = RescueCalls.pending(context).value!!
        RescueCalls.due(context, p.id, p.atMillis + RescuePlan.STALE_MS + 1)
        assertNull(RescueCalls.pending(context).value)
        assertEquals(1, offered.size)
    }

    @Test fun a_check_in_never_rings_now_and_keeps_its_helper_sealed() = runTest {
        val now = System.currentTimeMillis()
        val outcome = RescueCalls.set(context, checkIn.copy(whenChoice = RescueWhen.NOW), now)
        assertEquals(RescueCalls.Outcome.WAITING, outcome)
        val p = RescueCalls.pending(context).value!!
        assertEquals(now + 15 * 60_000, p.atMillis)
        assertEquals(sam, p.helper)
        val crypto = RecordCrypto.get(context)
        val stored = StatePrefs.open(context, "rescue_call").all.values.filterIsInstance<String>()
        assertFalse("the helper's number is never plain", stored.any { it.contains(sam) })
        assertTrue(stored.any { crypto.isSealed(it) })
        // Read back after Parley restarts: still a check-in with its helper.
        RescueCalls.forgetForTest()
        assertEquals(sam, RescueCalls.pending(context).value?.helper)
    }

    @Test fun a_new_check_in_or_an_answer_takes_an_earlier_unanswered_notice_away() = onVirtualTime {
        val nm = context.getSystemService(NotificationManager::class.java)
        fun shown() = shadowOf(nm).activeNotifications.any { it.tag == NotificationIds.TAG_CHECK_IN }
        CheckInNotice.post(context, CheckInHelper("Sam", sam))
        ringIt()
        assertFalse("setting a new check-in clears the old notice", shown())
        CheckInNotice.post(context, CheckInHelper("Sam", sam))
        RescueCall.answer()
        RescueCall.end()
        runCurrent()
        assertFalse("answering says you're fine: the old notice goes", shown())
    }

    @Test fun the_notice_offers_text_and_call_and_names_nobody_on_the_lock_screen() {
        CheckInNotice.post(context, CheckInHelper("Sam", sam))
        val nm = context.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).activeNotifications.single { it.tag == NotificationIds.TAG_CHECK_IN }.notification
        assertEquals(2, n.actions.size)
        assertTrue(n.actions[0].title.contains("Sam"))
        val public = n.publicVersion
        assertEquals(context.getString(R.string.checkin_missed_public), public.extras.getString("android.title"))
        assertFalse(public.actions.any { it.title.contains("Sam") })
        // Text only opens the messages app with the words ready: the user sends them.
        val text = CheckInNotice.textIntent(context, sam)
        assertEquals(Intent.ACTION_SENDTO, text.action)
        assertEquals(context.getString(R.string.checkin_sms_body), text.getStringExtra("sms_body"))
    }
}
