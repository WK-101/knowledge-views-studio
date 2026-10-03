package app.parley.ui.common

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.parley.common.photo.ImageFiles
import app.parley.testing.AppTestbed
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Save and Share for pictures: what is handed out is the kept original byte for byte (a private contact's opened from
 * its sealed copy), written as is to the place chosen, and the copies shared from Parley's cache don't outlive their
 * time (a private contact's least of all).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ImageExportTest {
    private lateinit var t: AppTestbed

    @Before fun setUp() {
        t = AppTestbed()
        Robolectric.buildContentProvider(Pictures::class.java).create(Pictures.AUTHORITY)
    }

    @After fun tearDown() = t.close()

    /** A [w]×[h] PNG handed over as the photo picker does (a content URI with its type); returns it and its bytes. */
    private fun picture(w: Int, h: Int, name: String = "picked.png"): Pair<Uri, ByteArray> {
        Pictures.dir = t.context.cacheDir
        val f = File(t.context.cacheDir, name)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 90, 40)) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.parse("content://${Pictures.AUTHORITY}/$name") to f.readBytes()
    }

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
            const val AUTHORITY = "parley.test.export.pictures"
            lateinit var dir: File
        }
    }

    @Test fun a_kept_original_is_handed_out_byte_for_byte() = runBlocking {
        val originals = t.c.people.originals
        val (uri, picked) = picture(300, 200)
        assertTrue(originals.keep("lk-ana", uri, null))
        val kept = originals.forContact("lk-ana", "content://com.android.contacts/photo/1")!!
        val out = originals.exportBytes(kept)!!
        assertArrayEquals("the bytes as picked, not decoded and encoded again", picked, out)
        assertEquals(ImageFiles.Format.PNG, ImageFiles.detect(out))
        originals.release(kept)
    }

    @Test fun a_private_original_is_handed_out_as_picked_from_its_sealed_copy() = runBlocking {
        val originals = t.c.people.originals
        val (uri, picked) = picture(320, 240, "private.png")
        assertTrue(originals.keepPrivate(7, uri))
        val sealed = File(t.context.filesDir, "vault_photo_originals/v7.bin").readBytes()
        assertFalse("kept sealed", sealed.contentEquals(picked))
        val kept = originals.forPrivate(7)!!
        assertArrayEquals(picked, originals.exportBytes(kept))
        originals.release(kept)
    }

    @Test fun save_writes_the_bytes_unchanged_to_the_place_chosen() = runBlocking {
        val (_, picked) = picture(64, 48)
        val target = File(t.context.filesDir, "chosen/Ana Lima.png").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(100_000) { 7 }) }
        assertTrue(ImageExport.save(t.context, Uri.fromFile(target), picked))
        assertArrayEquals("a longer file there before is replaced, not overwritten in part", picked, target.readBytes())
    }

    @Test fun reads_a_picture_behind_a_uri() = runBlocking {
        val (uri, picked) = picture(40, 40, "android.png")
        assertArrayEquals(picked, ImageExport.readUri(t.context, uri.toString()))
        // Without a phone contact to ask for its display photo, the photo's own URI is read.
        assertArrayEquals(picked, ImageExport.readAndroidPhoto(t.context, null, uri.toString()))
        assertEquals(null, ImageExport.readUri(t.context, "content://${Pictures.AUTHORITY}/missing.png"))
    }

    @Test fun a_shared_copy_keeps_the_name_and_the_bytes() = runBlocking {
        val (_, picked) = picture(50, 50)
        val f = ImageExport.writeShareCopy(t.context, picked, "Ana Lima.png", private = false)
        assertEquals("Ana Lima.png", f.name)
        assertArrayEquals(picked, f.readBytes())
        val png = ImageExport.png(Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888))
        assertEquals(ImageFiles.Format.PNG, ImageFiles.detect(png))
    }

    private fun shareUri(f: File) = Uri.parse("content://${t.context.packageName}.files/${ImageExport.DIR}/${f.parentFile!!.name}/${Uri.encode(f.name)}")

    @Test fun forget_deletes_only_a_shared_copy() {
        val f = ImageExport.writeShareCopy(t.context, byteArrayOf(1, 2, 3), "Ana.jpg", private = true)
        val other = File(t.context.cacheDir, "contact_camera/photo-1.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        ImageExport.forget(t.context, Uri.parse("content://${t.context.packageName}.files/contact_camera/photo-1.jpg"))
        ImageExport.forget(t.context, Uri.parse("content://media/external/images/1"))
        ImageExport.forget(t.context, Uri.parse("content://${t.context.packageName}.files/${ImageExport.DIR}/../contact_camera/photo-1.jpg"))
        ImageExport.forget(t.context, Uri.parse("content://${t.context.packageName}.files/${ImageExport.DIR}/../photo-1.jpg"))
        assertTrue(other.isFile)
        assertTrue(f.isFile)
        ImageExport.forget(t.context, shareUri(f))
        assertFalse("the copy and its folder", f.parentFile!!.exists())
    }

    @Test fun the_sweep_clears_copies_whose_time_is_up_private_ones_sooner() {
        val now = System.currentTimeMillis()
        fun copy(private: Boolean, age: Long) = ImageExport.writeShareCopy(t.context, byteArrayOf(1), "x.jpg", private, now - age)
            .also { it.parentFile!!.setLastModified(now - age) }
        val freshPlain = copy(false, 60_000)
        val oldPlain = copy(false, ImageExport.STALE_MS + 60_000)
        val freshPrivate = copy(true, 60_000)
        val oldPrivate = copy(true, ImageExport.PRIVATE_STALE_MS + 60_000)
        ImageExport.sweep(t.context, now)
        assertTrue(freshPlain.isFile)
        assertFalse(oldPlain.exists())
        assertTrue(freshPrivate.isFile)
        assertFalse("a private contact's copy goes after minutes, not an hour", oldPrivate.exists())
        // At start nothing shared before is still being read.
        ImageExport.sweep(t.context, now, all = true)
        assertFalse(freshPlain.exists())
        assertFalse(freshPrivate.exists())
        assertNotNull(File(t.context.cacheDir, ImageExport.DIR).listFiles())
    }

    @Test fun locking_parley_deletes_every_private_copy() {
        val plain = ImageExport.writeShareCopy(t.context, byteArrayOf(1), "a.png", private = false)
        val private = ImageExport.writeShareCopy(t.context, byteArrayOf(2), "b.png", private = true)
        ImageExport.forgetPrivate(t.context)
        assertTrue(plain.isFile)
        assertFalse(private.exists())
    }
}
