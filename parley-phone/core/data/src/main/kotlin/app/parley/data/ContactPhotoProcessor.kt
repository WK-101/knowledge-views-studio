package app.parley.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import app.parley.common.photo.PhotoMath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * C1 (G2): turns any picture into a contact photo with bounded memory. The size is read first and the image is
 * decoded already reduced (a 50 MP photo never sits in memory at full size), turned upright from its EXIF
 * orientation, cropped to the centre square and written as a [PhotoMath.TARGET] px JPEG.
 *
 * [ImageDecoder] (Android 9+, so always here) reads JPEG, PNG, WebP, GIF and HEIF/HEIC and applies EXIF orientation
 * itself. When it can't read a file, [BitmapFactory] with `inSampleSize` and [ExifInterface] do the same job.
 */
object ContactPhotoProcessor {
    private const val TAG = "PhotoProcessor"

    /** The processed JPEG for the picture at [source], or null when it can't be read as an image. */
    fun process(cr: ContentResolver, source: Uri, target: Int = PhotoMath.TARGET): ByteArray? =
        decode(ImageDecoder.createSource(cr, source), target)
            ?: fallback(target) { cr.openInputStream(source) }

    /** The processed JPEG for encoded image [bytes] (a vCard or backup photo), or null when they aren't an image. */
    fun process(bytes: ByteArray, target: Int = PhotoMath.TARGET): ByteArray? {
        if (bytes.isEmpty()) return null
        return decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), target)
            ?: fallback(target) { ByteArrayInputStream(bytes) }
    }

    private fun decode(source: ImageDecoder.Source, target: Int): ByteArray? = try {
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software pixels: the result is compressed right away, and hardware bitmaps can't be.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val w = info.size.width
            val h = info.size.height
            val (tw, th) = PhotoMath.scaledSize(w, h, target)
            if (tw != w || th != h) decoder.setTargetSize(tw, th)
            // The crop is in the scaled (and already upright) image's coordinates.
            val c = PhotoMath.centerSquare(tw, th)
            if (c.width != tw || c.height != th) decoder.crop = Rect(c.left, c.top, c.right, c.bottom)
        }
        encode(bitmap, target)
    } catch (e: Exception) {
        Log.w(TAG, "ImageDecoder couldn't read the photo", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Photo too large", e)
        null
    }

    /** BitmapFactory path: bounds first, then a sampled decode, EXIF rotation and the square crop. */
    private fun fallback(target: Int, open: () -> InputStream?): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val orientation = open()?.use { runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrNull() }
                ?: ExifInterface.ORIENTATION_NORMAL
            val opts = BitmapFactory.Options().apply { inSampleSize = PhotoMath.sampleSize(bounds.outWidth, bounds.outHeight, target) }
            val sampled = open()?.use { BitmapFactory.decodeStream(it, null, opts) }
            sampled?.let { encode(upright(it, orientation), target) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Photo couldn't be decoded", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Photo too large", e)
        null
    }

    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val t = PhotoMath.exifTransform(orientation)
        if (t.isIdentity) return bitmap
        val m = Matrix().apply {
            setRotate(t.degrees.toFloat())
            if (t.flipX) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** Centre square, scaled to at most [target] px, as JPEG. */
    private fun encode(bitmap: Bitmap, target: Int): ByteArray {
        val c = PhotoMath.centerSquare(bitmap.width, bitmap.height)
        var square = if (c.width == bitmap.width && c.height == bitmap.height) bitmap else Bitmap.createBitmap(bitmap, c.left, c.top, c.width, c.height)
        if (square.width > target) square = Bitmap.createScaledBitmap(square, target, target, true)
        return ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.JPEG, PhotoMath.QUALITY, it) }.toByteArray()
    }
}
