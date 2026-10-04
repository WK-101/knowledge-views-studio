package app.parley.data.vault

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.extras.TripMatch
import app.parley.common.people.ContactRef
import app.parley.common.security.PinVerdict
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.PostalItem
import app.parley.data.WorkProfile
import app.parley.data.security.Concealment
import app.parley.data.security.LockTransitions
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Private contacts in Parley's lists and scopes: the listing arrives whole (never a part of it first), "Lock private
 * contacts" closes every detail at once until the next unlock, and the city scope ("Who's in…") finds them while they
 * are shown, never while hidden or after a duress unlock.
 */
@RunWith(RobolectricTestRunner::class)
class PrivateLockAndListingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        WorkProfile::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        File(app.noBackupFilesDir, "app_pin").delete()
        File(app.noBackupFilesDir, "app_lock_state").delete()
        Concealment.forgetForTest(newProcess = true)
        VaultCrypto.appContext = app
        VaultCrypto.lockedByPerson = false
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        VaultCrypto.lockedByPerson = false
        Concealment.forgetForTest(newProcess = true)
        File(app.noBackupFilesDir, "app_pin").delete()
        File(app.noBackupFilesDir, "app_lock_state").delete()
        c.scope.cancel()
        c.db.close()
    }

    private suspend fun person(name: String, number: String, city: String = "", note: String = ""): Long = c.vault.save(
        null,
        ContactDetails(
            given = name, note = note, phones = listOf(DataItem(null, number, Phone.TYPE_MOBILE)),
            addresses = if (city.isEmpty()) emptyList() else listOf(PostalItem(city = city, country = "Portugal")),
        ),
    )

    @Test fun the_listing_arrives_whole_and_is_timed() = runBlocking {
        repeat(OPENED) { i -> person("P%03d".format(i), "+1 202 555 %04d".format(i)) }
        c.vault.forgetOpened()
        // A new process: nothing opened yet, every caller-ID copy opened for the first listing.
        val fresh = VaultRepository(app, c.db, c.scope)
        VaultCrypto.Meter.reset()
        val started = System.nanoTime()
        val first = withTimeout(30_000) { fresh.listing.filterNotNull().first() }
        val ms = (System.nanoTime() - started) / 1_000_000
        println("Private listing: $OPENED caller-ID copies opened in $ms ms (${VaultCrypto.Meter.callerOpens.get()} openings)")
        // The first listing is the whole one, in name order: nothing pops in after it.
        assertEquals(OPENED, first.size)
        assertEquals(first.map { it.name }.sorted(), first.map { it.name })
        assertEquals(OPENED, fresh.countNow())
    }

    @Test fun lock_all_closes_every_detail_until_the_next_unlock() = runBlocking {
        val id = person("Ana", "+351 21 000 0001")
        c.vault.forgetOpened()
        assertEquals("Ana", c.vault.details(id)!!.given)
        assertTrue("opening details counts as unlocked", c.vault.unlocked.value)
        val forgets = c.vault.forgets.value
        val locks = c.vault.locks.value

        c.vault.lockAll()
        assertFalse(c.vault.unlocked.value)
        assertTrue(VaultCrypto.detailNeedsUnlock())
        // What was opened is forgotten (the page and the search follow these), and nothing opens from memory.
        assertEquals(forgets + 1, c.vault.forgets.value)
        assertEquals(locks + 1, c.vault.locks.value)
        assertLocked { c.vault.details(id) }
        // Saving waits for the unlock too: nothing is written.
        assertLocked { person("Grace", "+1 202 555 0100") }
        assertEquals(1, c.vault.summariesNow().size)
        // Names and numbers stay readable (the caller-ID copy needs no unlock).
        assertEquals("Ana", c.vault.callerCopy(id)!!.given)

        // The next unlock in Parley ends it.
        c.vault.unlockedByPerson()
        assertFalse(VaultCrypto.detailNeedsUnlock())
        assertTrue(c.vault.unlocked.value)
        assertEquals("Ana", c.vault.details(id)!!.given)
        person("Grace", "+1 202 555 0100")
        assertEquals(2, c.vault.summariesNow().size)
    }

    private suspend fun assertLocked(block: suspend () -> Unit) {
        try {
            block()
            fail("expected the private contacts to be locked")
        } catch (_: VaultCrypto.LockedException) {
        }
    }

    private suspend fun lisbon(): List<Long> = TripMatch.match("Lisbon", c.extras.tripData("pt").people).map { it.person.id }

    @Test fun the_city_scope_finds_private_contacts_while_they_are_shown() = runBlocking {
        val ana = person("Ana", "+1 202 555 0101", city = "Lisbon")
        val rui = person("Rui", "+1 202 555 0102", note = "Moved to Lisbon in May")
        val navs = setOf(ContactRef.Private(ana).navId, ContactRef.Private(rui).navId)
        assertEquals(navs, lisbon().toSet())
        // Their cities are offered too.
        assertTrue("Lisbon" in c.extras.tripData("pt").cities)

        // Hide private contacts: as if there were none.
        c.settings.update { it.copy(hideVault = true) }
        assertTrue(lisbon().isEmpty())
        c.settings.update { it.copy(hideVault = false) }
        assertEquals(navs, lisbon().toSet())

        // Locked: addresses and notes wait for the unlock (only numbers' places could match).
        c.vault.lockAll()
        assertTrue(lisbon().isEmpty())
        c.vault.unlockedByPerson()
        assertEquals(navs, lisbon().toSet())
    }

    @Test fun a_duress_unlock_hides_private_contacts_from_the_city_scope() = runBlocking {
        person("Ana", "+1 202 555 0101", city = "Lisbon")
        assertEquals(1, lisbon().size)
        c.appPin.setPin("246810", duressSession = false)
        c.appPin.setDuress("1357")
        val attempt = c.appPin.check("1357")
        assertEquals(PinVerdict.DURESS, attempt.verdict)
        LockTransitions.pinEntered(c, attempt)
        assertTrue(Concealment.hiding)
        assertTrue(lisbon().isEmpty())
    }

    private companion object {
        const val OPENED = 120
    }
}
