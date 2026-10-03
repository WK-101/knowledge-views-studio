package app.parley.ui.contact

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.parley.common.people.ContactRef
import app.parley.common.photo.PhotoFrame
import app.parley.testing.AppTestbed
import kotlinx.coroutines.runBlocking
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.os.ParcelFileDescriptor
import org.robolectric.Robolectric
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * "Frame photo" as it is kept: the picture whole with the frame its avatar was cut from (sealed for a private
 * contact), matched to the avatar "Adjust framing" writes, and carried when a contact is made private or visible;
 * Done without moving the circle changes nothing; and camera shots never outlive the editor in Parley's plain cache.
 * (Cutting the avatar itself needs Android's ImageDecoder, which the test runtime can't run; FrameMath covers its
 * arithmetic in core:common.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PhotoFramingSaveTest {
    private lateinit var t: AppTestbed

    @Before fun setUp() {
        t = AppTestbed()
        Robolectric.buildContentProvider(Pictures::class.java).create(Pictures.AUTHORITY)
    }

    @After fun tearDown() = t.close()

    /** A [w]×[h] PNG in the cache, as a file. */
    private fun pictureFile(w: Int, h: Int, name: String): File {
        val f = File(t.context.cacheDir, name)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 120, 200)) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** A [w]×[h] picture handed over as the photo picker does: a content URI with its type. */
    private fun picture(w: Int, h: Int, name: String = "picked.png"): Uri {
        Pictures.dir = t.context.cacheDir
        pictureFile(w, h, name)
        return Uri.parse("content://${Pictures.AUTHORITY}/$name")
    }

    /** The photo picker's provider, for the pictures of these tests. */
    class Pictures : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(File(dir, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0

        companion object {
            const val AUTHORITY = "parley.test.pictures"
            lateinit var dir: File
        }
    }

    private val frame = PhotoFrame(0.25, 0.0, 0.5)

    @Test fun a_kept_original_stays_whole_and_records_its_frame() = runBlocking {
        val originals = t.c.people.originals
        assertTrue(originals.keep("lk-ada", picture(400, 300), null))
        originals.setFrame("lk-ada", frame)
        val kept = originals.forContact("lk-ada", "content://com.android.contacts/photo/1")!!
        assertEquals("the original stays whole", 400 to 300, kept.width to kept.height)
        assertEquals(frame, kept.frame)
        originals.release(kept)
    }

    @Test fun adjust_framing_matches_the_original_to_the_avatar_parley_writes() = runBlocking {
        val originals = t.c.people.originals
        assertTrue(originals.keep("lk-ada", picture(400, 300), null))
        // Android processed the first photo: the original is bound to it.
        originals.forContact("lk-ada", "content://com.android.contacts/photo/1")!!.let(originals::release)
        // "Adjust framing" wrote a new avatar from the original: Android's next photo is still this picture.
        val moved = PhotoFrame(0.5, 0.0, 0.5)
        originals.setFrame("lk-ada", moved, rewritten = "content://com.android.contacts/photo/1")
        val kept = originals.forContact("lk-ada", "content://com.android.contacts/photo/2")!!
        assertEquals(moved, kept.frame)
        assertEquals(400 to 300, kept.width to kept.height)
        originals.release(kept)
        // A photo another app writes after that is a different picture: the original goes.
        assertNull(originals.forContact("lk-ada", "content://com.android.contacts/photo/3"))
    }

    @Test fun a_framed_private_original_is_sealed_whole_with_its_frame() = runBlocking {
        val originals = t.c.people.originals
        assertTrue(originals.keepPrivate(42, picture(400, 300)))
        originals.setPrivateFrame(42, frame)
        val kept = originals.forPrivate(42)!!
        assertEquals(400 to 300, kept.width to kept.height)
        assertEquals(frame, kept.frame)
        originals.release(kept)
        // Nothing readable is left beside the sealed copy.
        assertTrue(File(t.context.filesDir, "vault_photo_originals").listFiles().orEmpty().none { it.name.endsWith(".tmp") || it.name.startsWith("staging") })
    }

    @Test fun the_frame_goes_along_on_make_private_and_make_visible() = runBlocking {
        val originals = t.c.people.originals
        assertTrue(originals.keep("lk-ada", picture(400, 300), null))
        originals.setFrame("lk-ada", frame)
        originals.move("lk-ada", ContactRef.privateKey(77))
        val sealed = originals.forPrivate(77)!!
        assertEquals(frame, sealed.frame)
        assertEquals(400 to 300, sealed.width to sealed.height)
        originals.release(sealed)
        originals.move(ContactRef.privateKey(77), "lk-ada-2")
        val back = originals.forContact("lk-ada-2", null)!!
        assertEquals(frame, back.frame)
        originals.release(back)
    }

    @Test fun done_without_moving_the_circle_changes_nothing() {
        assertTrue(FramingDone.unchanged(moved = false, start = frame, done = PhotoFrame(0.3, 0.0, 0.5), width = 400, height = 300))
        assertTrue("moved back to the start", FramingDone.unchanged(moved = true, start = frame, done = frame, width = 400, height = 300))
        assertFalse(FramingDone.unchanged(moved = true, start = frame, done = PhotoFrame(0.5, 0.0, 0.5), width = 400, height = 300))
        assertTrue("an unreadable picture", FramingDone.unchanged(moved = true, start = frame, done = null, width = 0, height = 0))
    }

    // ---------------------------------------------------------------- camera shots

    private var shots = 0

    /**
     * A shot as the camera app leaves it: a file in the camera folder and its FileProvider URI (written out here:
     * FileProvider keeps its roots per process, and each test has its own cache folder).
     */
    private fun shot(): Pair<File, Uri> {
        val name = "photo-${++shots}.jpg"
        val f = File(File(t.context.cacheDir, "contact_camera").apply { mkdirs() }, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return f to Uri.parse("content://${t.context.packageName}.files/contact_camera/$name")
    }

    @Test fun forget_deletes_only_camera_shots() {
        val (file, uri) = shot()
        val other = File(t.context.cacheDir, "contact_camera/../elsewhere.jpg").canonicalFile.apply { writeBytes(byteArrayOf(1)) }
        ContactCamera.forget(t.context, Uri.fromFile(other))
        ContactCamera.forget(t.context, Uri.parse("content://media/external/images/1"))
        assertTrue(other.isFile)
        ContactCamera.forget(t.context, uri)
        assertFalse(file.exists())
    }

    @Test fun the_sweep_clears_old_shots_and_unfinished_framed_files_only() {
        val (fresh, _) = shot()
        val (old, _) = shot()
        val now = System.currentTimeMillis()
        old.setLastModified(now - ContactCamera.STALE_MS - 60_000)
        val framedDir = File(t.context.cacheDir, ContactCamera.FRAMED_DIR).apply { mkdirs() }
        val leftOver = File(framedDir, "avatar-1.jpg").apply { writeBytes(byteArrayOf(1)) }
        leftOver.setLastModified(now - ContactCamera.FRAMED_STALE_MS - 60_000)
        val writing = File(framedDir, "avatar-2.jpg").apply { writeBytes(byteArrayOf(1)) }
        ContactCamera.sweep(t.context, now)
        assertTrue(fresh.isFile)
        assertFalse(old.exists())
        assertFalse(leftOver.exists())
        assertTrue("a save still writing it", writing.isFile)
    }

    /** An editor in its own store, so the test can close it as leaving the screen does. */
    private fun editorIn(store: ViewModelStore): EditorViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(EditorViewModel(t.c, SavedStateHandle()))!!
        }
        t.start()
        return ViewModelProvider(store, factory)[EditorViewModel::class.java].also { it.start(EditorArgs(null)) }
    }

    @Test fun a_replaced_or_removed_shot_is_deleted_at_once() {
        val store = ViewModelStore()
        val editor = editorIn(store)
        val (first, firstUri) = shot()
        editor.tookPhoto(firstUri)
        editor.pickPhoto(firstUri, frame)
        val (second, secondUri) = shot()
        editor.tookPhoto(secondUri)
        editor.pickPhoto(secondUri)
        assertFalse("the first shot, replaced", first.exists())
        editor.clearPhoto()
        assertFalse("the second shot, removed", second.exists())
        store.clear()
    }

    @Test fun leaving_the_editor_without_saving_deletes_its_shots() {
        val store = ViewModelStore()
        val editor = editorIn(store)
        val (picked, pickedUri) = shot()
        editor.tookPhoto(pickedUri)
        editor.pickPhoto(pickedUri)
        // Taken, then its framing still open when the editor closes.
        val (framing, framingUri) = shot()
        editor.tookPhoto(framingUri)
        // A photo chosen in the picker isn't Parley's to delete.
        val chosen = pictureFile(10, 10, "chosen.png")
        editor.pickPhoto(Uri.fromFile(chosen))
        store.clear()
        t.until("the shots to go") { !picked.exists() && !framing.exists() }
        assertTrue(chosen.isFile)
    }
}
