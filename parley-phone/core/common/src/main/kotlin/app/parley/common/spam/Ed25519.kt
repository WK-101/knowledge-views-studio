package app.parley.common.spam

import java.security.SecureRandom
import java.util.Locale
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 (RFC 8032) for `.parleylist` packs and shared templates. The platform's implementation (constant-time)
 * is used where there is one: Android 13+ and the JVM. Older Android versions fall back to the minimal pure-Kotlin
 * one below, which is not constant-time: fine for verifying public data, acceptable for signing your own shared rule
 * packs on those versions.
 */
object Ed25519 {
    // DER prefixes that turn a raw 32-byte key into X.509 / PKCS#8 for the platform's KeyFactory.
    private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    private val PKCS8_PREFIX = byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20)

    /** Whether the platform has Ed25519 (checked once). */
    val platformAvailable: Boolean by lazy {
        runCatching { Signature.getInstance("Ed25519"); KeyFactory.getInstance("Ed25519") }.isSuccess
    }

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
        require(secret.size == 32) { "Ed25519 secret key must be 32 bytes" }
        if (platformAvailable) {
            try {
                val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + secret))
                return Signature.getInstance("Ed25519").run { initSign(key); update(message); sign() }
            } catch (_: GeneralSecurityException) {
                // A provider that lists Ed25519 but can't take this key: the pure implementation gives the same result.
            }
        }
        return signPure(secret, message)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        // A small-order key or R (the identity among them) lets one signature pass for every message: refused on both
        // paths, so the platform and the pure verifier agree.
        if (smallOrder(publicKey) || smallOrder(signature.copyOfRange(0, 32))) return false
        // The pure check stays authoritative for what it rejects (s ≥ L, non-canonical points), so both paths agree.
        if (!platformAvailable) return verifyPure(publicKey, message, signature)
        return try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(X509_PREFIX + publicKey))
            Signature.getInstance("Ed25519").run { initVerify(key); update(message); verify(signature) } &&
                fromLe(signature.copyOfRange(32, 64)) < L
        } catch (_: GeneralSecurityException) {
            verifyPure(publicKey, message, signature)
        }
    }

    internal fun signPure(secret: ByteArray, message: ByteArray): ByteArray {
        val (a, prefix) = expand(secret)
        val pub = compress(mul(a, G))
        val r = fromLe(sha512(prefix, message)).mod(L)
        val rs = compress(mul(r, G))
        val h = fromLe(sha512(rs, pub, message)).mod(L)
        val s = r.add(h.multiply(a)).mod(L)
        return rs + toLe(s)
    }

    internal fun verifyPure(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rs = signature.copyOfRange(0, 32)
        val r = decompress(rs) ?: return false
        if (isSmallOrder(a) || isSmallOrder(r)) return false
        val s = fromLe(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val h = fromLe(sha512(rs, publicKey, message)).mod(L)
        return equal(mul(s, G), add(r, mul(h, a)))
    }

    private val EIGHT: BigInteger = BigInteger.valueOf(8)

    /** Whether 8·P is the identity: P lies in the small subgroup (order 1, 2, 4 or 8). */
    private fun isSmallOrder(p: Point): Boolean = equal(mul(EIGHT, p), IDENTITY)

    /** [encoded] decodes to a small-order point; false for anything that isn't a point (other checks refuse those). */
    internal fun smallOrder(encoded: ByteArray): Boolean = decompress(encoded)?.let(::isSmallOrder) ?: false

    /** A new random secret key (32 bytes). */
    fun newSecret(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /**
     * Readable key fingerprint for the UI: the first 16 bytes of SHA-256 (128 bits, so no second key can be made to
     * match it), in groups of four. Shown only, never stored as an identity: pins compare the whole key.
     */
    fun fingerprint(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey).take(16).joinToString("") { "%02X".format(Locale.ROOT, it) }.chunked(4).joinToString(" ")
}
