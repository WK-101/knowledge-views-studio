package app.parley.common

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {
    private fun reference(b: ByteArray) = b.joinToString("") { "%02x".format(Locale.ROOT, it) }

    @Test fun matchesTheFormatBasedEncoding() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(reference(bytes), Hex.encode(bytes))
    }

    @Test fun encodesAPrefix() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals("000f7f", Hex.encode(bytes, 3))
        assertEquals("000f7f80ff", Hex.encode(bytes, 99))
        assertEquals("", Hex.encode(bytes, 0))
    }
}
