package app.parley.common.sync.shared

import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.BackupIntegrityException
import app.parley.common.backup.Kdf
import app.parley.common.backup.KdfParams
import app.parley.common.backup.KdfPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encryption of a shared label's folder (docs/SHARED_LABELS.md). Like the folder sync's ([app.parley.common.backup.SyncCrypto])
 * with two differences: the header names the label and the key's **epoch** (it goes up when a member is removed and
 * the key changes), and every file is bound to the label as well as to its name.
 *
 *   header := "PARLEYL1" | u8 idLen | label id | u32 epoch | u8 kdfAlg | u32 kdfParam | u8 saltLen | salt
 *             | nonce[12] | GCM(key, "", aad = "PARLEYL1|check|<label id>|<epoch>")
 *   file   := "PARLEYL1" | nonce[12] | GCM(key, body, aad = "PARLEYL1|<label id>|<file name>")
 */
object SharedLabelCrypto {
    const val HEADER_NAME = ".parley-label"

    /**
     * The signed note of the key change away from epoch [oldEpoch] ([SharedLabelFiles.writeHeaderSig]), sealed with that
     * epoch's key. One per key change, so a member who missed several still finds the one their key opens.
     */
    fun headerSigName(oldEpoch: Int): String = ".parley-label-sig-$oldEpoch"
    const val EXTENSION = ".plabel"
    private const val MAGIC = "PARLEYL1"
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private const val MAX_HEADER = 4096
    private const val MAX_ID = 64

    /** What a header says, before any key is tried. */
    class Header(val labelId: String, val epoch: Int, val kdf: KdfParams, internal val salt: ByteArray, internal val check: ByteArray)

    /** A new header for [labelId] at [epoch] and the key [passphrase] yields (32 bytes: keep it sealed). */
    fun newHeader(
        labelId: String,
        epoch: Int,
        passphrase: CharArray,
        kdf: KdfParams = BackupCrypto.DEFAULT_KDF,
        random: SecureRandom = SecureRandom(),
    ): Pair<ByteArray, ByteArray> {
        require(labelId.isNotEmpty() && labelId.length <= MAX_ID && labelId.all { it.isLetterOrDigit() }) { "Bad label id" }
        require(epoch >= 1) { "Bad epoch" }
        BackupCrypto.checkKdf(kdf)
        val salt = ByteArray(BackupCrypto.SALT_SIZE).also(random::nextBytes)
        val key = Kdf.derive(passphrase, salt, kdf)
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            val id = labelId.toByteArray(Charsets.US_ASCII)
            writeByte(id.size); write(id); writeInt(epoch)
            writeByte(kdf.alg); writeInt(kdf.param); writeByte(salt.size); write(salt)
            val nonce = ByteArray(NONCE).also(random::nextBytes)
            write(nonce)
            write(gcm(Cipher.ENCRYPT_MODE, key, nonce, ByteArray(0), checkAad(labelId, epoch)))
        }
        return bo.toByteArray() to key
    }

    /** Reads a header; throws [BackupIntegrityException] for one that isn't, or whose KDF cost is out of range. */
    fun parseHeader(bytes: ByteArray): Header {
        ensure(bytes.size <= MAX_HEADER, "Label header too large")
        val d = DataInputStream(ByteArrayInputStream(bytes))
        try {
            val magic = ByteArray(MAGIC.length).also(d::readFully)
            ensure(magic.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII)), "Not a Parley shared label")
            val idLen = d.readUnsignedByte()
            ensure(idLen in 1..MAX_ID, "Bad label id")
            val id = String(ByteArray(idLen).also(d::readFully), Charsets.US_ASCII)
            ensure(id.all { it.isLetterOrDigit() }, "Bad label id")
            val epoch = d.readInt()
            ensure(epoch >= 1, "Bad epoch")
            val kdf = KdfParams.of(d.readUnsignedByte(), d.readInt())
            ensure(KdfPolicy.BACKUP.accepts(kdf), "KDF parameters out of range")
            val saltLen = d.readUnsignedByte()
            ensure(saltLen == BackupCrypto.SALT_SIZE, "Bad salt length")
            val salt = ByteArray(saltLen).also(d::readFully)
            val check = d.readBytes()
            ensure(check.size == NONCE + TAG_BITS / 8, "Bad label header")
            return Header(id, epoch, kdf, salt, check)
        } catch (e: EOFException) {
            throw BackupIntegrityException("Truncated label header", e)
        }
    }

    /** The key for [passphrase], or null when it is the wrong one. */
    fun unlock(header: Header, passphrase: CharArray): ByteArray? {
        val key = Kdf.derive(passphrase, header.salt, header.kdf)
        if (opens(header, key)) return key
        key.fill(0)
        return null
    }

    /** Whether [key] is this header's key (a member's stored key after the label's key changed is not). */
    fun opens(header: Header, key: ByteArray): Boolean = try {
        gcm(Cipher.DECRYPT_MODE, key, header.check.copyOf(NONCE), header.check.copyOfRange(NONCE, header.check.size), checkAad(header.labelId, header.epoch))
        true
    } catch (_: GeneralSecurityException) {
        false
    }

    fun seal(key: ByteArray, labelId: String, fileName: String, plain: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        return MAGIC.toByteArray(Charsets.US_ASCII) + nonce + gcm(Cipher.ENCRYPT_MODE, key, nonce, plain, fileAad(labelId, fileName))
    }

    /** The body, or null when the file isn't sealed with [key] for this label under [fileName]. */
    fun open(key: ByteArray, labelId: String, fileName: String, sealed: ByteArray): ByteArray? {
        val m = MAGIC.length
        if (sealed.size < m + NONCE + TAG_BITS / 8) return null
        if (String(sealed.copyOf(m), Charsets.US_ASCII) != MAGIC) return null
        return try {
            gcm(Cipher.DECRYPT_MODE, key, sealed.copyOfRange(m, m + NONCE), sealed.copyOfRange(m + NONCE, sealed.size), fileAad(labelId, fileName))
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun ensure(ok: Boolean, problem: String) {
        if (!ok) throw BackupIntegrityException(problem)
    }

    private fun checkAad(labelId: String, epoch: Int) = "$MAGIC|check|$labelId|$epoch".toByteArray(Charsets.US_ASCII)

    private fun fileAad(labelId: String, name: String) = "$MAGIC|$labelId|$name".toByteArray(Charsets.UTF_8)

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        return c.doFinal(data)
    }
}
