package app.parley.common.backup

import java.util.Locale
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Persistent public-key material for scheduled backups. Holds the RSA public key in the clear and the
 * PKCS#8 private key twice: AES-GCM-sealed under [kdf](passphrase, [salt]) and under
 * HKDF(recovery key, [recoverySalt]). Safe to store unprotected and embedded in every archive.
 */
class KeyBundle internal constructor(
    publicKeyBytes: ByteArray,
    val kdf: KdfParams,
    salt: ByteArray,
    passphraseWrap: ByteArray,
    recoveryWrap: ByteArray,
    recoverySalt: ByteArray,
) {
    private val pub = publicKeyBytes.copyOf()
    private val s = salt.copyOf()
    private val pw = passphraseWrap.copyOf()
    private val rw = recoveryWrap.copyOf()
    private val rs = recoverySalt.copyOf()
    val publicKeyBytes: ByteArray get() = pub.copyOf()
    val salt: ByteArray get() = s.copyOf()
    val passphraseWrap: ByteArray get() = pw.copyOf()
    val recoveryWrap: ByteArray get() = rw.copyOf()
    val recoverySalt: ByteArray get() = rs.copyOf()

    val publicKey: PublicKey by lazy {
        try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pub))
        } catch (e: GeneralSecurityException) {
            throw BackupIntegrityException("Corrupt public key", e)
        }
    }

    /** Short fingerprint of the public key, e.g. to show which key a backup was made for. */
    val keyId: String get() = BackupCrypto.sha256(pub).copyOf(8).joinToString("") { "%02x".format(Locale.ROOT, it) }

    /** The KDF's stored parameter (PBKDF2 iterations, or packed scrypt settings). */
    val iterations: Int get() = kdf.param

    fun toBytes(): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).apply {
            // Version 1 (PBKDF2 only) stays byte-identical, so bundles made before scrypt keep their bytes.
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            if (kdf is KdfParams.Pbkdf2) writeByte(1) else { writeByte(2); writeByte(kdf.alg) }
            writeInt(kdf.param); writeByte(s.size); write(s); writeByte(rs.size); write(rs)
            writeShort(pub.size); write(pub); writeShort(pw.size); write(pw); writeShort(rw.size); write(rw)
        }
        return bo.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is KeyBundle && toBytes().contentEquals(other.toBytes())
    override fun hashCode(): Int = toBytes().contentHashCode()

    companion object {
        const val MAGIC = "PARLEYK1"
        private const val MAX_FIELD = 8 * 1024

        /** Parses with bounded sizes and iteration counts. */
        fun fromBytes(bytes: ByteArray): KeyBundle {
            intact(bytes.size <= 32 * 1024) { "Key bundle too large" }
            val bin = ByteArrayInputStream(bytes)
            val d = DataInputStream(bin)
            try {
                val magic = ByteArray(8).also(d::readFully)
                intact(magic.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) { "Not a Parley key bundle" }
                val kdf = when (d.readUnsignedByte()) {
                    1 -> KdfParams.Pbkdf2(d.readInt())
                    2 -> KdfParams.of(d.readUnsignedByte(), d.readInt())
                    else -> throw BackupIntegrityException("Unsupported key bundle version")
                }
                intact(KdfPolicy.BACKUP.accepts(kdf)) { "KDF parameters out of range" }
                fun field(maxLen: Int, lenReader: () -> Int): ByteArray {
                    val l = lenReader()
                    intact(l in 0..minOf(maxLen, bin.available())) { "Bad key bundle field" }
                    return ByteArray(l).also(d::readFully)
                }
                val salt = field(64) { d.readUnsignedByte() }
                val recSalt = field(64) { d.readUnsignedByte() }
                intact(salt.size == BackupCrypto.SALT_SIZE && recSalt.size == BackupCrypto.SALT_SIZE) { "Bad salt" }
                val pub = field(MAX_FIELD) { d.readUnsignedShort() }
                val pw = field(MAX_FIELD) { d.readUnsignedShort() }
                val rw = field(MAX_FIELD) { d.readUnsignedShort() }
                intact(bin.available() == 0) { "Trailing key bundle bytes" }
                val b = KeyBundle(pub, kdf, salt, pw, rw, recSalt)
                val pk = b.publicKey
                intact(pk is RSAPublicKey && pk.modulus.bitLength() >= 2048) { "Unsupported public key" }
                return b
            } catch (e: EOFException) {
                throw BackupIntegrityException("Truncated key bundle", e)
            }
        }
    }
}

