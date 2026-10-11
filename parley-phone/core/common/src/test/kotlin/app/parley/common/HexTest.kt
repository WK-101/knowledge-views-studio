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

    @Test fun decodesWhatItEncodes() {
        val bytes = ByteArray(256) { it.toByte() }
        org.junit.Assert.assertArrayEquals(bytes, Hex.decode(Hex.encode(bytes)))
        org.junit.Assert.assertArrayEquals(byteArrayOf(0xab.toByte()), Hex.decode("AB"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { Hex.decode("abc") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { Hex.decode("zz") }
    }
}
