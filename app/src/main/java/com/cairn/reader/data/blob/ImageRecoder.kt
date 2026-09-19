package com.cairn.reader.data.blob

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Recompresses downloaded article images to WebP and caps their pixel dimensions, so a permanent
 * offline copy stores far fewer bytes than the site's originals (often multi-megabyte hero JPEG/PNGs)
 * while staying sharp at phone reading sizes. Vector (SVG) and animated (GIF) images are left
 * byte-for-byte; anything that fails to decode — or that wouldn't actually get smaller — is kept as
 * received. Entirely on-device (android.graphics), no network, no library.
 */
internal object ImageRecoder {

    private const val MAX_DIM = 2048        // px on the longest side; ample for a phone reader
    private const val WEBP_QUALITY = 80     // visually ~lossless for article photos, big size win

    /** The (bytes, fileExtension) to actually store: either a smaller WebP re-encode, or the input. */
    fun recode(bytes: ByteArray, contentType: String?, url: String): Pair<ByteArray, String> {
        val ext = extensionOf(contentType, url)
        if (ext == "svg" || ext == "gif") return bytes to ext // don't rasterize vectors / flatten anims
        if (bytes.size < 8 * 1024) return bytes to ext          // tiny icons: not worth re-encoding

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return bytes to ext

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight) }
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
            ?: return bytes to ext
        val scaled = downscale(decoded)

        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        val ok = runCatching { scaled.compress(fmt, WEBP_QUALITY, out) }.getOrDefault(false)
        if (scaled !== decoded) runCatching { scaled.recycle() }
        runCatching { decoded.recycle() }
        if (!ok) return bytes to ext

        val webp = out.toByteArray()
        return if (webp.isNotEmpty() && webp.size < bytes.size) webp to "webp" else bytes to ext
    }

    private fun downscale(bmp: Bitmap): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= MAX_DIM) return bmp
        val ratio = MAX_DIM.toFloat() / longest
        val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(bmp, w, h, true) }.getOrDefault(bmp)
    }

    /** Coarse power-of-two subsample so a huge source never fully decodes into memory. */
    private fun sampleFor(w: Int, h: Int): Int {
        var sample = 1
        val longest = maxOf(w, h)
        while (longest / sample > MAX_DIM * 2) sample *= 2
        return sample
    }

    private fun extensionOf(contentType: String?, url: String): String {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            "svg" in ct -> "svg"
            "gif" in ct -> "gif"
            "png" in ct -> "png"
            "webp" in ct -> "webp"
            "jpeg" in ct || "jpg" in ct -> "jpg"
            "avif" in ct -> "avif"
            "bmp" in ct -> "bmp"
            else -> when (val u = url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
                "jpeg" -> "jpg"
                "svg", "gif", "png", "webp", "jpg", "avif", "bmp" -> u
                else -> "jpg"
            }
        }
    }
}
