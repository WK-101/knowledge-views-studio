package app.parley.rescue

import android.app.AlarmManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.calls.RescuePlan
import app.parley.common.calls.RescueWhen
import app.parley.telecom.CallState
import app.parley.telecom.RescueCall
import app.parley.telecom.RescueCaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Setting a rescue call for later: one inexact alarm, kept on this phone only, cancelled or replaced cleanly. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RescueCallsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    private val realClock = RescueCalls.clock
    private val realBoot = RescueCalls.bootCount
    private val realLookUp = RescueCalls.lookUp

    @After fun clear() {
        RescueCalls.cancel(context)
        RescueCall.yieldToRealCall()
        RescueCalls.clock = realClock
        RescueCalls.bootCount = realBoot
        RescueCalls.lookUp = realLookUp
        RescueCalls.forgetForTest()
    }

    /** Runs [block] with Parley's main thread and wall clock on virtual time, and who calls looked up as typed. */
    private fun onVirtualTime(block: suspend kotlinx.coroutines.test.TestScope.(lookups: () -> Int) -> Unit) {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val base = 1_790_000_000_000L
        var n = 0
        RescueCalls.clock = { base + main.scheduler.currentTime }
        RescueCalls.lookUp = { _, r, clip -> n++; RescueCaller(name = r.name, clip = clip) }
        try {
            runTest(main) {
                block { n }
                // Nothing keeps running on the virtual main thread afterwards.
                RescueCall.yieldToRealCall()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun a_short_wait_rings_on_time_from_parleys_own_timer() = onVirtualTime { lookups ->
        RescueCalls.set(context, RescueCalls.Choices(name = "Mum", whenChoice = RescueWhen.IN_1))
        val id = RescueCalls.pending(context).value!!.id
        advanceTimeBy(59_000)
        runCurrent()
        assertNull("not yet", RescueCall.state.value)
        advanceTimeBy(1_001)
        runCurrent()
        val call = RescueCall.state.value?.call
        assertEquals(CallState.RINGING, call?.state)
        assertEquals("Mum", call?.name)
        // Rung: nothing waits any more, and the alarm that may still come rings nothing.
        assertNull(RescueCalls.pending(context).value)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        RescueCalls.due(context, id)
        assertEquals(1, lookups())
    }

    @Test fun the_alarm_and_the_timer_ring_it_once() = onVirtualTime { lookups ->
        RescueCalls.set(context, RescueCalls.Choices(name = "Sam", whenChoice = RescueWhen.IN_5))
        val p = RescueCalls.pending(context).value!!
        // The alarm comes first (a moment early, as Android batches them).
        RescueCalls.due(context, p.id, p.atMillis - 10_000)
        runCurrent()
        assertEquals(CallState.RINGING, RescueCall.state.value?.call?.state)
        advanceTimeBy(6 * 60_000L)
        runCurrent()
        assertEquals(1, lookups())
    }

    @Test fun after_a_restart_a_call_waiting_is_dropped_and_the_screen_says_so() = onVirtualTime { _ ->
        RescueCalls.bootCount = { 7 }
        RescueCalls.set(context, RescueCalls.Choices(name = "Mum", whenChoice = RescueWhen.IN_15))
        // Parley stopped and started again on the same boot: still waiting, and its alarm is set again.
        RescueCalls.forgetForTest()
        alarms.scheduledAlarms.toList().forEach { a -> a.operation?.let { context.getSystemService(AlarmManager::class.java).cancel(it) } }
        assertFalse(RescueCalls.refresh(context))
        assertNotNull(RescueCalls.pending(context).value)
        assertEquals(1, alarms.scheduledAlarms.size)
        // The phone restarted: its alarm is gone, so nothing is shown as waiting.
        RescueCalls.forgetForTest()
        RescueCalls.bootCount = { 8 }
        assertTrue(RescueCalls.refresh(context))
        assertNull(RescueCalls.pending(context).value)
        assertFalse("said once", RescueCalls.refresh(context))
    }

    @Test fun a_call_long_past_its_time_is_dropped_when_the_screen_opens() = onVirtualTime { _ ->
        RescueCalls.set(context, RescueCalls.Choices(name = "Mum", whenChoice = RescueWhen.AT_TIME, minuteOfDay = 18 * 60))
        val p = RescueCalls.pending(context).value!!
        RescueCalls.clock = { p.atMillis + RescuePlan.STALE_MS + 1 }
        assertTrue(RescueCalls.refresh(context))
        assertNull(RescueCalls.pending(context).value)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun a_call_for_later_waits_on_one_inexact_alarm() = runTest {
        val now = System.currentTimeMillis()
        val outcome = RescueCalls.set(context, RescueCalls.Choices(name = "Mum", whenChoice = RescueWhen.IN_5), now)
        assertEquals(RescueCalls.Outcome.WAITING, outcome)
        val p = RescueCalls.pending(context).value
        assertNotNull(p)
        assertEquals("Mum", p!!.name)
        assertEquals(now + 5 * 60_000, p.atMillis)
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(p.atMillis, alarm.triggerAtMs)
        // Inexact and allowed while idle; never an alarm clock, which needs a permission Parley doesn't hold.
        assertTrue(alarm.isAllowWhileIdle)
        assertNull(alarm.alarmClockInfo)
    }

    @Test fun setting_another_replaces_the_first_and_cancel_leaves_nothing() = runTest {
        val now = System.currentTimeMillis()
        RescueCalls.set(context, RescueCalls.Choices(name = "Mum", whenChoice = RescueWhen.IN_15), now)
        val first = RescueCalls.pending(context).value!!.id
        RescueCalls.set(context, RescueCalls.Choices(name = "Sam", whenChoice = RescueWhen.AT_TIME, minuteOfDay = 18 * 60), now)
        val second = RescueCalls.pending(context).value!!
        assertTrue(first != second.id)
        assertEquals("Sam", second.name)
        assertEquals(1, alarms.scheduledAlarms.size)
        // The first one's alarm, should it still come, rings nothing.
        RescueCalls.due(context, first, second.atMillis)
        assertEquals(second, RescueCalls.pending(context).value)
        RescueCalls.cancel(context)
        assertNull(RescueCalls.pending(context).value)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun a_very_late_alarm_rings_nothing() = runTest {
        val now = System.currentTimeMillis()
        RescueCalls.set(context, RescueCalls.Choices(name = "Mum", whenChoice = RescueWhen.IN_1), now)
        val p = RescueCalls.pending(context).value!!
        RescueCalls.due(context, p.id, p.atMillis + RescuePlan.STALE_MS + 1)
        assertNull(RescueCalls.pending(context).value)
    }

    @Test fun the_choices_are_remembered_on_this_phone() {
        RescueCalls.saveChoices(context, RescueCalls.Choices(name = "Mum", number = "+15550001111", whenChoice = RescueWhen.IN_15, minuteOfDay = 7 * 60))
        val c = RescueCalls.choices(context)
        assertEquals("Mum", c.name)
        assertEquals("+15550001111", c.number)
        assertEquals(RescueWhen.IN_15, c.whenChoice)
        assertEquals(7 * 60, c.minuteOfDay)
    }
}
