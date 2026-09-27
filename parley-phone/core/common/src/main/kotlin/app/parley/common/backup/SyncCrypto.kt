package app.parley.common.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted folder sync, with the backup's KDF and AES-GCM.
 *
 * The folder holds one header file ([HEADER_NAME]) with the KDF settings and salt, and a check value that tells a
 * wrong passphrase from damage. Every phone that shares the folder derives the same key from the shared passphrase
 * once and keeps it in its Keystore-sealed storage, so syncing needs no passphrase afterwards.
 *
 *   header := "PARLEYS1" | u8 kdfAlg | u32 kdfParam | u8 saltLen | salt | check (nonce[12] | GCM(key, "", aad = "PARLEYS1|check"))
 *   file   := "PARLEYF2" | nonce[12] | GCM(key, u64 version | vCard bytes, aad = "PARLEYF2|" + file name)
 *
 * The file name is authenticated, so a folder writer can't swap two people's files, and every file is authenticated,
 * so one can't edit or plant a contact without the passphrase. File names are hashes: they say nothing about anyone.
 *
 * What authentication alone can't catch is an older copy of a file put back (a rollback) or a deleted contact's
 * file put back (a resurrection): both are valid ciphertexts. Each write therefore seals a version that only grows
 * ([app.parley.common.sync.FolderSyncRules.nextVersion]); a phone remembers the last version it saw of each file and
 * of each deleted one, and ignores anything older. A phone that joins later has no such memory, so it can't tell.
 *
 * Files from before versions ("PARLEYF1", the vCard alone) still open, as version 0.
 */
object SyncCrypto {
    const val HEADER_NAME = ".parley-sync"
    const val EXTENSION = ".parleycard"
    private const val HEADER_MAGIC = "PARLEYS1"
    private const val FILE_MAGIC_V1 = "PARLEYF1"
    private const val FILE_MAGIC = "PARLEYF2"
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private const val MAX_HEADER = 4096
    private const val VERSION_BYTES = 8

    /** A new folder header for [passphrase] and the key it yields (32 bytes, keep it sealed). */
    fun newFolder(passphrase: CharArray, kdf: KdfParams = BackupCrypto.DEFAULT_KDF, random: SecureRandom = SecureRandom()): Pair<ByteArray, ByteArray> {
        BackupCrypto.checkKdf(kdf)
        val salt = ByteArray(BackupCrypto.SALT_SIZE).also(random::nextBytes)
        val key = Kdf.derive(passphrase, salt, kdf)
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).apply {
            write(HEADER_MAGIC.toByteArray(Charsets.US_ASCII))
            writeByte(kdf.alg); writeInt(kdf.param); writeByte(salt.size); write(salt)
            val nonce = ByteArray(NONCE).also(random::nextBytes)
            write(nonce)
            write(gcm(Cipher.ENCRYPT_MODE, key, nonce, ByteArray(0), checkAad()))
        }
        return bo.toByteArray() to key
    }

    /**
     * The folder key for [passphrase], or null when the passphrase is wrong. Throws [BackupIntegrityException] for a
     * header that isn't one, or whose KDF cost is outside [KdfPolicy.BACKUP].
     */
    fun unlock(header: ByteArray, passphrase: CharArray): ByteArray? {
        ensure(header.size <= MAX_HEADER, "Sync header too large")
        val d = DataInputStream(ByteArrayInputStream(header))
        try {
            val magic = ByteArray(8).also(d::readFully)
            ensure(magic.contentEquals(HEADER_MAGIC.toByteArray(Charsets.US_ASCII)), "Not a Parley sync folder")
            val kdf = KdfParams.of(d.readUnsignedByte(), d.readInt())
            ensure(KdfPolicy.BACKUP.accepts(kdf), "KDF parameters out of range")
            val saltLen = d.readUnsignedByte()
            ensure(saltLen == BackupCrypto.SALT_SIZE, "Bad salt length")
            val salt = ByteArray(saltLen).also(d::readFully)
            val check = d.readBytes()
            ensure(check.size == NONCE + TAG_BITS / 8, "Bad sync header")
            val key = Kdf.derive(passphrase, salt, kdf)
            return try {
                gcm(Cipher.DECRYPT_MODE, key, check.copyOf(NONCE), check.copyOfRange(NONCE, check.size), checkAad())
                key
            } catch (_: GeneralSecurityException) {
                key.fill(0)
                null
            }
        } catch (e: EOFException) {
            throw BackupIntegrityException("Truncated sync header", e)
        }
    }

    /** A file's vCard and the [version] it was sealed with (0 for files from before versions). */
    class Opened(val vcard: ByteArray, val version: Long)

    fun seal(key: ByteArray, fileName: String, plain: ByteArray, version: Long, random: SecureRandom = SecureRandom()): ByteArray {
        require(version >= 0) { "Negative version" }
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val body = ByteBuffer.allocate(VERSION_BYTES + plain.size).putLong(version).put(plain).array()
        return FILE_MAGIC.toByteArray(Charsets.US_ASCII) + nonce + gcm(Cipher.ENCRYPT_MODE, key, nonce, body, fileAad(FILE_MAGIC, fileName))
    }

    /** The plain file, or null when it isn't sealed with [key] under [fileName] (another key, renamed, or altered). */
    fun open(key: ByteArray, fileName: String, sealed: ByteArray): ByteArray? = openVersioned(key, fileName, sealed)?.vcard

    /** Like [open], with the version the file was sealed with. */
    fun openVersioned(key: ByteArray, fileName: String, sealed: ByteArray): Opened? {
        val m = FILE_MAGIC.length
        if (sealed.size < m + NONCE + TAG_BITS / 8) return null
        val magic = String(sealed.copyOf(m), Charsets.US_ASCII)
        if (magic != FILE_MAGIC && magic != FILE_MAGIC_V1) return null
        val plain = try {
            gcm(Cipher.DECRYPT_MODE, key, sealed.copyOfRange(m, m + NONCE), sealed.copyOfRange(m + NONCE, sealed.size), fileAad(magic, fileName))
        } catch (_: GeneralSecurityException) {
            return null
        }
        if (magic == FILE_MAGIC_V1) return Opened(plain, 0)
        if (plain.size < VERSION_BYTES) return null
        val version = ByteBuffer.wrap(plain, 0, VERSION_BYTES).long
        if (version < 0) return null
        return Opened(plain.copyOfRange(VERSION_BYTES, plain.size), version)
    }

    private fun ensure(ok: Boolean, problem: String) {
        if (!ok) throw BackupIntegrityException(problem)
    }

    private fun checkAad() = "$HEADER_MAGIC|check".toByteArray(Charsets.US_ASCII)

    private fun fileAad(magic: String, name: String) = "$magic|$name".toByteArray(Charsets.UTF_8)

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        return c.doFinal(data)
    }
}
