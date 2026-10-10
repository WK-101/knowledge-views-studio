package app.parley.data.backup

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.security.PinVerdict
import app.parley.common.situations.Situations
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.security.RecordCrypto
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * An update from 5.7 (AUDIT GP-3). `golden/stores-5.7.zip` holds the app's files as the 5.7.0 code left them (written by
 * it under Robolectric from the release commit): the database at its 5.7 schema with a private contact and a pinned
 * note, the call-history archive with its wrapped key, the sealed PIN record, the settings, and the test Keystore's keys.
 * The current code opens all of it; then an archived contact and an active Situation are added and survive a restart.
 */
@RunWith(RobolectricTestRunner::class)
class UpgradeFrom57Test {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val containers = ArrayList<DataContainer>()

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        RecordCrypto::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        ZipInputStream(javaClass.getResourceAsStream("/golden/stores-5.7.zip")!!).use { z ->
            generateSequence { z.nextEntry }.forEach { e -> unpack(e.name, z.readBytes()) }
        }
    }

    /** One entry of the zip: a file of the app's data folder, or the Keystore's keys. */
    private fun unpack(name: String, bytes: ByteArray) {
        when {
            name == "keystore.txt" -> loadKeys(bytes.decodeToString())
            name.endsWith(".lck") -> Unit
            else -> File(app.filesDir.parentFile!!, name.removePrefix("data/")).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        }
    }

    /** The test Keystore's keys as 5.7 left them: `alias|algorithm|base64` per line. */
    private fun loadKeys(text: String) = text.lines().filter { it.isNotBlank() }.forEach { line ->
        val (alias, alg, key) = line.split('|')
        FakeAndroidKeyStore.keys[alias] = SecretKeySpec(Base64.getDecoder().decode(key), alg)
    }

    @After fun tearDown() {
        containers.forEach { it.scope.cancel(); it.db.close() }
    }

    private fun start() = DataContainer(app).also { containers += it }

    @Test fun stores_from_5_7_open_and_keep_working() = runBlocking {
        val c = start()
        // The database migrated from its 5.7 schema; the note is still there, sealed or not.
        assertEquals("Prefers mornings", c.meta.meta("golden-ada")?.pinnedNote)
        // The private contact opens with the 5.7 keys.
        val private = c.vault.summariesNow().single()
        assertEquals("Private Person", private.name)
        // The call-history archive opens with its 5.7 key.
        val lines = c.history.backupLines()
        assertEquals(2, lines.count { it.call != null })
        assertEquals(listOf("+44 20 7946 0101"), lines.mapNotNull { it.keepForever })
        // The PIN still opens Parley, and a wrong one doesn't.
        assertEquals(PinVerdict.NORMAL, c.appPin.check("2468").verdict)
        assertEquals(PinVerdict.WRONG, c.appPin.check("1111").verdict)

        // On the new version: an archived contact and a Situation on.
        val grace = c.contacts.save(
            null, ContactDetails(given = "Grace", family = "Hopper", phones = listOf(DataItem(null, "+1 202 555 0100", Phone.TYPE_MOBILE))), null, null, false,
        )!!.contactId!!
        assertNotNull(c.archive.archive(grace))
        assertTrue(c.situations.turnOn(Situations.MEETING))
        // Upkeep seals what older versions kept plain, the archive included.
        c.recordSealing.runIfNeeded()
        c.scope.cancel()
        c.db.close()
        containers.remove(c)

        // The phone restarts.
        val again = start()
        assertEquals(Situations.MEETING, again.situations.state.value.activeId)
        assertEquals("Grace Hopper", again.archive.all().single().name)
        assertEquals("+1 202 555 0100", again.archive.lookup("+1 202 555 0100")?.numbers?.single())
        assertEquals("Private Person", again.vault.summariesNow().single().name)
        assertTrue(again.recordSealing.done)
    }
}
