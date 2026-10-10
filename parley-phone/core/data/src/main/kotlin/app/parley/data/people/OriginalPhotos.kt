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
import app.parley.common.photo.FrameMath
import app.parley.common.photo.ImageFiles
import app.parley.common.photo.OriginalPhoto
import app.parley.common.photo.PhotoFrame
import app.parley.common.photo.PhotoMath
import app.parley.common.storage.DurableFiles
import app.parley.data.ContactPhotoProcessor
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.ref.SoftReference
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Contact photos as they were picked: full resolution, their own shape, never cropped. Android keeps only a reduced
 * copy (see [OriginalPhoto]), which stays what other apps see; Parley's contact page and photo viewer show this one.
 *
 * Phone contacts' originals are keyed by lookup key (and follow key changes through [ContactKeys]) in
 * `files/contact_photos`, and go stale when another app replaces the photo ([OriginalPhoto.match]). Private contacts'
 * are sealed with the private-contacts caller key in `files/vault_photo_originals`, keyed by the private entry.
 * Every format Parley names (JPEG, PNG, WebP, GIF, HEIC/HEIF, AVIF) is kept byte for byte, in its own format
 * ([OriginalPhoto.plan]): JPEG, PNG and WebP with their location tags removed (an EXIF rewrite that leaves the picture
 * as it is). A HEIC/HEIF/AVIF with a location is kept with it only when the user said so, and a file over
 * [OriginalPhoto.ASK_ABOVE_BYTES] only when they wanted it whole; otherwise, and for other formats, one high-quality
 * JPEG without location is written. How it was kept ([OriginalPhoto.Kept]) is recorded beside it for the viewer.
 *
 * Beside each original its [PhotoFrame] is kept: the square the avatar was cut from ("Frame photo"), so it can be
 * adjusted again from the whole picture.
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
        /** A sealed original's header-size copy ([PREVIEW_PX]), made the first time it is shown; null for a plain file. */
        internal val preview: File? = null,
        /** The square the avatar was cut from; null: the whole picture (as before framing existed). */
        val frame: PhotoFrame? = null,
        /** How it was kept (as picked, without its location, with it, or as a JPEG); null for one kept before 5.3.1. */
        val kept: OriginalPhoto.Kept? = null,
    ) {
        /** [preview] when it exists and is newer than the original it was made from. */
        internal fun freshPreview(): File? = preview?.takeIf { it.isFile && it.lastModified() >= file.lastModified() }

        /** Identifies this version of the picture (for caches and `remember`). */
        val id: String = file.name + "@" + file.lastModified()

        /**
         * A sealed original, opened once for as long as it is shown (the viewer decodes a region after every pan and
         * zoom; opening the whole file each time made tens of MB of garbage). Memory only, dropped by [release] or
         * under memory pressure.
         */
        @Volatile private var opened: SoftReference<ByteArray>? = null

        /** The region decoder over [opened], kept with it (a plain file's is cheap to open each time). */
        private var decoder: BitmapRegionDecoder? = null

        internal fun bytes(): ByteArray = if (!sealed) {
            file.readBytes()
        } else {
            opened?.get() ?: VaultCrypto.openCallerId(file.readBytes()).also { opened = SoftReference(it) }
        }

        internal fun source(): ImageDecoder.Source = if (sealed) ImageDecoder.createSource(ByteBuffer.wrap(bytes())) else ImageDecoder.createSource(file)

        /** Whether [regionDecoder] hands out a decoder kept for later calls (never recycled by the caller). */
        internal val keepsDecoder: Boolean get() = sealed

        // The (String) and (ByteArray) overloads without the flag are API 31; the flag is ignored anyway.
        @Suppress("DEPRECATION")
        @Synchronized
        internal fun regionDecoder(): BitmapRegionDecoder? {
            if (!sealed) return BitmapRegionDecoder.newInstance(file.path, false)
            decoder?.takeIf { !it.isRecycled }?.let { return it }
            return bytes().let { BitmapRegionDecoder.newInstance(it, 0, it.size, false) }.also { decoder = it }
        }

        /** Drops what [bytes] and [regionDecoder] kept open (the viewer closed). */
        @Synchronized
        internal fun release() {
            decoder?.recycle()
            decoder = null
            opened = null
        }
    }

    /** [o] is no longer shown: what was opened to show it goes. */
    fun release(o: Original) = o.release()

    /**
     * [o] exactly as kept, for Save and Share: the same bytes (a sealed one opened), never decoded or re-encoded, so
     * the file keeps its format and its EXIF as stored. Null when it can't be read.
     */
    @Suppress("TooGenericExceptionCaught") // Keystore or file: nothing to hand out.
    suspend fun exportBytes(o: Original): ByteArray? = withContext(Dispatchers.IO) {
        try {
            o.bytes()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the original to hand it out", e)
            null
        }
    }

    // ---- Phone contacts

    private fun sha(s: String) = Hex.encode(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
    private fun imageFor(key: String) = File(dir, sha(key) + ".img")
    private fun metaFor(key: String) = File(dir, sha(key) + ".json")

    /**
     * Keeps [source] as [lookupKey]'s original. [before]: the contact's Android photo URI before the new photo was
     * written (null for none), so the original can tell Android's new copy from the old one.
     */
    suspend fun keep(
        lookupKey: String,
        source: Uri,
        before: String?,
        answers: OriginalPhoto.Answers = OriginalPhoto.Answers(),
    ): Boolean = withContext(Dispatchers.IO) {
        if (lookupKey.isEmpty()) return@withContext false
        val staged = stage(source, dir, answers) ?: return@withContext false
        val image = imageFor(lookupKey)
        if (!DurableFiles.place(staged.file, image)) return@withContext false
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

    /**
     * Records the square [lookupKey]'s avatar was cut from (null: the whole picture). [rewritten]: the Android photo
     * URI before Parley wrote a new avatar from the kept original ("Adjust framing"), so the new photo is matched to
     * it rather than taken for one another app wrote.
     */
    suspend fun setFrame(lookupKey: String, frame: PhotoFrame?, rewritten: String? = null) = withContext(Dispatchers.IO) {
        if (lookupKey.isEmpty()) return@withContext
        ContactRef.vaultIdOf(lookupKey)?.let { setPrivateFrameNow(it, frame); return@withContext }
        val metaFile = metaFor(lookupKey)
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return@withContext
        putFrame(meta, frame)
        if (rewritten != null) meta.put("before", rewritten).put("bound", "")
        runCatching { metaFile.writeText(meta.toString()) }
        _version.value++
    }

    /** Forgets [lookupKey]'s original. Off the main thread: callers include the editor's save, on the main thread. */
    suspend fun clear(lookupKey: String) = withContext(Dispatchers.IO) { clearNow(lookupKey) }

    private fun clearNow(lookupKey: String) {
        if (lookupKey.isEmpty()) return
        ContactRef.vaultIdOf(lookupKey)?.let { clearPrivateNow(it); return }
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
            clearNow(from)
            return
        }
        val src = imageFor(from)
        if (!src.isFile) return
        if (imageFor(to).isFile) {
            clearNow(from)
            return
        }
        val meta = runCatching { JSONObject(metaFor(from).readText()) }.getOrNull() ?: return
        if (!DurableFiles.move(src, imageFor(to))) return
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

    /**
     * Puts [c] under [key]: sealed for a private contact; for a device contact matched to the next photo it gets. The
     * same size limit as when a picture is first kept ([OriginalPhoto.MAX_BYTES]) holds here too.
     */
    fun put(key: String, c: Carried): Boolean = runCatching {
        if (c.bytes.size > OriginalPhoto.MAX_BYTES) return false
        val size = JSONObject().put("w", c.meta.optInt("w")).put("h", c.meta.optInt("h")).put("o", c.meta.optInt("o", ExifInterface.ORIENTATION_NORMAL))
        // The avatar's square goes along (Make private, Make visible), so it can still be adjusted there; so does how
        // the picture was kept.
        c.meta.optString(FRAME).takeIf { it.isNotEmpty() }?.let { size.put(FRAME, it) }
        c.meta.optString(KEPT).takeIf { it.isNotEmpty() }?.let { size.put(KEPT, it) }
        val id = ContactRef.vaultIdOf(key)
        if (id != null) {
            DurableFiles.writeOrThrow(privateImage(id), VaultCrypto.sealCallerId(c.bytes))
            privateMeta(id).writeText(size.toString())
        } else {
            DurableFiles.writeOrThrow(imageFor(key), c.bytes)
            metaFor(key).writeText(size.put("key", key).put("before", "").put("bound", "").toString())
        }
        _version.value++
        true
    }.getOrDefault(false)

    private fun putFrame(meta: JSONObject, frame: PhotoFrame?) {
        if (frame == null) meta.remove(FRAME) else meta.put(FRAME, frame.encode())
    }

    /** Lookup keys that have an original. */
    fun keys(): Set<String> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { JSONObject(f.readText()).optString("key") }.getOrNull()?.takeIf { it.isNotEmpty() } }
        .filter { imageFor(it).isFile }.toSet()

    internal fun read(lookupKey: String): ByteArray? = imageFor(lookupKey).takeIf { it.isFile }?.readBytes()

    /** Restores an original from a backup (bytes as [read] gave them), matched to the contact whose photo is [current]. */
    internal suspend fun restore(lookupKey: String, bytes: ByteArray, current: String?) = withContext(Dispatchers.IO) {
        // Kept under the same limit as a picture picked here.
        if (bytes.isEmpty() || bytes.size > OriginalPhoto.MAX_BYTES) return@withContext
        dir.mkdirs()
        val tmp = File(dir, "restore.tmp")
        tmp.writeBytes(bytes)
        val staged = inspect(tmp) ?: run { tmp.delete(); return@withContext }
        if (!DurableFiles.place(tmp, imageFor(lookupKey))) return@withContext
        // Bound to the photo the restored contact has: the backup's Android copy of the same picture.
        writeMeta(metaFor(lookupKey), staged, JSONObject().put("key", lookupKey).put("before", "").put("bound", current.orEmpty()))
        _version.value++
    }

    // ---- Private contacts

    private fun privateImage(id: Long) = File(privateDir, "v$id.bin")
    private fun privateMeta(id: Long) = File(privateDir, "v$id.json")

    /** Keeps [source] as private contact [id]'s original, sealed; nothing readable is left behind. */
    @Suppress("TooGenericExceptionCaught") // Keystore and file errors alike: nothing kept.
    suspend fun keepPrivate(id: Long, source: Uri, answers: OriginalPhoto.Answers = OriginalPhoto.Answers()): Boolean = withContext(Dispatchers.IO) {
        val staged = stage(source, privateDir, answers) ?: return@withContext false
        try {
            val sealed = VaultCrypto.sealCallerId(staged.file.readBytes())
            if (!DurableFiles.write(privateImage(id), sealed)) return@withContext false
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

    /** Records the square private contact [id]'s avatar was cut from (null: the whole picture). */
    suspend fun setPrivateFrame(id: Long, frame: PhotoFrame?) = withContext(Dispatchers.IO) { setPrivateFrameNow(id, frame) }

    private fun setPrivateFrameNow(id: Long, frame: PhotoFrame?) {
        val metaFile = privateMeta(id)
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return
        putFrame(meta, frame)
        runCatching { metaFile.writeText(meta.toString()) }
        _version.value++
    }

    /** Forgets private contact [id]'s original, off the main thread (see [clear]). */
    suspend fun clearPrivate(id: Long) = withContext(Dispatchers.IO) { clearPrivateNow(id) }

    private fun clearPrivateNow(id: Long) {
        File(privateDir, "v$id$PREVIEW_SUFFIX").delete()
        val a = privateImage(id).delete()
        val b = privateMeta(id).delete()
        if (a || b) _version.value++
    }

    // ---- Showing

    /** [o] upright and whole, its longer side at most [maxLong] px, or null. */
    @Suppress("TooGenericExceptionCaught") // Decoders throw many kinds; the page then shows Android's photo.
    suspend fun decode(o: Original, maxLong: Int): Bitmap? = withContext(Dispatchers.IO) {
        // A sealed original shown at header size: its small sealed copy, made from the original once.
        if (o.preview != null && maxLong <= PREVIEW_PX) {
            o.freshPreview()?.let { f -> decodePreview(f, maxLong)?.let { return@withContext it } }
            makePreview(o)?.let { pre -> return@withContext if (maxLong >= PREVIEW_PX) pre else scaled(pre, maxLong) }
        }
        decodeWhole(o, maxLong)
    }

    @Suppress("TooGenericExceptionCaught") // Keystore, file or decoder: the original is decoded instead.
    private fun decodePreview(f: File, maxLong: Int): Bitmap? = try {
        val bytes = VaultCrypto.openCallerId(f.readBytes())
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (tw, th) = PhotoMath.fitLongSide(info.size.width, info.size.height, maxLong)
            if (tw != info.size.width || th != info.size.height) decoder.setTargetSize(tw, th)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't show the photo's preview", e)
        null
    }

    /** Decodes [o] at [PREVIEW_PX] and keeps that copy sealed beside it; returns the decoded picture. */
    @Suppress("TooGenericExceptionCaught") // Keystore or file: the picture still shows, the copy is made next time.
    private fun makePreview(o: Original): Bitmap? {
        val pre = decodeWhole(o, PREVIEW_PX) ?: return null
        val target = o.preview ?: return pre
        try {
            val out = java.io.ByteArrayOutputStream()
            pre.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, out)
            DurableFiles.write(target, VaultCrypto.sealCallerId(out.toByteArray()))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't keep the photo's preview", e)
        }
        return pre
    }

    private fun scaled(b: Bitmap, maxLong: Int): Bitmap {
        val (w, h) = PhotoMath.fitLongSide(b.width, b.height, maxLong)
        return if (w == b.width && h == b.height) b else Bitmap.createScaledBitmap(b, w, h, true)
    }

    /** [o] upright and whole, its longer side at most [maxLong] px, or null. */
    @Suppress("TooGenericExceptionCaught") // Decoders throw many kinds; the page then shows Android's photo.
    private fun decodeWhole(o: Original, maxLong: Int): Bitmap? {
        return try {
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
     * The avatar for [o] cut to [frame]: a square JPEG at most [target] px a side, decoded from only that part of the
     * original. Null when it can't be read.
     */
    suspend fun framed(o: Original, frame: PhotoFrame, target: Int = PhotoMath.TARGET): ByteArray? {
        val crop = FrameMath.toCrop(frame, o.width, o.height)
        val bmp = decodeRegion(o, crop, target, target) ?: return null
        return withContext(Dispatchers.IO) { ContactPhotoProcessor.framed(bmp, PhotoFrame(0.0, 0.0, 1.0), target) }
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
                if (!o.keepsDecoder) decoder.recycle()
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

    private class Staged(val file: File, val width: Int, val height: Int, val orientation: Int, val kept: OriginalPhoto.Kept? = null) {
        fun keptAs(k: OriginalPhoto.Kept) = Staged(file, width, height, orientation, k)
    }

    private fun original(meta: JSONObject, image: File, sealed: Boolean): Original? {
        val w = meta.optInt("w")
        val h = meta.optInt("h")
        if (w <= 0 || h <= 0) return null
        val o = meta.optInt("o", ExifInterface.ORIENTATION_NORMAL)
        val (uw, uh) = PhotoMath.exifTransform(o).uprightSize(w, h)
        return Original(
            uw, uh, w, h, o, image, sealed,
            preview = if (sealed) File(image.parentFile, image.nameWithoutExtension + PREVIEW_SUFFIX) else null,
            frame = PhotoFrame.decode(meta.optString(FRAME))?.let { FrameMath.clamp(it, uw, uh) },
            kept = OriginalPhoto.Kept.of(meta.optString(KEPT).ifEmpty { null }),
        )
    }

    private fun writeMeta(file: File, s: Staged, base: JSONObject) {
        base.put("w", s.width).put("h", s.height).put("o", s.orientation)
        s.kept?.let { base.put(KEPT, it.key) }
        file.writeText(base.toString())
    }

    /**
     * What Parley sees of [source] before keeping it: its format from its first bytes, its size, and whether it carries
     * a location. The editor asks its questions from it ([OriginalPhoto.nextQuestion]); off the main thread.
     */
    suspend fun probe(source: Uri): OriginalPhoto.Probe = withContext(Dispatchers.IO) { probeNow(source) }

    @Suppress("TooGenericExceptionCaught") // Any provider failure: nothing known.
    private fun probeNow(source: Uri): OriginalPhoto.Probe {
        val cr = app.contentResolver
        val head = try {
            cr.openInputStream(source)?.use { i -> ByteArray(HEAD_BYTES).let { b -> b.copyOf(readFully(i, b)) } }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the picture's start", e)
            null
        }
        val format = head?.let(ImageFiles::detect)
        val size = runCatching {
            cr.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        }.getOrNull() ?: runCatching { cr.openInputStream(source)?.use { countAtMost(it) } }.getOrNull() ?: -1L
        val location = when (format) {
            null -> null
            ImageFiles.Format.GIF -> false
            else -> runCatching { cr.openInputStream(source)?.use { hasLocation(ExifInterface(it)) } }.getOrNull()
        }
        return OriginalPhoto.Probe(format, size, location)
    }

    /** Copies (or re-encodes) [source] into a temporary file in [into], as [answers] chose; null when it isn't a picture. */
    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod") // Any failure: nothing kept, Android's copy stays.
    private fun stage(source: Uri, into: File, answers: OriginalPhoto.Answers): Staged? {
        into.mkdirs()
        val tmp = File(into, "staging-" + System.nanoTime() + ".tmp")
        val cr = app.contentResolver
        try {
            val probe = probeNow(source)
            val plan = OriginalPhoto.plan(probe, answers)
            if (plan.keep == OriginalPhoto.Keep.COPY) copy(source, tmp, probe, plan)?.let { return it }
            // Too large and not wanted whole, a location not wanted kept, another format, or unreadable as it is: one
            // high-quality, upright JPEG (without the file's metadata, location included).
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (tw, th) = OriginalPhoto.reencodeSize(info.size.width, info.size.height)
                if (tw != info.size.width || th != info.size.height) decoder.setTargetSize(tw, th)
            }
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, OriginalPhoto.REENCODE_QUALITY, it) }
            bmp.recycle()
            return inspect(tmp)?.keptAs(OriginalPhoto.Kept.JPEG) ?: run { tmp.delete(); null }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't keep the original photo", e)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Original photo too large", e)
        }
        tmp.delete()
        return null
    }

    /**
     * [source] copied as it is into [tmp], its location tags removed when [plan] says; null when that can't be done
     * (too large after all, unreadable, or a location that couldn't be removed: never a copy that still has it).
     */
    private fun copy(source: Uri, tmp: File, probe: OriginalPhoto.Probe, plan: OriginalPhoto.Plan): Staged? {
        if (!copyBounded(source, tmp)) return null
        val had = if (plan.stripLocation) locationOf(tmp) == true else probe.hasLocation ?: locationOf(tmp) ?: false
        if (plan.stripLocation && had) stripLocation(tmp)
        val has = if (plan.stripLocation) locationOf(tmp) != false else had
        if (plan.stripLocation && has) return null
        return inspect(tmp)?.keptAs(OriginalPhoto.kept(plan, had, has))
    }

    private fun readFully(i: java.io.InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) {
            val r = i.read(b, n, b.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** The bytes of a stream, counted up to just past [OriginalPhoto.MAX_BYTES]. */
    private fun countAtMost(i: java.io.InputStream): Long {
        var total = 0L
        val buf = ByteArray(64 * 1024)
        while (total <= OriginalPhoto.MAX_BYTES) {
            val n = i.read(buf)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** Whether [f] carries a location; null when its metadata can't be read. */
    private fun locationOf(f: File): Boolean? = runCatching { hasLocation(ExifInterface(f)) }.getOrNull()

    private fun hasLocation(exif: ExifInterface): Boolean = exif.latLong != null || LOCATION_TAGS.any { exif.getAttribute(it) != null }

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

    /**
     * Removes location tags in place without touching the picture (EXIF rewrite only); keeps the orientation. The
     * caller checks the result ([locationOf]): a failure leaves the tags.
     */
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

        /** The avatar's square in an original's record ([PhotoFrame.encode]). */
        private const val FRAME = "frame"

        /** How the original was kept, in its record ([OriginalPhoto.Kept.key]). */
        private const val KEPT = "kept"

        /** The bytes read to tell a picture's format ([ImageFiles.detect] reads an ISO file's brands from its start). */
        private const val HEAD_BYTES = 256

        /**
         * A sealed original's header copy: its longer side in px (the contact page's photo is at most 1.6 × 160 dp).
         * Opening the whole original (tens of MB through the Keystore) for a 128 dp header took seconds.
         */
        internal const val PREVIEW_PX = 1024
        private const val PREVIEW_SUFFIX = ".pre.bin"
        private const val PREVIEW_QUALITY = 88

        private val LOCATION_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_SPEED,
        )

        /** Private entry [id]'s original exactly as stored (still sealed) and its size record, for "Recently deleted". */
        fun sealedPrivate(context: Context, id: Long): Pair<ByteArray, String>? = runCatching {
            val d = File(context.filesDir, "vault_photo_originals")
            val image = File(d, "v$id.bin")
            val meta = File(d, "v$id.json")
            if (image.isFile && meta.isFile) image.readBytes() to meta.readText() else null
        }.getOrNull()

        /** Puts back what [sealedPrivate] gave, under private entry [id] (restored with a new id). */
        fun restoreSealedPrivate(context: Context, id: Long, image: ByteArray, meta: String): Boolean = runCatching {
            val d = File(context.filesDir, "vault_photo_originals").apply { mkdirs() }
            DurableFiles.writeOrThrow(File(d, "v$id.bin"), image)
            File(d, "v$id.json").writeText(meta)
            true
        }.getOrDefault(false)

        /** Deletes private entry [id]'s original (the entry itself was deleted). */
        fun forgetPrivate(context: Context, id: Long) {
            val d = File(context.filesDir, "vault_photo_originals")
            File(d, "v$id.bin").delete()
            File(d, "v$id.json").delete()
            File(d, "v$id$PREVIEW_SUFFIX").delete()
        }
    }
}
