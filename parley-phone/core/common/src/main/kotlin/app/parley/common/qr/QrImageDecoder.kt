package app.parley.common.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/**
 * Q1: finds QR codes (and Aztec / Data Matrix / PDF417 as a fallback) in a picture, offline, with ZXing. Pure JVM:
 * the app hands it the pixels of an already downsampled bitmap. It tries, in order, until something is found:
 * the hybrid binarizer, the global-histogram one (better on evenly lit, low-contrast prints), light-on-dark codes
 * (inverted), and the picture turned a quarter. Several codes in one picture are all returned, each once.
 */
object QrImageDecoder {
    data class Found(val text: String, val format: String)

    private val QR_HINTS: Map<DecodeHintType, Any> = mapOf(DecodeHintType.TRY_HARDER to true)
    private val OTHER_HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX, BarcodeFormat.PDF_417),
    )

    /** [argb] is [width]×[height] ARGB_8888 pixels, row by row. */
    fun decode(argb: IntArray, width: Int, height: Int): List<Found> {
        require(argb.size >= width * height) { "Not enough pixels" }
        return decodeLuminance(luminance(argb, width, height), width, height)
    }

    /** [lum] is one byte of brightness per pixel. */
    fun decodeLuminance(lum: ByteArray, width: Int, height: Int): List<Found> {
        val upright = source(lum, width, height)
        val sources = sequence {
            yield(upright)
            yield(InvertedLuminanceSource(upright))
            val turned = rotate(lum, width, height)
            val t = source(turned, height, width)
            yield(t)
            yield(InvertedLuminanceSource(t))
        }
        for (s in sources) {
            qr(s).takeIf { it.isNotEmpty() }?.let { return it }
        }
        // Not a QR code: Aztec (boarding passes), Data Matrix, PDF417 (some business cards and ID cards).
        for (s in listOf(upright, InvertedLuminanceSource(upright))) {
            other(s).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    private fun qr(source: LuminanceSource): List<Found> {
        for (binarizer in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
            val bitmap = BinaryBitmap(binarizer)
            val multi = try {
                QRCodeMultiReader().decodeMultiple(bitmap, QR_HINTS).toList()
            } catch (_: ReaderException) {
                emptyList()
            } catch (_: RuntimeException) {
                emptyList()
            }
            if (multi.isNotEmpty()) return distinct(multi)
        }
        return emptyList()
    }

    private fun other(source: LuminanceSource): List<Found> = try {
        distinct(GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(BinaryBitmap(HybridBinarizer(source)), OTHER_HINTS).toList())
    } catch (_: ReaderException) {
        emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }

    private fun distinct(results: List<Result>): List<Found> =
        results.map { Found(textOf(it), it.barcodeFormat.name) }.filter { it.text.isNotEmpty() }.distinctBy { it.text }

    /**
     * The code's text. ZXing guesses the character set of byte-mode data when the code doesn't say; a guess of
     * ISO-8859-1 or Shift_JIS for bytes that are valid UTF-8 with non-ASCII in them is corrected to UTF-8 (the usual
     * encoding of vCards with accented names).
     */
    internal fun textOf(r: Result): String {
        val text = r.text.orEmpty()
        @Suppress("UNCHECKED_CAST")
        val segments = r.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray> ?: return text
        if (segments.size != 1) return text
        val bytes = segments[0]
        if (bytes.all { it >= 0 }) return text
        // Non-ASCII bytes that happen to form valid UTF-8 are almost never Latin-1 or Shift_JIS text.
        // Only when the whole text came from this one segment read one character per byte (a single-byte guess).
        if (text.length != bytes.size) return text
        return utf8OrNull(bytes) ?: text
    }

    /** [bytes] as UTF-8, or null when they aren't valid UTF-8. */
    internal fun utf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }

    private fun source(lum: ByteArray, w: Int, h: Int): LuminanceSource = PlanarYUVLuminanceSource(lum, w, h, 0, 0, w, h, false)

    /** Brightness (the same weights ZXing's RGB source uses). */
    fun luminance(argb: IntArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        for (i in 0 until width * height) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Transparent pixels (a screenshot's empty areas) count as white.
            val a = (p ushr 24) and 0xFF
            val y = (r + 2 * g + b) / 4
            out[i] = (if (a < 128) 255 else y).toByte()
        }
        return out
    }

    /** A quarter turn clockwise: the result is [height]×[width]. */
    fun rotate(lum: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(lum.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                // (x, y) → (height - 1 - y, x) in a height-wide image.
                out[x * height + (height - 1 - y)] = lum[row + x]
            }
        }
        return out
    }
}
