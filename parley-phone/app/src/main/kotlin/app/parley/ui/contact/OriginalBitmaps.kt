package app.parley.ui.contact

import android.graphics.Bitmap
import android.util.LruCache
import app.parley.data.people.OriginalPhotos

/** Decoded originals at the sizes shown, so returning to a contact page shows its photo at once. */
internal object OriginalBitmaps {
    private const val MAX_BYTES = 24 * 1024 * 1024
    private const val HEAP_SHARE = 8

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Sized to the heap Android gives the app ([heapBytes]): an eighth of it, at most 24 MB. */
    fun fit(heapBytes: Long) = cache.resize((heapBytes / HEAP_SHARE).coerceIn(1L, MAX_BYTES.toLong()).toInt())

    /** Lets go of every decoded original; a page shown again decodes its photo again. */
    fun trim() = cache.evictAll()

    fun peek(o: OriginalPhotos.Original, px: Int): Bitmap? = cache.get("${o.id}@$px")

    suspend fun load(originals: OriginalPhotos, o: OriginalPhotos.Original, px: Int): Bitmap? =
        peek(o, px) ?: originals.decode(o, px)?.also { cache.put("${o.id}@$px", it) }
}
