package app.parley.data.people

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.BlockRule
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.calls.SafeWord
import app.parley.common.people.ContactRef
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.WorkProfile
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Deleting a label with Undo: its members (device and private), its ringtone, its policies with the Do Not Disturb
 * stars Parley set for it, its safe word and its rules all come back; and Undo never leaves two labels of one title.
 */
@RunWith(RobolectricTestRunner::class)
class LabelDeleteUndoTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeContactsProvider
    private lateinit var c: DataContainer
    private val phone = AccountRef(null, null)

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        WorkProfile::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        VaultCrypto.appContext = app
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    private suspend fun bob(): Long = c.contacts.save(
        null, ContactDetails(given = "Bob", phones = listOf(DataItem(null, "+1 202 555 0100", Phone.TYPE_MOBILE))), null, null, false,
    )!!.contactId!!

    private suspend fun starred(id: Long) = c.contacts.details(id)!!.starred

    @Test fun undo_puts_back_members_settings_rules_and_dnd_stars() = runBlocking {
        val bob = bob()
        val ada = c.vault.save(null, ContactDetails(given = "Ada", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE))))
        val groupId = c.contacts.createGroup("Team", phone)!!
        val team = c.contacts.groups().first { it.id == groupId }
        c.people.labels.addMembers(listOf(bob, ContactRef.Private(ada).navId), team)
        c.peoplePrefs.update { it.copy(labelRingtones = it.labelRingtones + ("Team" to "content://tones/team")) }
        val ruleId = c.blocks.saveRule(BlockRule(pattern = "Team", type = RuleType.LABEL, kind = RuleKind.ALLOW, label = "Team"))
        val safeWordSet = c.familySafety.setSafeWord("Team", SafeWord("Our dog?", "Rex"))
        // "Allow through Do Not Disturb": Parley stars Bob for the label.
        c.extras.updatePolicy("Team") { it.copy(allowThroughDnd = true, rhythmDays = 30) }
        assertEquals(1, c.extras.starForDnd("Team", c.contacts.loadNow().filter { it.id == bob }))
        assertTrue(starred(bob))

        val (_, deleted) = c.people.labels.deleteForUndo("Team")
        assertNotNull(deleted)
        assertTrue(c.contacts.groups().none { it.title == "Team" })
        assertFalse("the label's star goes with it", starred(bob))
        assertNull(c.extras.policies.value["Team"])
        assertTrue(c.blocks.allRules().none { it.id == ruleId })

        c.people.labels.restore(deleted!!)
        assertEquals(1, c.contacts.groups().count { it.title == "Team" })
        assertEquals(setOf(bob, ContactRef.Private(ada).navId), c.people.labels.members("Team"))
        assertEquals("content://tones/team", c.peoplePrefs.current().labelRingtones["Team"])
        assertEquals(true, c.extras.policies.value["Team"]?.allowThroughDnd)
        assertEquals(30, c.extras.policies.value["Team"]?.rhythmDays)
        assertTrue("starred again, so Do Not Disturb lets Bob through as the switch says", starred(bob))
        assertEquals(setOf("Team"), c.extras.dndStars.value[c.contacts.lookupKeyOf(bob)])
        assertTrue(c.blocks.allRules().any { it.id == ruleId && it.labelKey == "Team" })
        if (safeWordSet) assertEquals("Rex", c.familySafety.safeWords()["Team"]?.answer)

        // Switching the policy off later unstars Bob again: the star is recorded as Parley's.
        c.extras.dndOff("Team")
        assertFalse(starred(bob))
    }

    @Test fun undo_uses_a_label_made_again_meanwhile_and_never_doubles_it() = runBlocking {
        val bob = bob()
        val cy = c.contacts.save(
            null, ContactDetails(given = "Cy", phones = listOf(DataItem(null, "+1 202 555 0199", Phone.TYPE_MOBILE))), null, null, false,
        )!!.contactId!!
        val first = c.contacts.createGroup("Team", phone)!!
        c.people.labels.addMembers(listOf(bob), c.contacts.groups().first { it.id == first })
        assertEquals(setOf(bob), c.people.labels.members("Team"))
        val (_, deleted) = c.people.labels.deleteForUndo("Team")

        // Made again (or synced back) before Undo, with someone else in it.
        val again = c.contacts.createGroup("Team", phone)!!
        c.people.labels.addMembers(listOf(cy), c.contacts.groups().first { it.id == again })

        c.people.labels.restore(deleted!!)
        c.people.labels.restore(deleted)
        assertEquals(listOf(again), c.contacts.groups().filter { it.title == "Team" }.map { it.id })
        assertEquals(setOf(bob, cy), c.people.labels.members("Team"))
        // One membership row each, however often Undo ran.
        assertEquals(2, provider.rows("data", "mimetype = 'vnd.android.cursor.item/group_membership' AND data1 = $again").size)
    }
}
