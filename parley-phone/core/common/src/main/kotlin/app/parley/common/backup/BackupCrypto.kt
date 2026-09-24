package app.parley.common.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/*
 * Parley Backup envelope ("PARLEYB1"), JCE only.
 *
 * File layout (all integers big-endian):
 *
 *   header := magic "PARLEYB1" | u8 version | u32 bodyLen | body
 *   body   := u8 kdfAlg(1 = PBKDF2-HMAC-SHA256) | u32 iterations | u8 saltLen(16) | salt
 *             | u32 segmentSize | noncePrefix[7] | u8 wrapCount | wrap*
 *   wrap   := u8 type | u32 len | payload
 *   payload:
 *     PASSPHRASE  nonce[12] | AES-GCM(KEK = PBKDF2(passphrase, salt, iterations), DEK)
 *     RECOVERY    nonce[12] | AES-GCM(KEK = HKDF-SHA256(recoveryKey, salt), DEK)
 *     PUBLIC_KEY  u32 bundleLen | KeyBundle bytes | u32 rsaLen | RSA-OAEP-SHA256(publicKey, DEK)
 *
 *   payload := segment*   (STREAM construction, Hoang-Reyhanitabar-Rogaway-Vizár)
 *   segment_i := AES-256-GCM(DEK, nonce = noncePrefix[7] | u32 i | u8 last, aad = header bytes, chunk_i)
 *
 * Every plaintext chunk is exactly segmentSize bytes except the last (0..segmentSize bytes); an empty
 * payload is one empty last segment. The last-segment flag and the counter in the nonce make truncation,
 * reordering and extension fail authentication. The header is AAD of every segment, so any header byte
 * that a key wrap does not itself cover (KDF params, nonce prefix, other wraps) is authenticated too.
 *
 * A PUBLIC_KEY wrap embeds the KeyBundle (public key + private key encrypted under the passphrase and
 * under the recovery key), so scheduled backups need only the public key on the phone, yet can be
 * restored on a new device with the passphrase or the recovery key alone.
 */

/** Malformed, truncated or tampered backup data. */
open class BackupIntegrityException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** None of the archive's key wraps opens with the supplied secret (wrong passphrase / key). */
class WrongKeyException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** What an archive is encrypted to. Any one of them can later decrypt it. */
sealed interface Recipient {
    /** DEK wrapped under PBKDF2(passphrase). The caller may wipe the array after [BackupCrypto.encrypt]. */
    class Passphrase(val passphrase: CharArray) : Recipient
    class Recovery(val key: RecoveryKey) : Recipient
    /** Public-key wrap: needs no secret at backup time. */
    class PublicKey(val bundle: KeyBundle) : Recipient
}

/** A secret that opens an archive. */
sealed interface Unlock {
    /** Opens PASSPHRASE wraps and, via the embedded key bundle, PUBLIC_KEY wraps. */
    class Passphrase(val passphrase: CharArray) : Unlock
    /** Opens RECOVERY wraps and, via the embedded key bundle, PUBLIC_KEY wraps. */
    class Recovery(val key: RecoveryKey) : Unlock
    /** Opens PUBLIC_KEY wraps directly with an already-unlocked private key. */
    class WithPrivateKey(val key: PrivateKey) : Unlock
}

enum class WrapType(val id: Int) {
    PASSPHRASE(1), RECOVERY(2), PUBLIC_KEY(3);

    companion object {
        fun of(id: Int): WrapType =
            entries.firstOrNull { it.id == id } ?: throw BackupIntegrityException("Unknown key wrap type $id")
    }
}

class KeyWrap(val type: WrapType, payload: ByteArray) {
    private val data = payload.copyOf()
    val payload: ByteArray get() = data.copyOf()
}

