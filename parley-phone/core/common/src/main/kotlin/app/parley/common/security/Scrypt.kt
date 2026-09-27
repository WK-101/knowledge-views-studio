package app.parley.common.security

import java.security.MessageDigest

/**
 * scrypt (RFC 7914) in plain Kotlin: PBKDF2-HMAC-SHA256 around ROMix with BlockMix over Salsa20/8.
 *
 * Memory-hard: each derivation needs 128·r·N bytes, so guessing a passphrase on a GPU costs far more than with
 * PBKDF2 alone. The JDK and Android ship no scrypt, and a pure implementation keeps the app free of native code.
 */
object Scrypt {
    /** The most memory one derivation may take (128·r·N bytes), whatever the caller passes. */
    const val MAX_MEMORY_BYTES = 256L shl 20

    /** The most PBKDF2 output the p lanes may need (128·r·p bytes). */
    const val MAX_LANE_BYTES = 64L shl 20

    /**
     * Derives [dkLen] bytes from [password] and [salt]. [n] must be a power of two above 1; [r] and [p] at least 1.
     * Callers cap the parameters they accept from files (see the backup's KDF limits); these bounds hold here too, so
     * no parameters can overflow the buffer sizes or ask for more than [MAX_MEMORY_BYTES].
     */
    fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        require(n > 1 && n and (n - 1) == 0) { "N must be a power of two above 1" }
        require(r >= 1 && p >= 1 && dkLen >= 1) { "r, p and the key length must be positive" }
        require(r.toLong() * p < 1L shl 30) { "r·p is too large" }
        require(128L * r * p <= MAX_LANE_BYTES) { "r·p is too large" }
        require(128L * r * n <= MAX_MEMORY_BYTES) { "N·r needs too much memory" }
        require(dkLen <= MAX_LANE_BYTES) { "The key length is too large" }
        val blockBytes = 128 * r
        val b = pbkdf2Sha256(password, salt, 1, p * blockBytes)
        val words = 32 * r
        val x = IntArray(words)
        val v = IntArray(words * n)
        val scratch = IntArray(words)
        for (i in 0 until p) {
            val off = i * blockBytes
            for (k in 0 until words) x[k] = le32(b, off + 4 * k)
            roMix(x, v, scratch, n, r)
            for (k in 0 until words) put32(b, off + 4 * k, x[k])
        }
        v.fill(0)
        val out = pbkdf2Sha256(password, b, 1, dkLen)
        b.fill(0)
        return out
    }

    private fun roMix(x: IntArray, v: IntArray, scratch: IntArray, n: Int, r: Int) {
        val words = 32 * r
        for (i in 0 until n) {
            System.arraycopy(x, 0, v, i * words, words)
            blockMix(x, scratch, r)
        }
        for (i in 0 until n) {
            // Integerify: the first word of the last 64-byte block, modulo N.
            val j = x[(2 * r - 1) * 16] and (n - 1)
            val base = j * words
            for (k in 0 until words) x[k] = x[k] xor v[base + k]
            blockMix(x, scratch, r)
        }
    }

    private val salsaIn = ThreadLocal.withInitial { IntArray(16) }

    private fun blockMix(b: IntArray, y: IntArray, r: Int) {
        val t = salsaIn.get()
        // X = B[2r-1]
        System.arraycopy(b, (2 * r - 1) * 16, t, 0, 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) t[k] = t[k] xor b[i * 16 + k]
            salsa208(t)
            // Even blocks go to the first half of the output, odd blocks to the second.
            val dst = (if (i % 2 == 0) i / 2 else r + i / 2) * 16
            System.arraycopy(t, 0, y, dst, 16)
        }
        System.arraycopy(y, 0, b, 0, 32 * r)
    }

    private fun salsa208(b: IntArray) {
        var x0 = b[0]; var x1 = b[1]; var x2 = b[2]; var x3 = b[3]
        var x4 = b[4]; var x5 = b[5]; var x6 = b[6]; var x7 = b[7]
        var x8 = b[8]; var x9 = b[9]; var x10 = b[10]; var x11 = b[11]
        var x12 = b[12]; var x13 = b[13]; var x14 = b[14]; var x15 = b[15]
        repeat(4) {
            x4 = x4 xor (x0 + x12).rotateLeft(7); x8 = x8 xor (x4 + x0).rotateLeft(9)
            x12 = x12 xor (x8 + x4).rotateLeft(13); x0 = x0 xor (x12 + x8).rotateLeft(18)
            x9 = x9 xor (x5 + x1).rotateLeft(7); x13 = x13 xor (x9 + x5).rotateLeft(9)
            x1 = x1 xor (x13 + x9).rotateLeft(13); x5 = x5 xor (x1 + x13).rotateLeft(18)
            x14 = x14 xor (x10 + x6).rotateLeft(7); x2 = x2 xor (x14 + x10).rotateLeft(9)
            x6 = x6 xor (x2 + x14).rotateLeft(13); x10 = x10 xor (x6 + x2).rotateLeft(18)
            x3 = x3 xor (x15 + x11).rotateLeft(7); x7 = x7 xor (x3 + x15).rotateLeft(9)
            x11 = x11 xor (x7 + x3).rotateLeft(13); x15 = x15 xor (x11 + x7).rotateLeft(18)
            x1 = x1 xor (x0 + x3).rotateLeft(7); x2 = x2 xor (x1 + x0).rotateLeft(9)
            x3 = x3 xor (x2 + x1).rotateLeft(13); x0 = x0 xor (x3 + x2).rotateLeft(18)
            x6 = x6 xor (x5 + x4).rotateLeft(7); x7 = x7 xor (x6 + x5).rotateLeft(9)
            x4 = x4 xor (x7 + x6).rotateLeft(13); x5 = x5 xor (x4 + x7).rotateLeft(18)
            x11 = x11 xor (x10 + x9).rotateLeft(7); x8 = x8 xor (x11 + x10).rotateLeft(9)
            x9 = x9 xor (x8 + x11).rotateLeft(13); x10 = x10 xor (x9 + x8).rotateLeft(18)
            x12 = x12 xor (x15 + x14).rotateLeft(7); x13 = x13 xor (x12 + x15).rotateLeft(9)
            x14 = x14 xor (x13 + x12).rotateLeft(13); x15 = x15 xor (x14 + x13).rotateLeft(18)
        }
        b[0] += x0; b[1] += x1; b[2] += x2; b[3] += x3
        b[4] += x4; b[5] += x5; b[6] += x6; b[7] += x7
        b[8] += x8; b[9] += x9; b[10] += x10; b[11] += x11
        b[12] += x12; b[13] += x13; b[14] += x14; b[15] += x15
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018) over raw bytes. The JCE's PBKDF2 takes chars and, on some providers, rejects
     * an empty password, which scrypt allows.
     */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations >= 1 && dkLen >= 1)
        val mac = HmacSha256(password)
        val out = ByteArray(dkLen)
        var block = 1
        var pos = 0
        while (pos < dkLen) {
            val u = mac.run(salt, byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val t = u.copyOf()
            var prev = u
            for (i in 1 until iterations) {
                prev = mac.run(prev)
                for (k in t.indices) t[k] = (t[k].toInt() xor prev[k].toInt()).toByte()
            }
            val n = minOf(t.size, dkLen - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            block++
        }
        mac.wipe()
        return out
    }

    /** HMAC-SHA256 (RFC 2104) with any key length, including empty. */
    private class HmacSha256(key: ByteArray) {
        private val md = MessageDigest.getInstance("SHA-256")
        private val ipad = ByteArray(64)
        private val opad = ByteArray(64)

        init {
            val k = if (key.size > 64) md.digest(key) else key
            for (i in 0 until 64) {
                val b = if (i < k.size) k[i].toInt() else 0
                ipad[i] = (b xor 0x36).toByte()
                opad[i] = (b xor 0x5c).toByte()
            }
        }

        fun run(vararg parts: ByteArray): ByteArray {
            md.reset()
            md.update(ipad)
            parts.forEach { md.update(it) }
            val inner = md.digest()
            md.update(opad)
            md.update(inner)
            return md.digest()
        }

        fun wipe() {
            ipad.fill(0)
            opad.fill(0)
        }
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte()
    }
}
