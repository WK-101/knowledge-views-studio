package app.parley.common.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/*
 * Parley Backup envelope ("PARLEYB1"), JCE only.
 *
 * File layout (all integers big-endian):
 *
 *   header := magic "PARLEYB1" | u8 version | u32 bodyLen | body
 *   body   := u8 kdfAlg | u32 kdfParam | u8 saltLen(16) | salt
 *             | u32 segmentSize | noncePrefix[7] | u8 wrapCount | wrap*
 *   wrap   := u8 type | u32 len | payload
 *   payload:
 *     PASSPHRASE  nonce[12] | AES-GCM(KEK = KDF(passphrase, salt), DEK)
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
 * kdfAlg 1 is PBKDF2-HMAC-SHA256 (kdfParam = iterations); kdfAlg 2 is scrypt (kdfParam = log2 N << 16 | r << 8 | p,
 * see [KdfParams]). New files use scrypt; PBKDF2 files stay readable. Readers cap the parameters ([KdfPolicy]).
 *
 * A PUBLIC_KEY wrap embeds the KeyBundle (public key + private key encrypted under the passphrase and
 * under the recovery key), so scheduled backups need only the public key on the phone, yet can be
 * restored on a new device with the passphrase or the recovery key alone.
 */

/** Malformed, truncated or tampered backup data. */
open class BackupIntegrityException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Throws [BackupIntegrityException] with [message] unless [ok]: one line per check of untrusted input. */
internal inline fun intact(ok: Boolean, message: () -> String) {
    if (!ok) throw BackupIntegrityException(message())
}

/** None of the archive's key wraps opens with the supplied secret (wrong passphrase / key). */
class WrongKeyException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** What an archive is encrypted to. Any one of them can later decrypt it. */
sealed interface Recipient {
    /** DEK wrapped under KDF(passphrase). The caller may wipe the array after [BackupCrypto.encrypt]. */
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
    val kdf: KdfParams,
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

    /** The KDF's stored parameter (PBKDF2 iterations, or packed scrypt settings). */
    val iterations: Int get() = kdf.param

    /** The key bundle embedded in a PUBLIC_KEY wrap, if any (to show "encrypted to key XYZ"). */
    val keyBundle: KeyBundle?
        get() = wraps.firstOrNull { it.type == WrapType.PUBLIC_KEY }?.let { BackupCrypto.splitPublicWrap(it.payload).first }
}

object BackupCrypto {
    const val MAGIC = "PARLEYB1"
    const val VERSION = 1
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ITERATIONS = 1_000

    /** Readers refuse more: a crafted file must not make each passphrase attempt burn many seconds. */
    const val MAX_ITERATIONS = 2_000_000

    /** New files: scrypt with N = 2^15, r = 8, p = 1 (32 MB, about a second on a phone). */
    val DEFAULT_KDF: KdfParams = KdfParams.Scrypt(15, 8, 1)
    const val SEGMENT_SIZE = 64 * 1024
    const val SALT_SIZE = 16
    const val NONCE_PREFIX_SIZE = 7
    const val TAG_SIZE = 16
    const val RSA_BITS = 3072

    internal const val MAX_HEADER_BODY = 64 * 1024
    internal const val MAX_WRAPS = 16

