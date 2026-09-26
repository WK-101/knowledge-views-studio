package app.parley.common.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codes drawn in memory (as a photo or screenshot would hold them) and read back. */
class QrImageDecoderTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** A [width]×[height] white canvas. */
    private class Canvas(val width: Int, val height: Int, fill: Int) {
        val px = IntArray(width * height) { fill }
    }

    private fun draw(c: Canvas, text: String, left: Int, top: Int, size: Int, dark: Int = black, light: Int = white) {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2))
        for (y in 0 until size) for (x in 0 until size) c.px[(top + y) * c.width + left + x] = if (m[x, y]) dark else light
    }

    private fun decode(c: Canvas) = QrImageDecoder.decode(c.px, c.width, c.height).map { it.text }

    @Test fun reads_a_plain_code() {
        val c = Canvas(400, 300, white)
        draw(c, "https://example.com/hello", 60, 40, 220)
        assertEquals(listOf("https://example.com/hello"), decode(c))
    }

    @Test fun reads_utf8_vcards() {
        val card = "BEGIN:VCARD\nVERSION:3.0\nN:Müller;Jürgen\nFN:Jürgen Müller\nTEL:+4915112345678\nEND:VCARD"
        val c = Canvas(500, 500, white)
        draw(c, card, 40, 40, 420)
        assertEquals(listOf(card), decode(c))
    }

    @Test fun reads_light_on_dark_codes() {
        val c = Canvas(300, 300, black)
        draw(c, "inverted", 20, 20, 260, dark = white, light = black)
        assertEquals(listOf("inverted"), decode(c))
    }

    @Test fun reads_several_codes_in_one_picture() {
        val c = Canvas(700, 320, white)
        draw(c, "first code", 20, 40, 240)
        draw(c, "second code", 420, 40, 240)
        assertEquals(setOf("first code", "second code"), decode(c).toSet())
    }

    @Test fun nothing_in_a_blank_picture() {
        assertTrue(decode(Canvas(200, 200, white)).isEmpty())
        // Transparent pixels count as white.
        assertTrue(QrImageDecoder.decode(IntArray(100 * 100), 100, 100).isEmpty())
    }

    @Test fun rotation_moves_pixels_a_quarter_clockwise() {
        // 3×2: row 0 = 1 2 3, row 1 = 4 5 6 → 2×3: 4 1 / 5 2 / 6 3.
        val r = QrImageDecoder.rotate(byteArrayOf(1, 2, 3, 4, 5, 6), 3, 2)
        assertEquals(listOf<Byte>(4, 1, 5, 2, 6, 3), r.toList())
    }

    @Test fun utf8_detection() {
        assertEquals("é", QrImageDecoder.utf8OrNull(byteArrayOf(0xC3.toByte(), 0xA9.toByte())))
        assertEquals(null, QrImageDecoder.utf8OrNull(byteArrayOf(0xE9.toByte())))
    }
}