/** Parsed envelope header. [bytes] is the exact serialized header (the AAD of every segment). */
class EnvelopeHeader internal constructor(
    val version: Int,
    val iterations: Int,
    salt: ByteArray,
    val segmentSize: Int,
    noncePrefix: ByteArray,
    val wraps: List<KeyWrap>,
    bytes: ByteArray,
) {
    private val saltBytes = salt.copyOf()
    private val prefix = noncePrefix.copyOf()
    private val raw = bytes.copyOf()
    val salt: ByteArray get() = saltBytes.copyOf()
    val noncePrefix: ByteArray get() = prefix.copyOf()
    val bytes: ByteArray get() = raw.copyOf()
    internal val aad: ByteArray get() = raw

    val wrapTypes: Set<WrapType> get() = wraps.map { it.type }.toSet()

    /** The key bundle embedded in a PUBLIC_KEY wrap, if any (to show "encrypted to key XYZ"). */
    val keyBundle: KeyBundle?
        get() = wraps.firstOrNull { it.type == WrapType.PUBLIC_KEY }?.let { BackupCrypto.splitPublicWrap(it.payload).first }
}

object BackupCrypto {
    const val MAGIC = "PARLEYB1"
    const val VERSION = 1
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ITERATIONS = 1_000
    const val MAX_ITERATIONS = 10_000_000
    const val SEGMENT_SIZE = 64 * 1024
    const val SALT_SIZE = 16
    const val NONCE_PREFIX_SIZE = 7
    const val TAG_SIZE = 16
    const val RSA_BITS = 3072