    /** Passphrase attempts through embedded key bundles per open (each runs the bundle's KDF). */
    const val MAX_BUNDLE_ATTEMPTS = 2
    private const val GCM_NONCE = 12
    private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.US_ASCII)

    // ---------------------------------------------------------------- encryption

    /**
     * Starts an encrypted archive on [out]: writes the header and returns a stream that encrypts
     * everything written to it. Closing the returned stream finishes the last segment and closes [out];
     * [EncryptingOutputStream.finish] finishes without closing.
     *
     * [kdf] is the cost for [Recipient.Passphrase] wraps (tests use a cheap one).
     */
    fun encrypt(
        out: OutputStream,
        recipients: List<Recipient>,
        kdf: KdfParams = DEFAULT_KDF,
        random: SecureRandom = SecureRandom(),
    ): EncryptingOutputStream {
        require(recipients.isNotEmpty()) { "At least one recipient is required" }
        require(recipients.size <= MAX_WRAPS) { "Too many recipients" }
        checkKdf(kdf)
        val dek = ByteArray(32).also(random::nextBytes)
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val prefix = ByteArray(NONCE_PREFIX_SIZE).also(random::nextBytes)
        try {
            val wraps = recipients.map { r ->
                when (r) {
                    is Recipient.Passphrase -> {
                        val kek = Kdf.derive(r.passphrase, salt, kdf)
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
            val header = buildHeader(kdf, salt, SEGMENT_SIZE, prefix, wraps)
            return EncryptingOutputStream(out, header, SecretKeySpec(dek, "AES"))
        } finally {
            dek.fill(0)
        }
    }

    /** One-shot helper for small payloads (tests, key-bundle export). */
    fun encryptBytes(
        plaintext: ByteArray,
        recipients: List<Recipient>,
        kdf: KdfParams = DEFAULT_KDF,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val bo = ByteArrayOutputStream()
        encrypt(bo, recipients, kdf, random).use { it.write(plaintext) }
        return bo.toByteArray()
    }

    // ---------------------------------------------------------------- decryption

    /**
     * Reads and validates the header, leaving [input] positioned at the first segment. KDF parameters outside
     * [policy] are refused before any key derivation runs.
     */
    fun readHeader(input: InputStream, policy: KdfPolicy = KdfPolicy.BACKUP): EnvelopeHeader {
        val din = DataInputStream(input)
        try {
            val magic = ByteArray(MAGIC_BYTES.size).also(din::readFully)
            intact(magic.contentEquals(MAGIC_BYTES)) { "Not a Parley backup" }
            val version = din.readUnsignedByte()
            intact(version == VERSION) { "Unsupported backup version $version" }
            val bodyLen = din.readInt()
            intact(bodyLen in 1..MAX_HEADER_BODY) { "Bad header length" }
            val body = ByteArray(bodyLen).also(din::readFully)
            val headerBytes = ByteArrayOutputStream(9 + 4 + bodyLen).apply {
                write(magic); write(version); write(ByteBuffer.allocate(4).putInt(bodyLen).array()); write(body)
            }.toByteArray()
            return parseBody(version, body, headerBytes, policy)
        } catch (e: EOFException) {
            throw BackupIntegrityException("Truncated header", e)
        }
    }

    private fun parseBody(version: Int, body: ByteArray, headerBytes: ByteArray, policy: KdfPolicy): EnvelopeHeader {
        val bin = ByteArrayInputStream(body)
        val d = DataInputStream(bin)
        try {
            val kdf = KdfParams.of(d.readUnsignedByte(), d.readInt())
            intact(policy.accepts(kdf)) { "KDF parameters out of range" }
            val saltLen = d.readUnsignedByte()
            intact(saltLen == SALT_SIZE) { "Bad salt length" }
            val salt = ByteArray(saltLen).also(d::readFully)
            val seg = d.readInt()
            intact(seg in 4096..(1 shl 20) && Integer.bitCount(seg) == 1) { "Bad segment size" }
            val prefix = ByteArray(NONCE_PREFIX_SIZE).also(d::readFully)
            val count = d.readUnsignedByte()
            intact(count in 1..MAX_WRAPS) { "Bad key wrap count" }
            val wraps = (0 until count).map {
                val type = WrapType.of(d.readUnsignedByte())
                val len = d.readInt()
                intact(len in 0..bin.available()) { "Bad key wrap length" }
                KeyWrap(type, ByteArray(len).also(d::readFully))
            }
            intact(bin.available() == 0) { "Trailing header bytes" }
            return EnvelopeHeader(version, kdf, salt, seg, prefix, wraps, headerBytes)
        } catch (e: EOFException) {
            throw BackupIntegrityException("Truncated header", e)
        }
    }

    /** An opened archive key, with the key bundle it came through (null for a direct passphrase or recovery wrap). */
    class Opened internal constructor(val dataKey: SecretKey, val bundle: KeyBundle?, val privateKey: PrivateKey?)

    /**
     * Recovers the archive's data key. Throws [WrongKeyException] if no wrap opens with [unlock].
     * Note: a corrupted wrap is indistinguishable from a wrong secret at this point.
     */
    fun unwrapDataKey(header: EnvelopeHeader, unlock: Unlock, policy: KdfPolicy = KdfPolicy.BACKUP): SecretKey = open(header, unlock, policy).dataKey

    /**
     * Like [unwrapDataKey], and says which key bundle opened the archive. That bundle is genuine (its private key
     * opened with the user's own secret), so it can vouch for the device key that signed the archive.
     *
     * [policy] bounds every key derivation a passphrase attempt runs: the header's KDF (checked when it was read) and
     * the KDF of each embedded key bundle, so a payload held to one fixed cost (a QR code, a setup file) can't carry a
     * bundle that makes each attempt far more expensive. At most [MAX_BUNDLE_ATTEMPTS] bundles are tried.
     */
    fun open(header: EnvelopeHeader, unlock: Unlock, policy: KdfPolicy = KdfPolicy.BACKUP): Opened {
        var bundle: KeyBundle? = null
        var privateKey: PrivateKey? = null
        fun viaBundles(unlockBundle: (KeyBundle) -> PrivateKey?): ByteArray? = publicWraps(header).firstNotNullOfOrNull { (b, ct) ->
            val pk = unlockBundle(b) ?: return@firstNotNullOfOrNull null
            rsaOpenOrNull(pk, ct)?.also { bundle = b; privateKey = pk }
        }
        val dek: ByteArray? = when (unlock) {
            is Unlock.Passphrase -> openPassphraseWrap(header, unlock.passphrase)
                ?: viaBundles(bundleUnlocker(unlock.passphrase, policy))
            is Unlock.Recovery -> {
                val kek = hkdf(unlock.key.bytes(), header.salt, "parley/v1/archive-recovery")
                header.wraps.filter { it.type == WrapType.RECOVERY }.firstNotNullOfOrNull {
                    gcmOpenOrNull(kek, it.payload, wrapAad(WrapType.RECOVERY))
                } ?: viaBundles { b -> try { unlockPrivateKey(b, unlock.key) } catch (_: WrongKeyException) { null } }
            }
            // The caller's own key: no bundle vouches for anything here.
            is Unlock.WithPrivateKey -> publicWraps(header).firstNotNullOfOrNull { (_, ct) -> rsaOpenOrNull(unlock.key, ct) }
        }
        if (dek == null || dek.size != 32) throw WrongKeyException("Wrong passphrase or key")
        return Opened(SecretKeySpec(dek, "AES").also { dek.fill(0) }, bundle, privateKey)
    }

    /** Unlocks embedded bundles with [passphrase]: only those whose KDF [policy] allows, at most [MAX_BUNDLE_ATTEMPTS]. */
    private fun bundleUnlocker(passphrase: CharArray, policy: KdfPolicy): (KeyBundle) -> PrivateKey? {
        var derivations = 0
        return { b ->
            if (!policy.accepts(b.kdf) || derivations >= MAX_BUNDLE_ATTEMPTS) {
                null
            } else {
                derivations++
                try { unlockPrivateKey(b, passphrase) } catch (_: WrongKeyException) { null }
            }
        }
    }

    /** The data key from a direct PASSPHRASE wrap (derived only when the archive has one), or null. */
    private fun openPassphraseWrap(header: EnvelopeHeader, passphrase: CharArray): ByteArray? {
        val wraps = header.wraps.filter { it.type == WrapType.PASSPHRASE }
        if (wraps.isEmpty()) return null
        val kek = Kdf.derive(passphrase, header.salt, header.kdf)
        return wraps.firstNotNullOfOrNull { gcmOpenOrNull(kek, it.payload, wrapAad(WrapType.PASSPHRASE)) }
    }

    /** Reads the header from [input], unwraps the data key and returns the plaintext stream. */
    fun decrypt(input: InputStream, unlock: Unlock, policy: KdfPolicy = KdfPolicy.BACKUP): DecryptingInputStream {
        val header = readHeader(input, policy)
        return DecryptingInputStream(input, header, unwrapDataKey(header, unlock, policy))
    }

    /**
     * Like [decrypt] with a data key from [unwrapDataKey]: lets a re-openable source (the archive
     * reader's second pass) skip the KDF.
     */
    fun decrypt(input: InputStream, dataKey: SecretKey): DecryptingInputStream =
        DecryptingInputStream(input, readHeader(input), dataKey)

    fun decryptBytes(ciphertext: ByteArray, unlock: Unlock, policy: KdfPolicy = KdfPolicy.BACKUP): ByteArray =
        decrypt(ByteArrayInputStream(ciphertext), unlock, policy).use { it.readBytes() }

    // ---------------------------------------------------------------- key bundle

    /**
     * Creates the RSA-3072 key pair used for scheduled backups. The private key is stored only
     * encrypted, under the passphrase and under [recoveryKey] (show [RecoveryKey.format] to the user).
     */
    fun createKeyBundle(
        passphrase: CharArray,
        recoveryKey: RecoveryKey = RecoveryKey.generate(),
        kdf: KdfParams = DEFAULT_KDF,
        random: SecureRandom = SecureRandom(),
    ): KeyBundle {
        checkKdf(kdf)
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(RSAKeyGenParameterSpec(RSA_BITS, RSAKeyGenParameterSpec.F4), random)
        val kp = kpg.generateKeyPair()
        val pkcs8 = kp.private.encoded
        try {
            return sealBundle(kp.public.encoded, pkcs8, passphrase, recoveryKey, kdf, random)
        } finally {
            pkcs8.fill(0)
        }
    }

    fun unlockPrivateKey(bundle: KeyBundle, passphrase: CharArray): PrivateKey {
        val kek = Kdf.derive(passphrase, bundle.salt, bundle.kdf)
        return bundlePrivate(bundle, gcmOpenOrNull(kek, bundle.passphraseWrap, bundleAad(bundle.publicKeyBytes, WrapType.PASSPHRASE)))
    }

    fun unlockPrivateKey(bundle: KeyBundle, recoveryKey: RecoveryKey): PrivateKey {
        val kek = hkdf(recoveryKey.bytes(), bundle.recoverySalt, "parley/v1/bundle-recovery")
        return bundlePrivate(bundle, gcmOpenOrNull(kek, bundle.recoveryWrap, bundleAad(bundle.publicKeyBytes, WrapType.RECOVERY)))
    }

    /**
     * Re-wraps the private key under [newPassphrase] with a fresh salt (and, by default, the current [DEFAULT_KDF], so
     * older PBKDF2 bundles move to scrypt). The key pair, the recovery wrap and therefore every existing backup stay valid.
     */
    fun changePassphrase(
        bundle: KeyBundle,
        oldPassphrase: CharArray,
        newPassphrase: CharArray,
        kdf: KdfParams = DEFAULT_KDF,
        random: SecureRandom = SecureRandom(),
    ): KeyBundle {
        checkKdf(kdf)
        val pk = unlockPrivateKey(bundle, oldPassphrase)
        val pkcs8 = pk.encoded
        try {
            // The recovery wrap has its own salt (recoverySalt), so it is kept byte-for-byte.
            val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
            val passKek = Kdf.derive(newPassphrase, salt, kdf)
            val passWrap = gcmSeal(passKek, pkcs8, bundleAad(bundle.publicKeyBytes, WrapType.PASSPHRASE), random)
            return KeyBundle(bundle.publicKeyBytes, kdf, salt, passWrap, bundle.recoveryWrap, bundle.recoverySalt)
        } finally {
            pkcs8.fill(0)
        }
    }

    private fun sealBundle(
        pub: ByteArray, pkcs8: ByteArray, passphrase: CharArray, recoveryKey: RecoveryKey, kdf: KdfParams, random: SecureRandom,
    ): KeyBundle {
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val recSalt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val passWrap = gcmSeal(Kdf.derive(passphrase, salt, kdf), pkcs8, bundleAad(pub, WrapType.PASSPHRASE), random)
        val recKek = hkdf(recoveryKey.bytes(), recSalt, "parley/v1/bundle-recovery")
        val recWrap = gcmSeal(recKek, pkcs8, bundleAad(pub, WrapType.RECOVERY), random)
        return KeyBundle(pub, kdf, salt, passWrap, recWrap, recSalt)
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

    internal fun checkKdf(kdf: KdfParams) = require(KdfPolicy.BACKUP.accepts(kdf)) { "KDF parameters out of range: $kdf" }

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
            intact(bl in 0..payload.size) { "Bad key bundle length" }
            val bundle = KeyBundle.fromBytes(ByteArray(bl).also(d::readFully))
            val cl = d.readInt()
            intact(cl in 0..1024) { "Bad RSA wrap length" }
            return bundle to ByteArray(cl).also(d::readFully)
        } catch (e: EOFException) {
            throw BackupIntegrityException("Truncated key wrap", e)
        }
    }

    private fun buildHeader(kdf: KdfParams, salt: ByteArray, seg: Int, prefix: ByteArray, wraps: List<KeyWrap>): EnvelopeHeader {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            writeByte(kdf.alg); writeInt(kdf.param); writeByte(salt.size); write(salt)
            writeInt(seg); write(prefix); writeByte(wraps.size)
            wraps.forEach { w -> val p = w.payload; writeByte(w.type.id); writeInt(p.size); write(p) }
        }
        val b = body.toByteArray()
        check(b.size <= MAX_HEADER_BODY) { "Header too large" }
        val all = ByteArrayOutputStream()
        DataOutputStream(all).apply { write(MAGIC_BYTES); writeByte(VERSION); writeInt(b.size); write(b) }
        return EnvelopeHeader(VERSION, kdf, salt, seg, prefix, wraps, all.toByteArray())
    }

    private fun wrapAad(type: WrapType): ByteArray = MAGIC_BYTES + VERSION.toByte() + type.id.toByte()

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
