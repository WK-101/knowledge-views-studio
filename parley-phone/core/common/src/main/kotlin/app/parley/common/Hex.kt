package app.parley.common

/**
 * Lower-case hex through a lookup table. Keyed fingerprints are computed for every call-log row during an archive
 * sync; `"%02x".format` per byte was a measurable share of that.
 */
object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    /** The first [length] bytes of [bytes] (all of them by default) as hex. */
    fun encode(bytes: ByteArray, length: Int = bytes.size): String {
        val n = length.coerceIn(0, bytes.size)
        val out = CharArray(n * 2)
        for (i in 0 until n) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0f]
        }
        return String(out)
    }

    /** [hex] back to bytes; throws on anything that isn't an even run of hex digits. */
    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd length" }
        return ByteArray(hex.length / 2) { i -> ((digit(hex[i * 2]) shl 4) or digit(hex[i * 2 + 1])).toByte() }
    }

    private fun digit(c: Char): Int = Character.digit(c, 16).also { require(it >= 0) { "Not hex" } }
}