    internal const val KDF_PBKDF2_SHA256 = 1
    internal const val MAX_HEADER_BODY = 64 * 1024
    internal const val MAX_WRAPS = 16
    private const val GCM_NONCE = 12
    private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.US_ASCII)

    // ---------------------------------------------------------------- encryption

    /**
     * Starts an encrypted archive on [out]: writes the header and returns a stream that encrypts
     * everything written to it. Closing the returned stream finishes the last segment and closes [out];
     * [EncryptingOutputStream.finish] finishes without closing.
     *
     * [iterations] is the PBKDF2 cost for [Recipient.Passphrase] wraps (tests use [MIN_ITERATIONS]).
     */
    fun encrypt(
        out: OutputStream,
        recipients: List<Recipient>,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): EncryptingOutputStream {
        require(recipients.isNotEmpty()) { "At least one recipient is required" }
        require(recipients.size <= MAX_WRAPS) { "Too many recipients" }
        checkIterations(iterations)
        val dek = ByteArray(32).also(random::nextBytes)
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val prefix = ByteArray(NONCE_PREFIX_SIZE).also(random::nextBytes)
        try {
            val wraps = recipients.map { r ->
                when (r) {
                    is Recipient.Passphrase -> {
                        val kek = pbkdf2(r.passphrase, salt, iterations)
                        KeyWrap(WrapType.PASSPHRASE, gcmSeal(kek, dek, wrapAad(WrapType.PASSPHRASE), random))
                    }
                    is Recipient.Recovery -> {
                        val kek = hkdf(r.key.bytes(), salt, "parley/v1/archive-recovery")
                        KeyWrap(WrapType.RECOVERY, gcmSeal(kek, dek, wrapAad(WrapType.RECOVERY), random))
                    }
                    is Recipient.PublicKey -> {
                        val ct = rsaOaep().run {
                            init(Cipher.ENCRYPT_MODE, r.bundle.publicKey, OAEP, random)
                            doFinal(dek)
                        }
                        val bundle = r.bundle.toBytes()
                        val bb = ByteArrayOutputStream()
                        DataOutputStream(bb).apply {
                            writeInt(bundle.size); write(bundle); writeInt(ct.size); write(ct)
                        }
                        KeyWrap(WrapType.PUBLIC_KEY, bb.toByteArray())
                    }
                }
            }
            val header = buildHeader(iterations, salt, SEGMENT_SIZE, prefix, wraps)
            return EncryptingOutputStream(out, header, SecretKeySpec(dek, "AES"))
        } finally {
            dek.fill(0)
        }
    }

    /** One-shot helper for small payloads (tests, key-bundle export). */
    fun encryptBytes(
        plaintext: ByteArray,
        recipients: List<Recipient>,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val bo = ByteArrayOutputStream()
        encrypt(bo, recipients, iterations, random).use { it.write(plaintext) }
        return bo.toByteArray()
    }

    // ---------------------------------------------------------------- decryption

    /** Reads and validates the header, leaving [input] positioned at the first segment. */
    fun readHeader(input: InputStream): EnvelopeHeader {
        val din = DataInputStream(input)
        try {
            val magic = ByteArray(MAGIC_BYTES.size).also(din::readFully)
            if (!magic.contentEquals(MAGIC_BYTES)) throw BackupIntegrityException("Not a Parley backup")
            val version = din.readUnsignedByte()
            if (version != VERSION) throw BackupIntegrityException("Unsupported backup version $version")
            val bodyLen = din.readInt()
            if (bodyLen !in 1..MAX_HEADER_BODY) throw BackupIntegrityException("Bad header length")
            val body = ByteArray(bodyLen).also(din::readFully)
            val headerBytes = ByteArrayOutputStream(9 + 4 + bodyLen).apply {
                write(magic); write(version); write(ByteBuffer.allocate(4).putInt(bodyLen).array()); write(body)
            }.toByteArray()
            return parseBody(version, body, headerBytes)
        } catch (e: EOFException) {
            throw BackupIntegrityException("Truncated header", e)
        }
    }

    private fun parseBody(version: Int, body: ByteArray, headerBytes: ByteArray): EnvelopeHeader {
        val bin = ByteArrayInputStream(body)
        val d = DataInputStream(bin)
        try {
            val kdf = d.readUnsignedByte()
            if (kdf != KDF_PBKDF2_SHA256) throw BackupIntegrityException("Unknown KDF $kdf")
            val iterations = d.readInt()
            if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupIntegrityException("KDF iterations out of range")
            val saltLen = d.readUnsignedByte()
            if (saltLen != SALT_SIZE) throw BackupIntegrityException("Bad salt length")
            val salt = ByteArray(saltLen).also(d::readFully)
            val seg = d.readInt()
            if (seg < 4096 || seg > 1 shl 20 || Integer.bitCount(seg) != 1) throw BackupIntegrityException("Bad segment size")
            val prefix = ByteArray(NONCE_PREFIX_SIZE).also(d::readFully)
            val count = d.readUnsignedByte()
            if (count !in 1..MAX_WRAPS) throw BackupIntegrityException("Bad key wrap count")
            val wraps = (0 until count).map {
                val type = WrapType.of(d.readUnsignedByte())
                val len = d.readInt()
                if (len < 0 || len > bin.available()) throw BackupIntegrityException("Bad key wrap length")
                KeyWrap(type, ByteArray(len).also(d::readFully))
            }
            if (bin.available() != 0) throw BackupIntegrityException("Trailing header bytes")
            return EnvelopeHeader(version, iterations, salt, seg, prefix, wraps, headerBytes)
        } catch (e: EOFException) {
            throw BackupIntegrityException("Truncated header", e)
        }
    }

    /**
     * Recovers the archive's data key. Throws [WrongKeyException] if no wrap opens with [unlock].
     * Note: a corrupted wrap is indistinguishable from a wrong secret at this point.
     */
    fun unwrapDataKey(header: EnvelopeHeader, unlock: Unlock): SecretKey {
        val dek: ByteArray? = when (unlock) {
            is Unlock.Passphrase -> {
                var found: ByteArray? = null
                if (header.wraps.any { it.type == WrapType.PASSPHRASE }) {
                    val kek = pbkdf2(unlock.passphrase, header.salt, header.iterations)
                    found = header.wraps.filter { it.type == WrapType.PASSPHRASE }.firstNotNullOfOrNull {
                        gcmOpenOrNull(kek, it.payload, wrapAad(WrapType.PASSPHRASE))
                    }
                }
                found ?: publicWraps(header).firstNotNullOfOrNull { (bundle, ct) ->
                    val pk = try { unlockPrivateKey(bundle, unlock.passphrase) } catch (_: WrongKeyException) { null }
                    pk?.let { rsaOpenOrNull(it, ct) }
                }
            }
            is Unlock.Recovery -> {
                val kek = hkdf(unlock.key.bytes(), header.salt, "parley/v1/archive-recovery")
                header.wraps.filter { it.type == WrapType.RECOVERY }.firstNotNullOfOrNull {
                    gcmOpenOrNull(kek, it.payload, wrapAad(WrapType.RECOVERY))
                } ?: publicWraps(header).firstNotNullOfOrNull { (bundle, ct) ->
                    val pk = try { unlockPrivateKey(bundle, unlock.key) } catch (_: WrongKeyException) { null }
                    pk?.let { rsaOpenOrNull(it, ct) }
                }
            }
            is Unlock.WithPrivateKey -> publicWraps(header).firstNotNullOfOrNull { (_, ct) -> rsaOpenOrNull(unlock.key, ct) }
        }
        if (dek == null || dek.size != 32) throw WrongKeyException("Wrong passphrase or key")
        return SecretKeySpec(dek, "AES").also { dek.fill(0) }
    }

    /** Reads the header from [input], unwraps the data key and returns the plaintext stream. */
    fun decrypt(input: InputStream, unlock: Unlock): DecryptingInputStream {
        val header = readHeader(input)
        return DecryptingInputStream(input, header, unwrapDataKey(header, unlock))
    }

    /**
     * Like [decrypt] with a data key from [unwrapDataKey]: lets a re-openable source (the archive
     * reader's second pass) skip the KDF.
     */
    fun decrypt(input: InputStream, dataKey: SecretKey): DecryptingInputStream =
        DecryptingInputStream(input, readHeader(input), dataKey)

    fun decryptBytes(ciphertext: ByteArray, unlock: Unlock): ByteArray =
        decrypt(ByteArrayInputStream(ciphertext), unlock).use { it.readBytes() }

    // ---------------------------------------------------------------- key bundle

    /**
     * Creates the RSA-3072 key pair used for scheduled backups. The private key is stored only
     * encrypted, under the passphrase and under [recoveryKey] (show [RecoveryKey.format] to the user).
     */
    fun createKeyBundle(
        passphrase: CharArray,
        recoveryKey: RecoveryKey = RecoveryKey.generate(),
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): KeyBundle {
        checkIterations(iterations)
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(RSAKeyGenParameterSpec(RSA_BITS, RSAKeyGenParameterSpec.F4), random)
        val kp = kpg.generateKeyPair()
        val pkcs8 = kp.private.encoded
        try {
            return sealBundle(kp.public.encoded, pkcs8, passphrase, recoveryKey, iterations, random)
        } finally {
            pkcs8.fill(0)
        }
    }

    fun unlockPrivateKey(bundle: KeyBundle, passphrase: CharArray): PrivateKey {
        val kek = pbkdf2(passphrase, bundle.salt, bundle.iterations)
        return bundlePrivate(bundle, gcmOpenOrNull(kek, bundle.passphraseWrap, bundleAad(bundle.publicKeyBytes, WrapType.PASSPHRASE)))
    }

    fun unlockPrivateKey(bundle: KeyBundle, recoveryKey: RecoveryKey): PrivateKey {
        val kek = hkdf(recoveryKey.bytes(), bundle.recoverySalt, "parley/v1/bundle-recovery")
        return bundlePrivate(bundle, gcmOpenOrNull(kek, bundle.recoveryWrap, bundleAad(bundle.publicKeyBytes, WrapType.RECOVERY)))
    }

    /**
     * Re-wraps the private key under [newPassphrase] with a fresh salt. The key pair, the recovery
     * wrap and therefore every existing backup stay valid.
     */
    fun changePassphrase(
        bundle: KeyBundle,
        oldPassphrase: CharArray,
        newPassphrase: CharArray,
        iterations: Int = bundle.iterations,
        random: SecureRandom = SecureRandom(),
    ): KeyBundle {
        checkIterations(iterations)
        val pk = unlockPrivateKey(bundle, oldPassphrase)
        val pkcs8 = pk.encoded
        try {
            // The recovery wrap has its own salt (recoverySalt), so it is kept byte-for-byte.
            val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
            val passKek = pbkdf2(newPassphrase, salt, iterations)
            val passWrap = gcmSeal(passKek, pkcs8, bundleAad(bundle.publicKeyBytes, WrapType.PASSPHRASE), random)
            return KeyBundle(bundle.publicKeyBytes, iterations, salt, passWrap, bundle.recoveryWrap, bundle.recoverySalt)
        } finally {
            pkcs8.fill(0)
        }
    }

    private fun sealBundle(
        pub: ByteArray, pkcs8: ByteArray, passphrase: CharArray, recoveryKey: RecoveryKey, iterations: Int, random: SecureRandom,
    ): KeyBundle {
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val recSalt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val passWrap = gcmSeal(pbkdf2(passphrase, salt, iterations), pkcs8, bundleAad(pub, WrapType.PASSPHRASE), random)
        val recKek = hkdf(recoveryKey.bytes(), recSalt, "parley/v1/bundle-recovery")
        val recWrap = gcmSeal(recKek, pkcs8, bundleAad(pub, WrapType.RECOVERY), random)
        return KeyBundle(pub, iterations, salt, passWrap, recWrap, recSalt)
    }

    private fun bundlePrivate(bundle: KeyBundle, pkcs8: ByteArray?): PrivateKey {
        if (pkcs8 == null) throw WrongKeyException("Wrong passphrase or recovery key")
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        } catch (e: GeneralSecurityException) {
            throw BackupIntegrityException("Corrupt private key", e)
        } finally {
            pkcs8.fill(0)
        }
    }

    private fun bundleAad(pub: ByteArray, type: WrapType): ByteArray =
        "PARLEYK1".toByteArray(Charsets.US_ASCII) + type.id.toByte() + sha256(pub)

    // ---------------------------------------------------------------- primitives

    internal fun checkIterations(iterations: Int) =
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations must be in $MIN_ITERATIONS..$MAX_ITERATIONS" }

    private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    private fun rsaOaep(): Cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")

    private fun rsaOpenOrNull(key: PrivateKey, ct: ByteArray): ByteArray? = try {
        rsaOaep().run { init(Cipher.DECRYPT_MODE, key, OAEP); doFinal(ct) }
    } catch (_: GeneralSecurityException) {
        null
    }

    private fun publicWraps(header: EnvelopeHeader): List<Pair<KeyBundle, ByteArray>> =
        header.wraps.filter { it.type == WrapType.PUBLIC_KEY }.map { splitPublicWrap(it.payload) }

    internal fun splitPublicWrap(payload: ByteArray): Pair<KeyBundle, ByteArray> {
        val d = DataInputStream(ByteArrayInputStream(payload))
        try {
            val bl = d.readInt()
            if (bl < 0 || bl > payload.size) throw BackupIntegrityException("Bad key bundle length")
            val bundle = KeyBundle.fromBytes(ByteArray(bl).also(d::readFully))
            val cl = d.readInt()
            if (cl < 0 || cl > 1024) throw BackupIntegrityException("Bad RSA wrap length")
            return bundle to ByteArray(cl).also(d::readFully)
        } catch (e: EOFException) {
            throw BackupIntegrityException("Truncated key wrap", e)
        }
    }

    private fun buildHeader(iterations: Int, salt: ByteArray, seg: Int, prefix: ByteArray, wraps: List<KeyWrap>): EnvelopeHeader {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            writeByte(KDF_PBKDF2_SHA256); writeInt(iterations); writeByte(salt.size); write(salt)
            writeInt(seg); write(prefix); writeByte(wraps.size)
            wraps.forEach { w -> val p = w.payload; writeByte(w.type.id); writeInt(p.size); write(p) }
        }
        val b = body.toByteArray()
        check(b.size <= MAX_HEADER_BODY) { "Header too large" }
        val all = ByteArrayOutputStream()
        DataOutputStream(all).apply { write(MAGIC_BYTES); writeByte(VERSION); writeInt(b.size); write(b) }
        return EnvelopeHeader(VERSION, iterations, salt, seg, prefix, wraps, all.toByteArray())
    }

    private fun wrapAad(type: WrapType): ByteArray = MAGIC_BYTES + VERSION.toByte() + type.id.toByte()

    internal fun pbkdf2(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        checkIterations(iterations)
        // NFC so the same passphrase typed on another keyboard/device derives the same key.
        val normalized = Normalizer.normalize(String(passphrase), Normalizer.Form.NFC).toCharArray()
        val spec = PBEKeySpec(normalized, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword(); normalized.fill('\u0000')
        }
    }

    /** HKDF-SHA256 (RFC 5869), 32-byte output. */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info.toByteArray(Charsets.UTF_8)); mac.update(1)
        return mac.doFinal().also { prk.fill(0) }
    }

    private fun gcmSeal(key: ByteArray, plaintext: ByteArray, aad: ByteArray, random: SecureRandom): ByteArray {
        val nonce = ByteArray(GCM_NONCE).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(plaintext)
    }

    private fun gcmOpenOrNull(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < GCM_NONCE + TAG_SIZE) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, sealed, 0, GCM_NONCE))
            c.updateAAD(aad)
            c.doFinal(sealed, GCM_NONCE, sealed.size - GCM_NONCE)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    internal fun segmentNonce(prefix: ByteArray, counter: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(GCM_NONCE).put(prefix).putInt(counter.toInt()).put(if (last) 1 else 0).array()

    internal fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}

