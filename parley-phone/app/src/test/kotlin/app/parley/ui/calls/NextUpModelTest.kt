package app.parley.ui.calls

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.calls.NextUpKind
import app.parley.common.security.DuressMachine
import app.parley.common.security.PinVerdict
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.EventItem
import app.parley.data.security.Concealment
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Next up over the real stores: a birthday today and a Circle member due at the hours they usually answer each get a
 * line, and a duress unlock takes the Circle's line away (a relationship insight) while the birthday stays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NextUpModelTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val number = "+1 202 555 0142"

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        VaultCrypto.appContext = context
        Concealment.init(context)
        Concealment.forgetForTest(newProcess = true)
        c = DataContainer(context)
    }

    @After fun tearDown() {
        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.NORMAL, false))
        scope.cancel()
        c.scope.cancel()
    }

    private fun model(calls: List<CallEntry>): NextUpModel {
        val flow = MutableStateFlow<List<CallEntry>?>(calls)
        val toCall = ToCallModel(c, scope, flow, MutableStateFlow(emptySet()))
        return NextUpModel(scope, toCall, c.toCall.state, flow, c.contacts, c.circle, c.extras.chapters, c.privacy.flow, "US")
    }

    private suspend fun kinds(m: NextUpModel, until: (Set<NextUpKind>) -> Boolean): Set<NextUpKind> =
        withTimeoutOrNull(TIMEOUT_MS) { m.items.first { items -> until(items.map { it.line.kind }.toSet()) } }?.map { it.line.kind }?.toSet()
            ?: m.items.value.map { it.line.kind }.toSet()

    @Test fun a_birthday_and_a_good_time_show_and_duress_hides_the_good_time() = runBlocking {
        val today = LocalDate.now()
        val ada = c.contacts.save(
            null,
            ContactDetails(
                given = "Ada", phones = listOf(DataItem(null, number, Phone.TYPE_MOBILE)),
                events = listOf(EventItem(date = "1990-%02d-%02d".format(today.monthValue, today.dayOfMonth), type = Event.TYPE_BIRTHDAY)),
            ),
            null, null, false,
        )!!
        val bobDetails = ContactDetails(given = "Bob", phones = listOf(DataItem(null, "+1 202 555 0177", Phone.TYPE_MOBILE)))
        val bob = c.contacts.save(null, bobDetails, null, null, false)!!
        c.contacts.refresh()
        val bobKey = c.contacts.loadNow().single { it.id == bob.contactId }.lookupKey
        c.circle.setRhythm(bobKey, bob.contactId, everyDays = 7)
        // Eight answered calls at this hour on earlier days: Bob is usually free now, and due (nothing in the last week).
        val now = System.currentTimeMillis()
        val calls = (8..15).map { i ->
            CallEntry(i.toLong(), "+1 202 555 0177", null, if (i % 2 == 0) CallType.INCOMING else CallType.OUTGOING, now - i * DAY, 120, null, false, false)
        }
        val m = model(calls)
        val before = kinds(m) { NextUpKind.OCCASION in it && NextUpKind.GOOD_TIME in it }
        assertTrue("$before (Ada ${ada.contactId})", NextUpKind.OCCASION in before && NextUpKind.GOOD_TIME in before)

        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.DURESS, false))
        val after = kinds(m) { NextUpKind.GOOD_TIME !in it }
        assertTrue("$after", NextUpKind.GOOD_TIME !in after && NextUpKind.OCCASION in after)
    }

    private companion object {
        const val DAY = 24 * 3_600_000L
        const val TIMEOUT_MS = 15_000L
    }
}
