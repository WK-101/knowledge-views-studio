package app.parley.calls

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.parley.common.PhoneIdentity
import app.parley.common.calls.ToCall
import app.parley.common.calls.ToCallSource
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Promise a time and They'll call me back, as stored: the promise rides on the To call item (and the call screen reads
 * it back), and a callback is a To call item due by its date plus a window in force without the hint's yes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallPromisesTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val london: ZoneId = ZoneId.of("Europe/London")
    private val number = "+441632960001"

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(london))
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        VaultCrypto.appContext = context
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        c = DataContainer(context)
        ToCallReminders.containerOf = { c }
    }

    @After fun tearDown() {
        c.scope.cancel()
    }

    private fun t(d: Int, h: Int, mi: Int = 0): Long = ZonedDateTime.of(2026, 9, d, h, mi, 0, 0, london).toInstant().toEpochMilli()

    @Test fun the_message_says_the_time_as_they_will_read_it() {
        val now = t(29, 14)
        fun clock(at: Long, zone: ZoneId = london) =
            android.text.format.DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone(zone) }.format(java.util.Date(at))
        assertEquals("I'll call you back at ${clock(t(29, 15, 30))}.", CallPromises.message(context, t(29, 15, 30), now, null))
        assertEquals("I'll call you back tomorrow at ${clock(t(30, 9))}.", CallPromises.message(context, t(30, 9), now, null))
        // In their time: 18:00 in Karachi is 14:00 in London.
        val karachi = ZoneId.of("Asia/Karachi")
        assertEquals("I'll call you back at ${clock(t(29, 14), karachi)} your time.", CallPromises.message(context, t(29, 14), t(29, 10), karachi))
    }

    @Test fun a_promise_is_kept_on_to_call_and_read_back_for_the_call_screen() = runBlocking {
        val now = System.currentTimeMillis()
        val at = now + ToCall.HOUR_MS
        assertNotNull(CallPromises.promise(context, number, null, at, null, now))
        assertEquals(at, CallPromises.promisedAt(c, number, null))
        assertNull(CallPromises.promisedAt(c, "+441632960999", null))
    }

    @Test fun a_callback_is_due_by_its_date_and_lets_the_line_ring_meanwhile() = runBlocking {
        val now = System.currentTimeMillis()
        val by = now + 24 * ToCall.HOUR_MS
        assertTrue(CallPromises.expectCallback(context, c, number, null, by, anyCaller = true, now = now))
        val key = PhoneIdentity.key(number, PhoneEnv.countryIso(context, null))
        val item = c.toCall.state.value.items.single { it.key == key }
        assertEquals(ToCallSource.CALLBACK, item.source)
        assertEquals(by, item.at)
        // In force without the To call hint's yes, and for numbers nobody saved too.
        val w = c.familySafety.windows(now + 1).single()
        assertTrue(w.callback && w.anyCaller)
        assertEquals(by, w.end)
        // Done on To call: the window goes with the line.
        ToCallReminders.update(context) { ToCall.done(it, key, now + 2) }
        assertTrue(c.familySafety.windows(now + 3).isEmpty())
    }
}
