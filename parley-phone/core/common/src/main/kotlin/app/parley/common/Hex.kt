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
}
