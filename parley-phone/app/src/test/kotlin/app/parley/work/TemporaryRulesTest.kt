package app.parley.work

import android.Manifest
import android.app.Application
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseStatus
import app.parley.common.people.ContactRef
import app.parley.common.people.TemporaryChoice
import app.parley.common.people.TemporaryClock
import app.parley.common.people.TemporaryDue
import app.parley.common.people.TemporaryRule
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.TemporaryContacts
import app.parley.data.people.TemporaryRules
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Temporary contacts that count from the last call or wait for their case file: the upkeep moves their dates on (never
 * back), a call since the question keeps one from being deleted, and the rule survives edits, conversions and backups
 * while "Keep permanently" clears it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TemporaryRulesTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val day = TemporaryChoice.DAY_MS
    private val number = "+44 20 7946 0000"

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        VaultCrypto.appContext = context
        c = DataContainer(context)
    }

    @After fun tearDown() {
        c.scope.cancel()
    }

    private suspend fun privateTemporary(clock: TemporaryClock, savedAt: Long, days: Int = 7): Long =
        TemporaryContacts.save(c, "Plumber", number, days, private = true, now = savedAt, clock = clock)!!.id

    @Test fun a_call_moves_a_private_contacts_date_and_a_missed_one_does_not() = runBlocking {
        val now = System.currentTimeMillis()
        val id = privateTemporary(TemporaryClock.LAST_CALL, savedAt = now - 10 * day)
        assertEquals(TemporaryRule(slideDays = 7), c.vault.summary(id)!!.rule)
        // A missed call (no length) doesn't count: still due.
        c.vault.storePrivateCall(id, number, "Plumber", now - day, 0, CallLog.Calls.MISSED_TYPE)
        TemporaryRules.follow(c, now)
        assertEquals(now - 3 * day, c.vault.summary(id)!!.expiresAt)
        // A connected one yesterday: six days left.
        c.vault.storePrivateCall(id, number, "Plumber", now - day, 120, CallLog.Calls.INCOMING_TYPE)
        TemporaryRules.follow(c, now)
        assertEquals(now + 6 * day, c.vault.summary(id)!!.expiresAt)
        assertFalse(ContactRef.privateKey(id) in DueTemporaries.dueKeys(c, now))
    }

    @Test fun a_call_after_the_question_keeps_it_from_being_deleted() = runBlocking {
        val now = System.currentTimeMillis()
        val id = privateTemporary(TemporaryClock.LAST_CALL, savedAt = now - 10 * day)
        val key = ContactRef.privateKey(id)
        assertTrue(key in DueTemporaries.dueKeys(c, now))
        // They rang between the notification and the tap on Delete.
        c.vault.storePrivateCall(id, number, "Plumber", now - 60_000, 300, CallLog.Calls.INCOMING_TYPE)
        assertEquals(0, DueTemporaries.decide(c, TemporaryDue.Decision.DELETE, setOf(key), now))
        assertNotNull(c.vault.summary(id))
    }

    @Test fun a_case_holds_the_date_until_it_is_resolved() = runBlocking {
        val now = System.currentTimeMillis()
        val region = "GB"
        val state = c.cases.update { CaseFiles.ensure(it, "Northwind", listOf(number), false, now, region, "case-1") }!!
        val case = CaseFiles.find(state, listOf(number), region)!!
        assertTrue(TemporaryRule.offersCase(case))
        val id = privateTemporary(TemporaryClock.CASE_CLOSED, savedAt = now - 10 * day)
        assertEquals(TemporaryRule(untilCase = true), c.vault.summary(id)!!.rule)
        TemporaryRules.follow(c, now)
        val held = c.vault.summary(id)!!.expiresAt!!
        assertTrue(held > now)
        // Resolved: nothing holds it any more; the held date stands and then it is due, asked about as usual.
        c.cases.update { CaseFiles.setStatus(it, case.id, CaseStatus.RESOLVED, now) }
        TemporaryRules.follow(c, now + 3 * day)
        assertEquals(held, c.vault.summary(id)!!.expiresAt)
        assertTrue(ContactRef.privateKey(id) in DueTemporaries.dueKeys(c, now + 3 * day))
    }

    @Test fun a_visible_contacts_date_follows_its_numbers_last_contact() = runBlocking {
        val bob = c.contacts.save(null, ContactDetails(given = "Bob", phones = listOf(DataItem(null, "+1 202 555 0100", Phone.TYPE_MOBILE))), null, null, false)!!
            .contactId!!
        c.temporaries.mark(bob, 7, clock = TemporaryClock.LAST_CALL)
        val key = c.contacts.lookupKeyOf(bob)!!
        val before = c.temporaries.forKey(key)!!
        assertEquals(TemporaryRule(slideDays = 7), before.rule)
        val asked = mutableListOf<String>()
        val lastCall = before.expiresAt + day
        c.temporaries.followRules(before.expiresAt, { n -> asked += n; lastCall }) { TemporaryRule.CaseNow.UNKNOWN }
        assertEquals(listOf("+1 202 555 0100"), asked)
        assertEquals(lastCall + 7 * day, c.temporaries.forKey(key)!!.expiresAt)
        // A fixed one is never touched.
        c.temporaries.mark(bob, 7)
        val fixed = c.temporaries.forKey(key)!!.expiresAt
        c.temporaries.followRules(fixed, { lastCall * 2 }) { TemporaryRule.CaseNow.OPEN }
        assertEquals(fixed, c.temporaries.forKey(key)!!.expiresAt)
        assertEquals(TemporaryRule.FIXED, c.temporaries.forKey(key)!!.rule)
    }

    @Test fun the_rule_survives_an_edit_and_a_new_date_but_not_keep_permanently() = runBlocking {
        val id = privateTemporary(TemporaryClock.LAST_CALL, savedAt = System.currentTimeMillis(), days = 14)
        c.vault.save(id, ContactDetails(given = "Plumber Joe", phones = listOf(DataItem(null, number, Phone.TYPE_MOBILE))))
        assertEquals(TemporaryRule(slideDays = 14), c.vault.summary(id)!!.rule)
        // "Keep 7 more days" gives a date, not a new rule.
        c.vault.setExpiry(id, System.currentTimeMillis() + 7 * day)
        assertEquals(TemporaryRule(slideDays = 14), c.vault.summary(id)!!.rule)
        c.vault.setExpiry(id, null)
        assertEquals(TemporaryRule.FIXED, c.vault.summary(id)!!.rule)
    }

    @Test fun backups_carry_the_rule_and_older_ones_read_as_a_fixed_date() {
        val written = TemporaryRules.put(JSONObject().put("exp", 5L), TemporaryRule(slideDays = 7))
        assertEquals(TemporaryRule(slideDays = 7), TemporaryRules.read(JSONObject(written.toString())))
        assertEquals(TemporaryRule(untilCase = true), TemporaryRules.read(TemporaryRules.put(JSONObject(), TemporaryRule(untilCase = true))))
        // What older versions read is unchanged, and they ignore the rest.
        assertEquals(5L, written.getLong("exp"))
        // A fixed date adds nothing: such a backup is the same as before.
        assertEquals(0, TemporaryRules.put(JSONObject(), TemporaryRule.FIXED).length())
        // A backup from before, or a value that makes no sense: a fixed date.
        assertEquals(TemporaryRule.FIXED, TemporaryRules.read(JSONObject("""{"exp":5,"purge":true,"asked":false}""")))
        assertEquals(TemporaryRule.FIXED, TemporaryRules.read(JSONObject("""{"slide":"soon","case":"maybe"}""")))
        assertEquals(TemporaryRule.FIXED, TemporaryRules.read(JSONObject("""{"slide":-4}""")))
    }
}
