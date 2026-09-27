package app.parley.common.backup

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
 * Encrypted folder sync, with the backup's KDF and AES-GCM.
 *
 * The folder holds one header file ([HEADER_NAME]) with the KDF settings and salt, and a check value that tells a
 * wrong passphrase from damage. Every phone that shares the folder derives the same key from the shared passphrase
 * once and keeps it in its Keystore-sealed storage, so syncing needs no passphrase afterwards.
 *
 *   header := "PARLEYS1" | u8 kdfAlg | u32 kdfParam | u8 saltLen | salt | check (nonce[12] | GCM(key, "", aad = "PARLEYS1|check"))
 *   file   := "PARLEYF1" | nonce[12] | GCM(key, vCard bytes, aad = "PARLEYF1|" + file name)
 *
 * The file name is authenticated, so a folder writer can't swap two people's files, and every file is authenticated,
 * so one can't edit or plant a contact without the passphrase. File names are hashes: they say nothing about anyone.
 */
object SyncCrypto {
    const val HEADER_NAME = ".parley-sync"
    const val EXTENSION = ".parleycard"
    private const val HEADER_MAGIC = "PARLEYS1"
    private const val FILE_MAGIC = "PARLEYF1"
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private const val MAX_HEADER = 4096

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
        if (header.size > MAX_HEADER) throw BackupIntegrityException("Sync header too large")
        val d = DataInputStream(ByteArrayInputStream(header))
        try {
            val magic = ByteArray(8).also(d::readFully)
            if (!magic.contentEquals(HEADER_MAGIC.toByteArray(Charsets.US_ASCII))) throw BackupIntegrityException("Not a Parley sync folder")
            val kdf = KdfParams.of(d.readUnsignedByte(), d.readInt())
            if (!KdfPolicy.BACKUP.accepts(kdf)) throw BackupIntegrityException("KDF parameters out of range")
            val saltLen = d.readUnsignedByte()
            if (saltLen != BackupCrypto.SALT_SIZE) throw BackupIntegrityException("Bad salt length")
            val salt = ByteArray(saltLen).also(d::readFully)
            val check = d.readBytes()
            if (check.size != NONCE + TAG_BITS / 8) throw BackupIntegrityException("Bad sync header")
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

    fun seal(key: ByteArray, fileName: String, plain: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        return FILE_MAGIC.toByteArray(Charsets.US_ASCII) + nonce + gcm(Cipher.ENCRYPT_MODE, key, nonce, plain, fileAad(fileName))
    }

    /** The plain file, or null when it isn't sealed with [key] under [fileName] (another key, renamed, or altered). */
    fun open(key: ByteArray, fileName: String, sealed: ByteArray): ByteArray? {
        val m = FILE_MAGIC.length
        if (sealed.size < m + NONCE + TAG_BITS / 8) return null
        if (!sealed.copyOf(m).contentEquals(FILE_MAGIC.toByteArray(Charsets.US_ASCII))) return null
        return try {
            gcm(Cipher.DECRYPT_MODE, key, sealed.copyOfRange(m, m + NONCE), sealed.copyOfRange(m + NONCE, sealed.size), fileAad(fileName))
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun checkAad() = "$HEADER_MAGIC|check".toByteArray(Charsets.US_ASCII)

    private fun fileAad(name: String) = "$FILE_MAGIC|$name".toByteArray(Charsets.UTF_8)

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        return c.doFinal(data)
    }
}
