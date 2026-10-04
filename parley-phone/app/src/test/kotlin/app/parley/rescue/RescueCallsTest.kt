package app.parley.rescue

import android.app.AlarmManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.calls.RescuePlan
import app.parley.common.calls.RescueWhen
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Setting a rescue call for later: one inexact alarm, kept on this phone only, cancelled or replaced cleanly. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RescueCallsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    @After fun clear() {
        RescueCalls.cancel(context)
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
