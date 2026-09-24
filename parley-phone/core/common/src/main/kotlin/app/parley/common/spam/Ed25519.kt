package app.parley.common.spam

import java.util.Locale
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Minimal pure-Kotlin Ed25519 (RFC 8032), used to sign and verify `.parleylist` packs on every API level
 * (Android only exposes Ed25519 from API 33). Not constant-time: fine for verifying public data and for
 * signing your own shared rule packs, not for high-value keys.
 */
object Ed25519 {
    // BigInteger.TWO only exists from Android API 33.
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val TWO_D: BigInteger = D.shiftLeft(1).mod(P)

    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val G: Point = run {
        val y = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P)
        val x = recoverX(y, 0) ?: error("bad base point")
        Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun inv(a: BigInteger): BigInteger = a.modPow(P.subtract(TWO), P)

    private fun add(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(q.t).multiply(TWO_D).mod(P)
        val d = p.z.multiply(q.z).shiftLeft(1).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mul(s: BigInteger, p: Point): Point {
        var q = IDENTITY
        var base = p
        var k = s
        while (k.signum() > 0) {
            if (k.testBit(0)) q = add(q, base)
            base = add(base, base)
            k = k.shiftRight(1)
        }
        return q
    }

    private fun equal(p: Point, q: Point): Boolean =
        p.x.multiply(q.z).subtract(q.x.multiply(p.z)).mod(P).signum() == 0 &&
            p.y.multiply(q.z).subtract(q.y.multiply(p.z)).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val y2 = y.multiply(y)
        val x2 = y2.subtract(BigInteger.ONE).multiply(inv(D.multiply(y2).add(BigInteger.ONE))).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P.subtract(x)
        return x
    }

    private fun compress(p: Point): ByteArray {
        val zi = inv(p.z)
        val x = p.x.multiply(zi).mod(P)
        val y = p.y.multiply(zi).mod(P)
        val v = if (x.testBit(0)) y.setBit(255) else y
        return toLe(v)
    }

    private fun decompress(b: ByteArray): Point? {
        if (b.size != 32) return null
        var y = fromLe(b)
        val sign = if (y.testBit(255)) 1 else 0
        y = y.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun toLe(v: BigInteger): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    private fun fromLe(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun expand(secret: ByteArray): Pair<BigInteger, ByteArray> {
        require(secret.size == 32) { "Ed25519 secret key must be 32 bytes" }
        val h = sha512(secret)
        var a = fromLe(h.copyOfRange(0, 32))
        a = a.and(BigInteger.ONE.shiftLeft(254).subtract(BigInteger.valueOf(8))).or(BigInteger.ONE.shiftLeft(254))
        return a to h.copyOfRange(32, 64)
    }

    fun publicKey(secret: ByteArray): ByteArray = compress(mul(expand(secret).first, G))

    fun sign(secret: ByteArray, message: ByteArray): ByteArray {
        val (a, prefix) = expand(secret)
        val pub = compress(mul(a, G))
        val r = fromLe(sha512(prefix, message)).mod(L)
        val rs = compress(mul(r, G))
        val h = fromLe(sha512(rs, pub, message)).mod(L)
        val s = r.add(h.multiply(a)).mod(L)
        return rs + toLe(s)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rs = signature.copyOfRange(0, 32)
        val r = decompress(rs) ?: return false
        val s = fromLe(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val h = fromLe(sha512(rs, publicKey, message)).mod(L)
        return equal(mul(s, G), add(r, mul(h, a)))
    }

    /** A new random secret key (32 bytes). */
    fun newSecret(): ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

    /** Short, readable key fingerprint for the UI: first 8 bytes of SHA-256, grouped. */
    fun fingerprint(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey).take(8).joinToString("") { "%02X".format(Locale.ROOT, it) }.chunked(4).joinToString(" ")
}
