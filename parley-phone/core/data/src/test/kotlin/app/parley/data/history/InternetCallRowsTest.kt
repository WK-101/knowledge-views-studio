package app.parley.data.history

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.provider.CallLog.Calls
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.backup.CallLogRecord
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeCallLogProvider
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Calls an app logged in the system call log (Android 14+) are read as that app's calls by their phone account, stay
 * so through delete-and-undo, the archive and a private contact's history, and never reach the missed-call notice.
 */
@RunWith(RobolectricTestRunner::class)
class InternetCallRowsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private lateinit var log: FakeCallLogProvider

    private val whatsApp = "com.whatsapp/com.whatsapp.voipcalling.SelfManagedConnectionService"
    private val sim = "com.android.phone/com.android.services.telephony.TelephonyConnectionService"

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        log = FakeCallLogProvider.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.READ_CONTACTS)
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    private fun row(number: String, date: Long, type: Int, component: String?, new: Boolean = false) {
        app.contentResolver.insert(
            Calls.CONTENT_URI,
            ContentValues().apply {
                put(Calls.NUMBER, number)
                put(Calls.DATE, date)
                put(Calls.TYPE, type)
                put(Calls.PHONE_ACCOUNT_COMPONENT_NAME, component)
                put(Calls.NEW, if (new) 1 else 0)
            },
        )
    }

    @Test fun rowsAreToldApartByTheirPhoneAccount() {
        row("+44 20 7946 0001", 3_000, Calls.INCOMING_TYPE, whatsApp)
        row("+44 20 7946 0001", 2_000, Calls.OUTGOING_TYPE, sim)
        row("+44 20 7946 0001", 1_000, Calls.INCOMING_TYPE, null)
        val calls = c.callLog.queryForNumber("+44 20 7946 0001").sortedByDescending { it.date }
        assertEquals(listOf("com.whatsapp", null, null), calls.map { it.appPackage })
        assertEquals(whatsApp, calls[0].accountComponent)
    }

    @Test fun aCallMissedInAnAppIsNotAPhoneMissedCall() {
        row("+44 20 7946 0002", 2_000, Calls.MISSED_TYPE, whatsApp, new = true)
        row("+44 20 7946 0003", 1_000, Calls.MISSED_TYPE, sim, new = true)
        assertEquals(listOf("+44 20 7946 0003"), c.callLog.unseenMissed().map { it.number })
    }

    @Test fun theAppIsKeptThroughTheArchiveAndUndo() {
        val rec = CallLogRecord("+44 20 7946 0004", 5_000, 30, Calls.INCOMING_TYPE, accountComponent = whatsApp)
        val e = ArchivedCalls.entry(rec, 1)
        assertEquals("com.whatsapp", e.appPackage)
        // A deleted call's sealed copy is made from its row: the account goes with it, so Undo puts back an app call.
        assertEquals(whatsApp, ArchivedCalls.record(e).accountComponent)
        assertEquals(whatsApp, ArchivedCalls.decode(ArchivedCalls.encode(ArchivedCalls.record(e))).accountComponent)
        assertNull(ArchivedCalls.entry(rec.copy(accountComponent = sim), 2).appPackage)
    }

    @Test fun aPrivateContactsAppCallStaysAnAppCallInTheirHistory() = runBlocking {
        val id = c.vault.save(null, ContactDetails(given = "Private", phones = listOf(DataItem(null, "+44 20 7946 0005", Phone.TYPE_MOBILE))))
        row("+44 20 7946 0005", 7_000, Calls.INCOMING_TYPE, whatsApp)
        row("+44 20 7946 0005", 6_000, Calls.INCOMING_TYPE, sim)
        assertEquals(2, c.vault.sweepCallLog(0))
        val calls = c.vault.privateCallsOf(id).sortedByDescending { it.date }
        assertEquals(listOf("com.whatsapp", null), calls.map { it.app })
        assertEquals("com.whatsapp", CallHistory.privateEntry(calls[0]).appPackage)
        assertEquals(0, log.rows().size)
    }
}