/**
 * Encrypts into 64 KiB STREAM segments with constant memory. The header has already been written
 * by the time the constructor returns. Not thread-safe.
 */
class EncryptingOutputStream internal constructor(
    out: OutputStream,
    val header: EnvelopeHeader,
    private val key: SecretKey,
) : FilterOutputStream(out) {
    private val seg = header.segmentSize
    private val buf = ByteArray(seg)
    private var len = 0
    private var counter = 0L
    private var finished = false
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val prefix = header.noncePrefix
    private val aad = header.aad

    init {
        out.write(header.aad)
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!finished) { "Stream already finished" }
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
        var o = off
        var n = len
        while (n > 0) {
            // A full buffer is only flushed once more data arrives, so the final segment is always
            // emitted by finish() with the last flag set (an exact multiple of 64 KiB ends in a full last segment).
            if (this.len == seg) emit(last = false)
            val k = minOf(n, seg - this.len)
            System.arraycopy(b, o, buf, this.len, k)
            this.len += k; o += k; n -= k
        }
    }

    private fun emit(last: Boolean) {
        if (counter > 0xFFFF_FFFFL) throw IOException("Backup too large")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(BackupCrypto.TAG_SIZE * 8, BackupCrypto.segmentNonce(prefix, counter, last)))
        cipher.updateAAD(aad)
        out.write(cipher.doFinal(buf, 0, len))
        counter++
        len = 0
    }

    override fun flush() {
        // Segments are fixed-size; only flush what has already been sealed.
        out.flush()
    }

    /** Seals the last segment without closing the underlying stream. Idempotent. */
    fun finish() {
        if (finished) return
        emit(last = true)
        finished = true
        buf.fill(0)
        out.flush()
    }

    override fun close() {
        try {
            finish()
        } finally {
            out.close()
        }
    }
}

