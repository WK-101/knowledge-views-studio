package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.NewContactAccount
import app.parley.common.record.RawRecord
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.DeviceAccounts
import app.parley.data.records.ContactRecordStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Android 16 refuses new phone-only contacts while the user's default account is a cloud one. The fake provider
 * refuses them the same way, and every insert path (the editor's save, imports and restores) must land in the
 * cloud default instead of failing.
 */
@RunWith(RobolectricTestRunner::class)
class NewContactAccountWriteTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var provider: FakeContactsProvider
    private lateinit var repo: ContactsRepository
    private lateinit var records: ContactRecordStore
    private val google = AccountRef("com.google", "ana@example.org")
    private val dav = AccountRef("at.bitfire.davdroid", "work")

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        repo = ContactsRepository(app, scope)
        repo.beforeChange = { _, _ -> emptyList() }
        records = ContactRecordStore(app)
    }

    @After fun tearDown() {
        DeviceAccounts.newContactsOverride = null
        scope.cancel()
    }

    /** The phone as Android 16 with a cloud default ([cloud]) or with the phone as default (null). */
    private fun android16(cloud: AccountRef?) {
        provider.refusePhoneOnlyInserts = cloud != null
        val default: NewContactAccount.SystemDefault<AccountRef> =
            if (cloud != null) NewContactAccount.SystemDefault(NewContactAccount.State.CLOUD, cloud)
            else NewContactAccount.SystemDefault(NewContactAccount.State.LOCAL, null)
        DeviceAccounts.newContactsOverride = { DeviceAccounts.NewContacts(36, default, DeviceAccounts.localAccount(it)) }
    }

    private val ada = ContactDetails(given = "Ada", family = "Lovelace", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE)))

    private fun rawAccounts() = provider.rows("raw_contacts").map { AccountRef(it["account_type"]?.toString(), it["account_name"]?.toString()) }

    private fun record(account: AccountRef) = ContactRecord(
        key = "k", displayName = "Ada Lovelace",
        raws = listOf(RawRecord(account.type, account.name, rows = listOf(DataRow(Mime.NAME, mapOf(Col.D1 to "Ada Lovelace", Col.D2 to "Ada"))))),
    )

    @Test fun aNewContactWithNoChosenAccountGoesToTheCloudDefaultQuietly() = runBlocking {
        android16(google)
        val saved = repo.save(null, ada, null, null, false)
        assertNotNull(saved)
        assertNull(saved!!.redirectedTo)
        assertEquals(listOf(google), rawAccounts())
    }

    @Test fun aNewContactForThePhoneIsSavedToTheCloudDefaultAndSaysSo() = runBlocking {
        android16(google)
        val saved = repo.save(null, ada, AccountRef(null, null), null, false)
        assertEquals(google, saved!!.redirectedTo)
        assertEquals(listOf(google), rawAccounts())
    }

    @Test fun aChosenCloudAccountIsKept() = runBlocking {
        android16(google)
        val saved = repo.save(null, ada, dav, null, false)
        assertNull(saved!!.redirectedTo)
        assertEquals(listOf(dav), rawAccounts())
    }

    @Test fun withThePhoneAsDefaultNothingChanges() = runBlocking {
        android16(null)
        val saved = repo.save(null, ada, null, null, false)
        assertNull(saved!!.redirectedTo)
        assertEquals(listOf(AccountRef(null, null)), rawAccounts())
    }

    @Test fun importsAndRestoresOfPhoneOnlyContactsGoToTheCloudDefault() {
        android16(google)
        // A restore keeps each copy's own account, a phone-only one included.
        val restored = records.insertAll(listOf(record(AccountRef(null, null))), target = null).single()
        assertNotNull(restored.error, restored.contactId)
        // An import (vCard, CSV, SIM, Add several numbers) into the phone.
        val imported = records.insertAll(listOf(record(AccountRef(null, null))), target = AccountRef(null, null)).single()
        assertNotNull(imported.error, imported.contactId)
        assertEquals(listOf(google, google), rawAccounts())
    }

    @Test fun pickersOfferTheCloudDefaultFirstAndNoPhone() {
        android16(google)
        assertEquals(google, repo.accounts().first())
        assertEquals(emptyList<AccountRef>(), repo.accounts().filter { it.isLocal })
        assertEquals(google, repo.systemDefaultAccount())
    }
}
