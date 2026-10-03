package app.parley.data.screening

import android.Manifest
import android.app.Application
import android.os.UserManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AllowReason
import app.parley.common.AppSettings
import app.parley.common.BlockRule
import app.parley.common.Decision
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.ScreeningSettings
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.ScreenRequest
import app.parley.data.WorkProfile
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.vault.VaultCrypto
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** [app.parley.data.CallScreener] as the call path uses it: real settings, rules, contacts lookup and logging. */
@RunWith(RobolectricTestRunner::class)
class CallScreenerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeContactsProvider
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        WorkProfile::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        c = DataContainer(app)
        // DataStore instances are process-wide, so settings would carry over from the previous test.
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    private fun screening(f: (ScreeningSettings) -> ScreeningSettings) = runBlocking { c.settings.update { it.copy(screening = f(it.screening)) } }

    private fun screen(number: String?, hidden: Boolean = number == null) = runBlocking { c.screener.screenCall(ScreenRequest(number, hidden)) }

    private fun addContact(number: String) = runBlocking {
        c.contacts.save(null, ContactDetails(given = "Grace", phones = listOf(DataItem(null, number, Phone.TYPE_MOBILE))), null, null, false)
    }

    @Test fun everythingRingsWithScreeningOff() {
        assertEquals(Decision.Allow, screen("+1 202 555 0143").decision)
        assertEquals(Decision.Allow, screen(null).decision)
    }

    @Test fun hiddenNumbersAreBlockedOnlyWhenAsked() {
        screening { it.copy(blockHidden = true) }
        assertTrue(screen(null).blocked)
        assertFalse(screen("+1 202 555 0143").blocked)
    }

    @Test fun unknownCallersAreBlockedButContactsRing() {
        screening { it.copy(blockNonContacts = true) }
        addContact("+1 202 555 0100")
        assertTrue(screen("+1 202 555 0143").blocked)
        val contact = screen("+1 202 555 0100")
        assertEquals(Decision.Allow, contact.decision)
        assertEquals(AllowReason.CONTACT, contact.allowedBy)
    }

    @Test fun emergencyNumbersAlwaysRing() {
        screening { it.copy(blockNonContacts = true, blockInvalid = true) }
        runBlocking { c.blocks.saveRule(BlockRule(pattern = "*", type = RuleType.WILDCARD)) }
        for (n in listOf("911", "112")) {
            val r = screen(n)
            assertEquals(n, Decision.Allow, r.decision)
            assertEquals(n, AllowReason.EMERGENCY, r.allowedBy)
        }
    }

    @Test fun whenContactsCantBeCheckedTheCallRings() {
        screening { it.copy(blockNonContacts = true) }
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertFalse("fail open: never block a real contact", screen("+1 202 555 0143").blocked)
    }

    @Test fun aWorkProfileContactCountsAsAContact() {
        screening { it.copy(blockNonContacts = true) }
        provider.workNumbers += "+1 202 555 0177"
        shadowOf(app.getSystemService(UserManager::class.java)).addProfile(0, 10, "Work", 0x20)
        assertFalse(screen("+1 202 555 0177").blocked)
    }

    @Test fun aRuleBlocksAndIsLoggedAndCounted() = runBlocking {
        val id = c.blocks.saveRule(BlockRule(pattern = "+1202555", type = RuleType.PREFIX))
        val r = screen("+1 202 555 0143")
        assertTrue(r.blocked)
        assertEquals(id, (r.decision as Decision.Block).rule?.id)
        // Logging happens after the answer, off the call path.
        withTimeout(5_000) {
            while (c.blocks.screenedSince(0).isEmpty() || c.blocks.allRules().single().hitCount == 0) delay(20)
        }
        assertEquals(1, c.blocks.screenedSince(0).size)
        assertEquals(1, c.blocks.allRules().single().hitCount)
    }

    @Test fun anAllowRuleWinsOverBlockingUnknownCallers() = runBlocking {
        screening { it.copy(blockNonContacts = true) }
        c.blocks.saveRule(BlockRule(pattern = "+12025550143", type = RuleType.EXACT, kind = RuleKind.ALLOW))
        val r = screen("+1 202 555 0143")
        assertEquals(Decision.Allow, r.decision)
        assertEquals(AllowReason.RULE, r.allowedBy)
    }

    @Test fun testCallsDecideWithoutLogging() = runBlocking {
        screening { it.copy(blockHidden = true) }
        assertTrue(c.screener.test(null).blocked)
        delay(200)
        assertTrue(c.blocks.screenedSince(0).isEmpty())
    }

    @Test fun aContactsCallIsLookedUpOnce() {
        screening { it.copy(blockNonContacts = true, ringLoudFavourites = true) }
        addContact("+1 202 555 0100")
        provider.phoneLookups = 0
        val r = screen("+1 202 555 0100")
        assertEquals(AllowReason.CONTACT, r.allowedBy)
        assertEquals("one PhoneLookup gives \"is a contact\", the name, the star and the ringtone", 1, provider.phoneLookups)
    }

    @Test fun aPrivateCallerIsLookedUpInTheVaultOnce() = runBlocking {
        screening { it.copy(blockNonContacts = true) }
        c.vault.save(null, ContactDetails(given = "Private", phones = listOf(DataItem(null, "+1 202 555 0188", Phone.TYPE_MOBILE))))
        // Once to warm the vault's own caches; then count.
        screen("+1 202 555 0188")
        VaultCrypto.Meter.reset()
        provider.phoneLookups = 0
        val r = screen("+1 202 555 0188")
        assertFalse(r.blocked)
        assertEquals(1, VaultCrypto.Meter.hmacs.get())
        assertEquals(1, provider.phoneLookups)
    }

    @Test fun theUnknownCallerLookupsStartedAlongsideDecideOnlyForUnknownCallers() = runBlocking {
        // They start with the contact lookups; a contact's call never waits for or depends on them.
        screening { it.copy(blockNonContacts = true, repeatCallers = true) }
        addContact("+1 202 555 0100")
        assertEquals(AllowReason.CONTACT, screen("+1 202 555 0100").allowedBy)
        assertTrue(screen("+1 202 555 0143").blocked)
    }
}