/**
 * Decrypts STREAM segments, releasing plaintext only after each segment's tag verifies. Reaching EOF
 * (-1) guarantees the whole payload was authentic and complete; any truncation, reordering, extension
 * or bit flip raises [BackupIntegrityException]. Callers must not act on data before reading to EOF
 * unless the action is undoable.
 */
class DecryptingInputStream internal constructor(
    input: InputStream,
    val header: EnvelopeHeader,
    private val key: SecretKey,
) : InputStream() {
    private val src = java.io.PushbackInputStream(input, 1)
    private val cipherLen = header.segmentSize + BackupCrypto.TAG_SIZE
    private val cbuf = ByteArray(cipherLen)
    private var plain = ByteArray(0)
    private var pos = 0
    private var counter = 0L
    private var done = false
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val prefix = header.noncePrefix

    private fun fill(): Boolean {
        while (pos >= plain.size) {
            if (done) return false
            var n = 0
            while (n < cipherLen) {
                val r = src.read(cbuf, n, cipherLen - n)
                if (r < 0) break
                n += r
            }
            val last = if (n < cipherLen) true else {
                val peek = src.read()
                if (peek < 0) true else { src.unread(peek); false }
            }
            if (n < BackupCrypto.TAG_SIZE) throw BackupIntegrityException("Truncated backup")
            if (counter > 0xFFFF_FFFFL) throw BackupIntegrityException("Too many segments")
            plain = try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BackupCrypto.TAG_SIZE * 8, BackupCrypto.segmentNonce(prefix, counter, last)))
                cipher.updateAAD(header.aad)
                cipher.doFinal(cbuf, 0, n)
            } catch (e: AEADBadTagException) {
                throw BackupIntegrityException("Backup is corrupted, truncated or was modified (segment $counter)", e)
            } catch (e: GeneralSecurityException) {
                throw BackupIntegrityException("Backup decryption failed", e)
            }
            pos = 0
            counter++
            if (last) done = true
        }
        return true
    }

    override fun read(): Int = if (!fill()) -1 else plain[pos++].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        if (!fill()) return -1
        val k = minOf(len, plain.size - pos)
        System.arraycopy(plain, pos, b, off, k)
        pos += k
        return k
    }

    override fun available(): Int = plain.size - pos

    override fun close() = src.close()
}

