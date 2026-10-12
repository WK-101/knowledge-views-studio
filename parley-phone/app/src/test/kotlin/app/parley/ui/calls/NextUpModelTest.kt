package app.parley.ui.calls

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.calls.CallRounds
import app.parley.common.calls.NextUpKind
import app.parley.common.calls.RoundMark
import app.parley.common.calls.RoundPerson
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseMode
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
import org.junit.Assert.assertEquals
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
 * line, and a duress unlock takes the Circle's line away (a relationship insight) while the birthday stays. A private
 * contact's birthday shows while private contacts may show, and goes when they are hidden, locked or under duress.
 * Case files due for a follow-up and an unfinished call round get their lines, and go under duress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NextUpModelTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val number = "+1 202 555 0142"

    @Before fun setUp() {
        VaultCrypto.lockedByPerson = false
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
        VaultCrypto.lockedByPerson = false
        scope.cancel()
        c.scope.cancel()
    }

    private fun model(calls: List<CallEntry>): NextUpModel {
        val flow = MutableStateFlow<List<CallEntry>?>(calls)
        val toCall = ToCallModel(c, scope, flow, MutableStateFlow(emptySet())).also { it.start() }
        return NextUpModel(scope, toCall, c.toCall.state, flow, c.contacts, c.circle, c.extras.chapters, c.privacy.flow, "US", c.vault, c.cases, c.callRound)
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

    private suspend fun privateBirthday(m: NextUpModel, shown: Boolean): Boolean {
        val found = withTimeoutOrNull(TIMEOUT_MS) {
            m.items.first { items -> items.any { it is NextUpItem.Dated && it.person.private } == shown }
        }
        return found != null
    }

    @Test fun a_private_birthday_shows_while_private_contacts_show_and_goes_when_hidden_locked_or_under_duress() = runBlocking {
        val today = LocalDate.now()
        val cleo = c.vault.save(
            null,
            ContactDetails(
                given = "Cleo", phones = listOf(DataItem(null, number, Phone.TYPE_MOBILE)),
                events = listOf(EventItem(date = "1988-%02d-%02d".format(today.monthValue, today.dayOfMonth), type = Event.TYPE_BIRTHDAY)),
            ),
        )
        val m = model(emptyList())
        assertTrue("shown", privateBirthday(m, shown = true))
        val line = m.items.value.filterIsInstance<NextUpItem.Dated>().single { it.person.private }
        // It opens the private page (its navigation id) and calls its number.
        assertEquals(-cleo, line.person.contactId)
        assertEquals("Cleo", line.person.name)

        // Hide private contacts: gone at once.
        c.settings.update { it.copy(hideVault = true) }
        assertTrue("hidden", privateBirthday(m, shown = false))
        c.settings.update { it.copy(hideVault = false) }
        assertTrue("shown again", privateBirthday(m, shown = true))

        // Lock private contacts: gone too, until the next unlock.
        c.vault.lockAll()
        assertTrue("locked", privateBirthday(m, shown = false))
        c.vault.unlockedByPerson()
        assertTrue("unlocked", privateBirthday(m, shown = true))

        // A duress unlock: gone.
        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.DURESS, false))
        assertTrue("duress", privateBirthday(m, shown = false))
    }

    @Test fun the_dates_live_in_the_caller_id_copy_so_they_need_no_unlock() = runBlocking {
        val id = c.vault.save(null, ContactDetails(given = "Dee", events = listOf(EventItem(date = "--03-14", type = Event.TYPE_ANNIVERSARY))))
        // Read from the listing (the caller-ID copy), not the sealed details.
        val dates = c.vault.summary(id)!!.dates
        assertEquals(listOf("--03-14"), dates.map { it.date })
        assertEquals(Event.TYPE_ANNIVERSARY, dates.single().type)
    }

    @Test fun a_case_to_follow_up_and_an_unfinished_round_show_and_duress_hides_both() = runBlocking {
        val today = LocalDate.now().toEpochDay()
        c.cases.update {
            var s = CaseFiles.setMode(it, "Northwind Energy", listOf("+1 202 555 0100"), false, CaseMode.ON, 1, "US", "late")
            s = CaseFiles.setMode(s, "Council", listOf("+1 202 555 0101"), false, CaseMode.ON, 1, "US", "today")
            s = CaseFiles.setMode(s, "Clinic", listOf("+1 202 555 0102"), false, CaseMode.ON, 1, "US", "later")
            s = CaseFiles.setFollowUp(s, "late", today - 2)
            s = CaseFiles.setFollowUp(s, "today", today)
            CaseFiles.setFollowUp(s, "later", today + 3)
        }
        val people = listOf(RoundPerson("a", "Ana", "+1 202 555 0111"), RoundPerson("s", "Sam", "+1 202 555 0112"), RoundPerson("b", "Bo", "+1 202 555 0113"))
        c.callRound.update { CallRounds.mark(CallRounds.start("The party moved to Sunday", people, 1L)!!, "a", RoundMark.REACHED) }
        val m = model(emptyList())
        val shown = withTimeoutOrNull(TIMEOUT_MS) {
            m.items.first { items -> items.count { it is NextUpItem.CaseDue } == 2 && items.any { it is NextUpItem.RoundLeft } }
        } ?: m.items.value
        // The round first, then the most overdue case; three lines at most.
        assertEquals(listOf(NextUpKind.CALL_ROUND, NextUpKind.CASE_DUE, NextUpKind.CASE_DUE), shown.map { it.line.kind })
        val round = shown[0] as NextUpItem.RoundLeft
        assertEquals(Triple(2, 3, "Sam"), Triple(round.left, round.total, round.next))
        val cases = shown.filterIsInstance<NextUpItem.CaseDue>()
        assertEquals(listOf("late" to true, "today" to false), cases.map { it.caseId to it.overdue })

        // A duress unlock: case files and the round (its message is never shown there) go.
        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.DURESS, false))
        val after = kinds(m) { NextUpKind.CASE_DUE !in it && NextUpKind.CALL_ROUND !in it }
        assertTrue("$after", NextUpKind.CASE_DUE !in after && NextUpKind.CALL_ROUND !in after)
    }

    private companion object {
        const val DAY = 24 * 3_600_000L
        const val TIMEOUT_MS = 15_000L
    }
}
