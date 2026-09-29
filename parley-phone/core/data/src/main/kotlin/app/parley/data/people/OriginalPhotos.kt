package app.parley.data.people

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import app.parley.common.Hex
import app.parley.common.people.ContactRef
import app.parley.common.photo.OriginalPhoto
import app.parley.common.photo.PhotoMath
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Contact photos as they were picked: full resolution, their own shape, never cropped. Android keeps only a reduced
 * copy (see [OriginalPhoto]), which stays what other apps see; Parley's contact page and photo viewer show this one.
 *
 * Phone contacts' originals are keyed by lookup key (and follow key changes through [ContactKeys]) in
 * `files/contact_photos`, and go stale when another app replaces the photo ([OriginalPhoto.match]). Private contacts'
 * are sealed with the private-contacts caller key in `files/vault_photo_originals`, keyed by the private entry.
 * JPEG, PNG and WebP are kept byte for byte with their location tags removed; other formats (HEIC) and files over
 * [OriginalPhoto.MAX_BYTES] are written once as a high-quality JPEG.
 */
class OriginalPhotos(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "contact_photos")
    private val privateDir = File(app.filesDir, "vault_photo_originals")

    /** Bumped on every change so screens showing an original refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** One kept original. [width]×[height] is its upright size. */
    class Original internal constructor(
        val width: Int,
        val height: Int,
        internal val storedWidth: Int,
        internal val storedHeight: Int,
        internal val orientation: Int,
        private val file: File,
        private val sealed: Boolean,
    ) {
        /** Identifies this version of the picture (for caches and `remember`). */
        val id: String = file.name + "@" + file.lastModified()

        internal fun bytes(): ByteArray = if (sealed) VaultCrypto.openCallerId(file.readBytes()) else file.readBytes()

        internal fun source(): ImageDecoder.Source = if (sealed) ImageDecoder.createSource(ByteBuffer.wrap(bytes())) else ImageDecoder.createSource(file)

        // The (String) and (ByteArray) overloads without the flag are API 31; the flag is ignored anyway.
        @Suppress("DEPRECATION")
        internal fun regionDecoder(): BitmapRegionDecoder? =
            if (sealed) bytes().let { BitmapRegionDecoder.newInstance(it, 0, it.size, false) } else BitmapRegionDecoder.newInstance(file.path, false)
    }

    // ---- Phone contacts

    private fun sha(s: String) = Hex.encode(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
    private fun imageFor(key: String) = File(dir, sha(key) + ".img")
    private fun metaFor(key: String) = File(dir, sha(key) + ".json")

    /**
     * Keeps [source] as [lookupKey]'s original. [before]: the contact's Android photo URI before the new photo was
     * written (null for none), so the original can tell Android's new copy from the old one.
     */
    suspend fun keep(lookupKey: String, source: Uri, before: String?): Boolean = withContext(Dispatchers.IO) {
        if (lookupKey.isEmpty()) return@withContext false
        val staged = stage(source, dir) ?: return@withContext false
        val image = imageFor(lookupKey)
        if (!staged.file.renameTo(image)) {
            staged.file.delete()
            return@withContext false
        }
        writeMeta(metaFor(lookupKey), staged, JSONObject().put("key", lookupKey).put("before", before.orEmpty()).put("bound", ""))
        _version.value++
        true
    }

    /**
     * [lookupKey]'s original, or null. [current]: the contact's Android photo URI now; an original whose Android
     * photo was replaced or removed elsewhere is deleted (it no longer shows the same picture).
     */
    suspend fun forContact(lookupKey: String?, current: String?): Original? = withContext(Dispatchers.IO) {
        if (lookupKey.isNullOrEmpty()) return@withContext null
        // A private contact's Parley key: its sealed original (Parley alone writes its photo, so it can't go stale).
        ContactRef.vaultIdOf(lookupKey)?.let { return@withContext if (current == null) null else forPrivate(it) }
        val metaFile = metaFor(lookupKey)
        val image = imageFor(lookupKey)
        if (!metaFile.isFile || !image.isFile) return@withContext null
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return@withContext null
        when (val m = OriginalPhoto.match(meta.optString("before").ifEmpty { null }, meta.optString("bound"), current)) {
            OriginalPhoto.Match.Stale -> {
                clear(lookupKey)
                null
            }
            is OriginalPhoto.Match.Show -> {
                m.bind?.let { runCatching { metaFile.writeText(meta.put("bound", it).toString()) } }
                original(meta, image, sealed = false)
            }
        }
    }

    fun clear(lookupKey: String) {
        if (lookupKey.isEmpty()) return
        ContactRef.vaultIdOf(lookupKey)?.let { clearPrivate(it); return }
        val a = imageFor(lookupKey).delete()
        val b = metaFor(lookupKey).delete()
        if (a || b) _version.value++
    }

    /**
     * Moves [from]'s original to [to] (a changed lookup key); one [to] already has wins. Between a device contact and a
     * private one ([ContactRef.privateKey], Make private / Make visible) it is sealed or opened on the way.
     */
    @Synchronized
    fun move(from: String, to: String) {
        if (from == to || from.isEmpty() || to.isEmpty()) return
        if (ContactRef.isPrivateKey(from) || ContactRef.isPrivateKey(to)) {
            val carried = take(from) ?: return
            if (take(to) == null) put(to, carried)
            clear(from)
            return
        }
        val src = imageFor(from)
        if (!src.isFile) return
        if (imageFor(to).isFile) {
            clear(from)
            return
        }
        val meta = runCatching { JSONObject(metaFor(from).readText()) }.getOrNull() ?: return
        if (!src.renameTo(imageFor(to))) return
        metaFor(from).delete()
        runCatching { metaFor(to).writeText(meta.put("key", to).toString()) }
        _version.value++
    }

    /** An original taken out of the store (opened when it was sealed), to put back under another key. */
    class Carried internal constructor(internal val bytes: ByteArray, internal val meta: JSONObject)

    /** [key]'s original, readable, or null. */
    fun take(key: String): Carried? = runCatching {
        val id = ContactRef.vaultIdOf(key)
        val (image, meta) = if (id != null) privateImage(id) to privateMeta(id) else imageFor(key) to metaFor(key)
        if (!image.isFile || !meta.isFile) return null
        val bytes = if (id != null) VaultCrypto.openCallerId(image.readBytes()) else image.readBytes()
        Carried(bytes, JSONObject(meta.readText()))
    }.getOrNull()

    /** Puts [c] under [key]: sealed for a private contact; for a device contact matched to the next photo it gets. */
    fun put(key: String, c: Carried): Boolean = runCatching {
        val size = JSONObject().put("w", c.meta.optInt("w")).put("h", c.meta.optInt("h")).put("o", c.meta.optInt("o", ExifInterface.ORIENTATION_NORMAL))
        val id = ContactRef.vaultIdOf(key)
        if (id != null) {
            privateDir.mkdirs()
            val tmp = File(privateDir, "v$id.tmp")
            tmp.writeBytes(VaultCrypto.sealCallerId(c.bytes))
            check(tmp.renameTo(privateImage(id)))
            privateMeta(id).writeText(size.toString())
        } else {
            dir.mkdirs()
            val tmp = File(dir, "carry.tmp")
            tmp.writeBytes(c.bytes)
            check(tmp.renameTo(imageFor(key)))
            metaFor(key).writeText(size.put("key", key).put("before", "").put("bound", "").toString())
        }
        _version.value++
        true
    }.getOrDefault(false)

    /** Lookup keys that have an original. */
    fun keys(): Set<String> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { JSONObject(f.readText()).optString("key") }.getOrNull()?.takeIf { it.isNotEmpty() } }
        .filter { imageFor(it).isFile }.toSet()

    internal fun read(lookupKey: String): ByteArray? = imageFor(lookupKey).takeIf { it.isFile }?.readBytes()

    /** Restores an original from a backup (bytes as [read] gave them), matched to the contact whose photo is [current]. */
    internal suspend fun restore(lookupKey: String, bytes: ByteArray, current: String?) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "restore.tmp")
        tmp.writeBytes(bytes)
        val staged = inspect(tmp) ?: run { tmp.delete(); return@withContext }
        if (!tmp.renameTo(imageFor(lookupKey))) { tmp.delete(); return@withContext }
        // Bound to the photo the restored contact has: the backup's Android copy of the same picture.
        writeMeta(metaFor(lookupKey), staged, JSONObject().put("key", lookupKey).put("before", "").put("bound", current.orEmpty()))
        _version.value++
    }

    // ---- Private contacts

    private fun privateImage(id: Long) = File(privateDir, "v$id.bin")
    private fun privateMeta(id: Long) = File(privateDir, "v$id.json")

    /** Keeps [source] as private contact [id]'s original, sealed; nothing readable is left behind. */
    @Suppress("TooGenericExceptionCaught") // Keystore and file errors alike: nothing kept.
    suspend fun keepPrivate(id: Long, source: Uri): Boolean = withContext(Dispatchers.IO) {
        val staged = stage(source, privateDir) ?: return@withContext false
        try {
            val sealed = VaultCrypto.sealCallerId(staged.file.readBytes())
            val tmp = File(privateDir, "v$id.tmp")
            tmp.writeBytes(sealed)
            if (!tmp.renameTo(privateImage(id))) {
                tmp.delete()
                return@withContext false
            }
            writeMeta(privateMeta(id), staged, JSONObject())
            _version.value++
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't seal the private original", e)
            false
        } finally {
            staged.file.delete()
        }
    }

    suspend fun forPrivate(id: Long): Original? = withContext(Dispatchers.IO) {
        val image = privateImage(id)
        val meta = privateMeta(id)
        if (!image.isFile || !meta.isFile) return@withContext null
        runCatching { JSONObject(meta.readText()) }.getOrNull()?.let { original(it, image, sealed = true) }
    }

    fun clearPrivate(id: Long) {
        val a = privateImage(id).delete()
        val b = privateMeta(id).delete()
        if (a || b) _version.value++
    }

    // ---- Showing

    /** [o] upright and whole, its longer side at most [maxLong] px, or null. */
    @Suppress("TooGenericExceptionCaught") // Decoders throw many kinds; the page then shows Android's photo.
    suspend fun decode(o: Original, maxLong: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            ImageDecoder.decodeBitmap(o.source()) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (tw, th) = PhotoMath.fitLongSide(info.size.width, info.size.height, maxLong)
                if (tw != info.size.width || th != info.size.height) decoder.setTargetSize(tw, th)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't show the original", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Original too large to show", e)
            null
        }
    }

    /**
     * Part of [o] at full detail for the viewer: [region] of the upright picture, decoded with just enough pixels
     * for [viewWidth]×[viewHeight] screen pixels, turned upright. Null when it can't be read.
     */
    @Suppress("TooGenericExceptionCaught") // Decoders throw many kinds; the viewer then keeps the fitted picture.
    suspend fun decodeRegion(o: Original, region: PhotoMath.Crop, viewWidth: Int, viewHeight: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val t = PhotoMath.exifTransform(o.orientation)
            val stored = OriginalPhoto.storedRegion(region, t, o.storedWidth, o.storedHeight)
            val rect = Rect(
                stored.left.coerceAtLeast(0), stored.top.coerceAtLeast(0),
                stored.right.coerceAtMost(o.storedWidth), stored.bottom.coerceAtMost(o.storedHeight),
            )
            if (rect.isEmpty) return@withContext null
            val decoder = o.regionDecoder() ?: return@withContext null
            val sample = OriginalPhoto.sampleFor(region.width, region.height, viewWidth, viewHeight)
            val bmp = try {
                decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                decoder.recycle()
            }
            if (bmp == null || t.isIdentity) return@withContext bmp
            val m = Matrix().apply {
                setRotate(t.degrees.toFloat())
                if (t.flipX) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't decode part of the original", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Region too large", e)
            null
        }
    }

    // ---- Writing

    private class Staged(val file: File, val width: Int, val height: Int, val orientation: Int)

    private fun original(meta: JSONObject, image: File, sealed: Boolean): Original? {
        val w = meta.optInt("w")
        val h = meta.optInt("h")
        if (w <= 0 || h <= 0) return null
        val o = meta.optInt("o", ExifInterface.ORIENTATION_NORMAL)
        val (uw, uh) = PhotoMath.exifTransform(o).uprightSize(w, h)
        return Original(uw, uh, w, h, o, image, sealed)
    }

    private fun writeMeta(file: File, s: Staged, base: JSONObject) {
        file.writeText(base.put("w", s.width).put("h", s.height).put("o", s.orientation).toString())
    }

    /** Copies (or re-encodes) [source] into a temporary file in [into]; null when it isn't a picture. */
    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod") // Any failure: nothing kept, Android's copy stays.
    private fun stage(source: Uri, into: File): Staged? {
        into.mkdirs()
        val tmp = File(into, "staging-" + System.nanoTime() + ".tmp")
        val cr = app.contentResolver
        try {
            val mime = cr.getType(source)
            val size = runCatching {
                cr.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L }
            }.getOrNull() ?: -1L
            val copied = OriginalPhoto.keep(mime, if (size < 0) 1 else size) == OriginalPhoto.Keep.COPY && copyBounded(source, tmp)
            if (copied) {
                stripLocation(tmp)
                inspect(tmp)?.let { return it }
            }
            // Another format, too large, or unreadable as it is: one high-quality, upright JPEG.
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (tw, th) = OriginalPhoto.reencodeSize(info.size.width, info.size.height)
                if (tw != info.size.width || th != info.size.height) decoder.setTargetSize(tw, th)
            }
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, OriginalPhoto.REENCODE_QUALITY, it) }
            bmp.recycle()
            return inspect(tmp) ?: run { tmp.delete(); null }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't keep the original photo", e)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Original photo too large", e)
        }
        tmp.delete()
        return null
    }

    /** Streams [source] into [to]; false (and nothing left) when it is larger than [OriginalPhoto.MAX_BYTES]. */
    private fun copyBounded(source: Uri, to: File): Boolean {
        val input = app.contentResolver.openInputStream(source) ?: throw IOException("No stream for the picture")
        val total = input.use { i -> to.outputStream().use { o -> copyAtMost(i, o) } }
        if (total > OriginalPhoto.MAX_BYTES) to.delete()
        return total in 1..OriginalPhoto.MAX_BYTES
    }

    /** Copies up to just past [OriginalPhoto.MAX_BYTES]; returns the bytes read (more than the limit: too large). */
    private fun copyAtMost(i: java.io.InputStream, o: java.io.OutputStream): Long {
        var total = 0L
        val buf = ByteArray(64 * 1024)
        var n = i.read(buf)
        while (n >= 0 && total <= OriginalPhoto.MAX_BYTES) {
            total += n
            if (total <= OriginalPhoto.MAX_BYTES) o.write(buf, 0, n)
            n = i.read(buf)
        }
        return total
    }

    /** Removes location tags in place without touching the picture (EXIF rewrite only); keeps the orientation. */
    private fun stripLocation(f: File) {
        runCatching {
            val exif = ExifInterface(f)
            var changed = false
            for (tag in LOCATION_TAGS) {
                if (exif.getAttribute(tag) != null) {
                    exif.setAttribute(tag, null)
                    changed = true
                }
            }
            if (changed) exif.saveAttributes()
        }.onFailure { Log.w(TAG, "Couldn't remove location tags", it) }
    }

    /** The stored size and EXIF orientation of [f], or null when it isn't a picture. */
    private fun inspect(f: File): Staged? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val normal = ExifInterface.ORIENTATION_NORMAL
        val o = runCatching { ExifInterface(f).getAttributeInt(ExifInterface.TAG_ORIENTATION, normal) }.getOrDefault(normal)
        return Staged(f, bounds.outWidth, bounds.outHeight, o)
    }

    companion object {
        private const val TAG = "OriginalPhotos"

        private val LOCATION_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_SPEED,
        )

        /** Deletes private entry [id]'s original (the entry itself was deleted). */
        fun forgetPrivate(context: Context, id: Long) {
            val d = File(context.filesDir, "vault_photo_originals")
            File(d, "v$id.bin").delete()
            File(d, "v$id.json").delete()
        }
    }
}