/**
 * Persistent public-key material for scheduled backups. Holds the RSA public key in the clear and the
 * PKCS#8 private key twice: AES-GCM-sealed under PBKDF2(passphrase, [salt]) and under
 * HKDF(recovery key, [recoverySalt]). Safe to store unprotected and embedded in every archive.
 */
class KeyBundle internal constructor(
    publicKeyBytes: ByteArray,
    val iterations: Int,
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
    val keyId: String get() = BackupCrypto.sha256(pub).copyOf(8).joinToString("") { "%02x".format(it) }

    fun toBytes(): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII)); writeByte(1)
            writeInt(iterations); writeByte(s.size); write(s); writeByte(rs.size); write(rs)
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
            if (bytes.size > 32 * 1024) throw BackupIntegrityException("Key bundle too large")
            val bin = ByteArrayInputStream(bytes)
            val d = DataInputStream(bin)
            try {
                val magic = ByteArray(8).also(d::readFully)
                if (!magic.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) throw BackupIntegrityException("Not a Parley key bundle")
                if (d.readUnsignedByte() != 1) throw BackupIntegrityException("Unsupported key bundle version")
                val it = d.readInt()
                if (it !in BackupCrypto.MIN_ITERATIONS..BackupCrypto.MAX_ITERATIONS) throw BackupIntegrityException("KDF iterations out of range")
                fun field(maxLen: Int, lenReader: () -> Int): ByteArray {
                    val l = lenReader()
                    if (l < 0 || l > maxLen || l > bin.available()) throw BackupIntegrityException("Bad key bundle field")
                    return ByteArray(l).also(d::readFully)
                }
                val salt = field(64) { d.readUnsignedByte() }
                val recSalt = field(64) { d.readUnsignedByte() }
                if (salt.size != BackupCrypto.SALT_SIZE || recSalt.size != BackupCrypto.SALT_SIZE) throw BackupIntegrityException("Bad salt")
                val pub = field(MAX_FIELD) { d.readUnsignedShort() }
                val pw = field(MAX_FIELD) { d.readUnsignedShort() }
                val rw = field(MAX_FIELD) { d.readUnsignedShort() }
                if (bin.available() != 0) throw BackupIntegrityException("Trailing key bundle bytes")
                val b = KeyBundle(pub, it, salt, pw, rw, recSalt)
                val pk = b.publicKey
                if (pk !is RSAPublicKey || pk.modulus.bitLength() < 2048) throw BackupIntegrityException("Unsupported public key")
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