/**
 * 160-bit random recovery key, shown as Crockford base32 in groups of four plus a checksum group:
 * `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-CCCC` (8 data groups = 160 bits, checksum = first 20 bits of
 * SHA-256 of the key). Parsing is forgiving: case, spaces/hyphens, O→0 and I/L→1 are accepted.
 */
class RecoveryKey private constructor(private val key: ByteArray) {
    internal fun bytes(): ByteArray = key.copyOf()

    fun format(): String {
        val data = encode(key)
        val check = checksum(key)
        return (data.chunked(4) + check).joinToString("-")
    }

    override fun equals(other: Any?): Boolean = other is RecoveryKey && MessageDigest.isEqual(key, other.key)
    override fun hashCode(): Int = key.contentHashCode()
    override fun toString(): String = "RecoveryKey(****)"

    companion object {
        const val BYTES = 20
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        fun generate(random: SecureRandom = SecureRandom()): RecoveryKey = RecoveryKey(ByteArray(BYTES).also(random::nextBytes))

        fun fromBytes(bytes: ByteArray): RecoveryKey {
            require(bytes.size == BYTES) { "Recovery key must be $BYTES bytes" }
            return RecoveryKey(bytes.copyOf())
        }

        /** Parses user input; throws [IllegalArgumentException] with a user-presentable reason. */
        fun parse(text: String): RecoveryKey {
            val chars = normalize(text)
            require(chars.length == 36) { "A recovery key has 36 characters (9 groups of 4)" }
            val bytes = decode(chars.substring(0, 32))
            require(checksum(bytes) == chars.substring(32)) { "Recovery key checksum does not match – check for typos" }
            return RecoveryKey(bytes)
        }

        fun isValid(text: String): Boolean = try { parse(text); true } catch (_: IllegalArgumentException) { false }

        private fun normalize(text: String): String = buildString {
            for (c in text.uppercase()) {
                when {
                    c == '-' || c.isWhitespace() -> {}
                    c == 'O' -> append('0')
                    c == 'I' || c == 'L' -> append('1')
                    ALPHABET.indexOf(c) >= 0 -> append(c)
                    else -> throw IllegalArgumentException("Invalid character '$c' in recovery key")
                }
            }
        }

        private fun encode(bytes: ByteArray): String {
            val n = BigInteger(1, bytes)
            val bits = bytes.size * 8
            return buildString {
                var shift = bits - 5
                while (shift >= 0) {
                    append(ALPHABET[n.shiftRight(shift).toInt() and 31]); shift -= 5
                }
            }
        }

        private fun decode(chars: String): ByteArray {
            var n = BigInteger.ZERO
            for (c in chars) n = n.shiftLeft(5).or(BigInteger.valueOf(ALPHABET.indexOf(c).toLong()))
            val raw = n.toByteArray()
            val out = ByteArray(BYTES)
            val src = if (raw.size > BYTES) raw.copyOfRange(raw.size - BYTES, raw.size) else raw
            System.arraycopy(src, 0, out, BYTES - src.size, src.size)
            return out
        }

        private fun checksum(bytes: ByteArray): String {
            val h = MessageDigest.getInstance("SHA-256").digest(bytes)
            val v = ((h[0].toInt() and 0xFF) shl 12) or ((h[1].toInt() and 0xFF) shl 4) or ((h[2].toInt() and 0xFF) ushr 4)
            return buildString { for (s in intArrayOf(15, 10, 5, 0)) append(ALPHABET[(v ushr s) and 31]) }
        }
    }
}
