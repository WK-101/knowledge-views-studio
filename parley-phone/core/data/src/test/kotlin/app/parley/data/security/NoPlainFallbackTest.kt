package app.parley.data.security

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.people.Archive
import app.parley.common.people.ArchivedCard
import app.parley.common.people.ContactRef
import app.parley.common.spam.Ed25519
import app.parley.common.storage.DurableFiles
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import java.io.File
import java.security.KeyStoreException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * What the security model says is sealed is never written plain (SECURITY S3-08): when the Keystore fails, the PIN
 * record and archived contacts are refused or kept in memory, plain copies from older versions are sealed once the key
 * works, and the rule-pack signing key and private contacts' call-screen pictures are sealed too.
 */
@RunWith(RobolectricTestRunner::class)
class NoPlainFallbackTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        // A records key made in this test, not one remembered from an earlier test's process.
        RecordCrypto::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        File(app.noBackupFilesDir, "records.keys").delete()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        File(app.filesDir, "archive").deleteRecursively()
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        FakeAndroidKeyStore.failure = null
        c.scope.cancel()
        c.db.close()
    }

    private fun keystoreDown() {
        FakeAndroidKeyStore.failure = { KeyStoreException("busy") }
    }

    private suspend fun ada(): Long = c.contacts.save(
        null,
        ContactDetails(given = "Ada", family = "Lovelace", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE))),
        null, null, false,
    )!!.contactId!!

    @Test fun archiving_is_refused_rather_than_written_plain() = runBlocking {
        val id = ada()
        keystoreDown()
        assertNull(c.archive.archive(id))
        assertNotNull("Ada stays in the address book", c.contacts.details(id))
        assertTrue("no archive file at all", File(app.filesDir, "archive").listFiles().orEmpty().isEmpty())
        FakeAndroidKeyStore.failure = null
        assertNotNull("archived once the key works", c.archive.archive(id))
    }

    @Test fun a_plain_archive_from_an_older_version_is_sealed() = runBlocking {
        val dir = File(app.filesDir, "archive").apply { mkdirs() }
        val card = ArchivedCard(id = 7, name = "Grace Hopper", numbers = listOf("+15550100"), archivedAt = 1L, accounts = emptyList(), originalKey = "g")
        File(dir, "7.card").writeText(Archive.encode(card))
        File(dir, "7.rec").writeText("{}")
        assertTrue(c.archive.resealPlain())
        val crypto = RecordCrypto.get(app)
        assertTrue(crypto.isSealed(File(dir, "7.card").readBytes()))
        assertTrue(crypto.isSealed(File(dir, "7.rec").readBytes()))
        assertEquals("Grace Hopper", Archive.decode(String(crypto.openBytes(File(dir, "7.card").readBytes())))?.name)
    }

    @Test fun the_pin_record_is_never_written_plain() = runBlocking {
        val file = File(app.noBackupFilesDir, "app_pin")
        file.delete()
        keystoreDown()
        assertFalse("setting a PIN is refused", c.appPin.setPin("2468", duressSession = false))
        assertFalse("and nothing is stored", file.exists())
        FakeAndroidKeyStore.failure = null
        assertTrue(c.appPin.setPin("2468", duressSession = false))
        assertTrue(RecordCrypto.get(app).isSealed(file.readBytes()))
    }

    @Test fun a_plain_pin_record_is_sealed_at_the_next_read() = runBlocking {
        val file = File(app.noBackupFilesDir, "app_pin")
        // As an older version stored it when the Keystore failed: the record's text, plain.
        assertTrue(c.appPin.setPin("1357", duressSession = false))
        val plain = RecordCrypto.get(app).openBytes(file.readBytes())
        file.writeBytes(plain)
        val fresh = AppPinStore(app) { RecordCrypto.get(app) }
        assertTrue(fresh.load().pinSet)
        assertTrue(RecordCrypto.get(app).isSealed(file.readBytes()))
    }

    @Test fun the_rule_pack_signing_key_is_sealed_and_an_old_plain_one_kept() {
        val file = File(app.filesDir, "blocking/share.key").apply { parentFile!!.mkdirs() }
        val old = Ed25519.newSecret()
        file.writeBytes(old)
        val fingerprint = Ed25519.fingerprint(Ed25519.publicKey(old))
        assertEquals("the same key, so family still recognises it", fingerprint, c.lists.shareFingerprint())
        val stored = file.readBytes()
        assertTrue(RecordCrypto.get(app).isSealed(stored))
        assertArrayEquals(old, RecordCrypto.get(app).openBytes(stored))
        assertEquals(fingerprint, c.lists.shareFingerprint())
    }

    @Test fun a_new_signing_key_waits_in_memory_while_the_keystore_is_down() {
        val file = File(app.filesDir, "blocking/share.key")
        file.delete()
        keystoreDown()
        val fingerprint = c.lists.shareFingerprint()
        assertFalse("never stored plain", file.exists())
        FakeAndroidKeyStore.failure = null
        assertEquals(fingerprint, c.lists.shareFingerprint())
        assertTrue(RecordCrypto.get(app).isSealed(file.readBytes()))
    }

    @Test fun a_private_contacts_call_screen_picture_is_sealed() = runBlocking {
        val bg = c.people.backgrounds
        val key = ContactRef.privateKey(5)
        val jpeg = ByteArray(2000) { (it * 31).toByte() }
        bg.write(key, jpeg)
        val dir = File(app.filesDir, "call_backgrounds")
        assertTrue("no plain JPEG", dir.listFiles().orEmpty().none { it.name.endsWith(".jpg") })
        assertTrue(bg.forLookupKey(key)!!.startsWith("content://"))
        assertArrayEquals(jpeg, bg.read(key))
        // A device contact's stays a plain JPEG, as its photo in the address book is.
        bg.write("device-key", jpeg)
        assertTrue(bg.forLookupKey("device-key")!!.startsWith("file:"))

        // One an older version wrote plain is sealed by the re-sealing pass.
        bg.clear(key)
        val hash = bg.hashOf(key)
        File(dir, "$hash.jpg").writeBytes(jpeg)
        bg.remember(key)
        assertTrue(bg.resealPlain())
        assertFalse(File(dir, "$hash.jpg").exists())
        assertArrayEquals(jpeg, bg.read(key))
    }

    /** The old double rename could lose the record when both renames failed; now a failed write keeps the old one. */
    @Test fun a_pin_change_that_cant_be_written_keeps_the_old_record() = runBlocking {
        val file = File(app.noBackupFilesDir, "app_pin")
        assertTrue(c.appPin.setPin("2468", duressSession = false))
        val before = file.readBytes()
        DurableFiles.disk = object : DurableFiles.Disk by DurableFiles.RealDisk {
            override fun rename(from: File, to: File, replace: Boolean) = throw java.io.IOException("rename refused")
        }
        try {
            assertFalse(c.appPin.setLockVaultOnDuress(false))
        } finally {
            DurableFiles.disk = DurableFiles.RealDisk
        }
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(app.noBackupFilesDir, "app_pin.tmp").exists())
        assertTrue(AppPinStore(app) { RecordCrypto.get(app) }.load().pinSet)
    }
}
