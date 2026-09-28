package app.parley.ui.contact

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.people.CallBackgrounds
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.ui.people.BackgroundChange
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The per-contact call-screen picture: a picture chosen in the photo picker is kept, stored under the key the
 * contact has after the save, replaced pictures show (not a cached old one), and a failure says why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallBackgroundPickTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeContactsProvider
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        provider = FakeContactsProvider.install()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        c = DataContainer(context)
    }

    @After fun tearDown() = c.scope.cancel()

    private fun create(given: String, number: String): Long = runBlocking {
        c.contacts.save(null, ContactDetails(given = given, phones = listOf(DataItem(null, number, Phone.TYPE_MOBILE))), null, null, false)!!.contactId
    }

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("Timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun picture(name: String, colour: Int): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }

    private fun editor(contactId: Long, saved: Bundle? = null): EditorViewModel {
        val vm = EditorViewModel(c, if (saved == null) SavedStateHandle() else SavedStateHandle(mapOf("editor" to saved)))
        vm.start(EditorArgs(contactId))
        return vm
    }

    @Test fun aPictureChosenWhileTheEditorWasRestoringIsKept() {
        val ada = create("Ada", "+44 20 7946 0000")
        // The editor's saved state from before the photo picker opened (nothing chosen yet).
        val first = editor(ada)
        until("the contact to load") { first.draft?.phones?.any { it.id != null } == true }
        first.update { d -> d.copy(phones = d.phones.map { it.copy(value = "+44 20 7946 1111") }) }
        val saved = first.toBundle()

        // The process was stopped while the picker was open. Coming back, the picker's answer reaches the screen
        // as soon as it's drawn, while the restored editor is still loading.
        val restored = editor(ada, saved)
        val pick = picture("pick.png", Color.RED)
        restored.changeBackground(BackgroundChange.Set(pick))
        restored.pickPhoto(pick)
        until("the restore") { restored.draft?.phones?.any { it.value == "+44 20 7946 1111" } == true }
        assertEquals("the older saved state must not undo the pick", BackgroundChange.Set(pick), restored.background)
        assertEquals(pick, restored.photo)
    }

    @Test fun theSaveStoresThePictureUnderTheKeyTheContactHasNow() {
        val ada = create("Ada", "+44 20 7946 0000")
        val vm = editor(ada)
        until("the contact to load") { vm.original != null }
        val before = vm.original!!.lookupKey
        vm.changeBackground(BackgroundChange.Set(picture("bg.png", Color.BLUE)))
        // As a rename of a phone-only contact, or a first sync, does: the contact's key changes.
        provider.changeLookupKey(ada, "renamed-key")
        vm.save()
        until("the picture") { c.people.backgrounds.forLookupKey("renamed-key") != null }
        assertNull("not left under the old key", c.people.backgrounds.forLookupKey(before))
    }

    @Test fun aReplacedPictureGetsANewAddress() = runBlocking {
        val bg = c.people.backgrounds
        assertEquals(CallBackgrounds.SetResult.OK, bg.set("k1", picture("a.png", Color.RED)))
        val first = bg.forLookupKey("k1")
        assertNotNull(first)
        // Image caches are keyed by the address, so a new picture must not come back under the old one.
        File(Uri.parse(first).path!!).setLastModified(1_000L)
        assertEquals(CallBackgrounds.SetResult.OK, bg.set("k1", picture("b.png", Color.GREEN)))
        assertNotEquals(bg.forLookupKey("k1"), first)
    }

    @Test fun aPictureThatCantBeReadSaysSo() = runBlocking {
        val bg = c.people.backgrounds
        // A picture the picker's permission no longer covers: a reason, not silence (it used to come back as a bare
        // false, and the contact page's row sent people into the editor with no word of what happened).
        val revoked = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1000000042")
        shadowOf(context.contentResolver).registerInputStreamSupplier(revoked) { throw SecurityException("Permission Denial") }
        assertEquals(CallBackgrounds.SetResult.UNREADABLE, bg.set("k2", revoked))
        assertNull(bg.forLookupKey("k2"))
        assertEquals(CallBackgrounds.SetResult.NOT_SAVED, bg.set("", picture("c.png", Color.BLUE)))
    }
}
