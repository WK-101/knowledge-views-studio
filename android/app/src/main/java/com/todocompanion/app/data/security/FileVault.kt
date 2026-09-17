package com.todocompanion.app.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SEC-1 — at-rest encryption for the byte blobs the app keeps in app-private FILES: task/note
 * attachments (F4) and habit-day photos (K5). Before this, those files were written as plaintext in
 * `filesDir`, readable by anything with filesystem access (root, an adb/device backup, forensic
 * imaging) even when the Room database itself was SQLCipher-encrypted — a gap, since the very same
 * bytes stored inline in the DB WERE protected. FileVault closes it so a file-backed attachment gets
 * the same protection as an inline one.
 *
 * The key is a 256-bit AES key in the AndroidKeyStore (hardware-backed / StrongBox where the device
 * offers it — see [key]), under a dedicated alias, distinct from the DB-passphrase-wrap key in
 * [SecureDb]. It is never exported, never in a backup, never leaves the device. Each blob is
 * AES-256-GCM with a fresh 12-byte IV the provider generates, laid out as:
 *
 *     MAGIC(4) ‖ iv(12) ‖ ciphertext+tag
 *
 * Reads are TOLERANT: a blob without the [MAGIC] prefix is a legacy plaintext file (or, defensively,
 * anything not written by us) and is returned verbatim — so shipping this never breaks an attachment
 * that already exists on someone's device. New writes always encrypt, and [migrateLegacyPlaintext]
 * upgrades the old files in place, idempotently (it fingerprints by header, so a second run is cheap).
 *
 * Key-loss caveat mirrors [SecureDb]: uninstall / factory reset / most device transfers destroy the
 * KeyStore key. If it is gone the encrypted files can't be read — the JSON backup (which stores the
 * bytes as Base64, decrypted on the way out via the repository's hydration) is the recovery path.
 */
object FileVault {
    private const val KS_PROVIDER = "AndroidKeyStore"
    private const val KS_ALIAS = "kairo_file_vault_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    // "KVF1" — Kairo Vault File v1. A 4-byte marker chosen not to collide with the signatures of the
    // formats we actually store (JPEG FF D8 FF, PNG 89 50 4E 47, PDF 25 50 44 46), so a legacy plaintext
    // image is never mistaken for one of our envelopes.
    private val MAGIC = byteArrayOf(0x4B, 0x56, 0x46, 0x31)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KS_PROVIDER).apply { load(null) }
        (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS_PROVIDER)
        fun spec(strongBox: Boolean, requireUnlocked: Boolean) = KeyGenParameterSpec.Builder(
            KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
                // SEC (Batch 4) — bind the key's usability to an UNLOCKED device (API 28+). Attachments and
                // habit photos are only ever decrypted in the foreground (a viewer has to unlock to see the
                // screen), so this never blocks a background task — unlike the DB-passphrase key, which
                // reminders/widgets must use while the device is locked and which is therefore NOT gated.
                // The result: a lost/stolen phone that is locked (or off) can't decrypt these files even as
                // the app's own process. Deliberately NOT invalidatedByBiometricEnrollment — a fingerprint
                // change must never orphan a user's existing attachments.
                if (requireUnlocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setUnlockedDeviceRequired(true)
            }
            .build()
        // Prefer StrongBox + the unlocked-device gate, then degrade gracefully so no device is ever left
        // unable to encrypt attachments: StrongBox+gate → TEE+gate → TEE (no gate, e.g. pre-API-28).
        for ((sb, ru) in listOf(true to true, false to true, false to false)) {
            runCatching { kg.init(spec(sb, ru)); return kg.generateKey() }
        }
        // Unreachable in practice (the last option always succeeds); a final plain attempt satisfies the compiler.
        kg.init(spec(strongBox = false, requireUnlocked = false)); return kg.generateKey()
    }

    /** SEC (Batch 6) — irreversibly delete the file-vault KeyStore key, part of the app's panic wipe. After
     *  this, every FileVault envelope on disk is permanently undecryptable. Best-effort; never throws. */
    fun evictKey() {
        runCatching {
            val ks = KeyStore.getInstance(KS_PROVIDER).apply { load(null) }
            ks.deleteEntry(KS_ALIAS)
        }
    }

    /** Encrypt [plain] into a FileVault envelope (MAGIC ‖ iv ‖ ciphertext+tag). */
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv                       // GCM: the provider generated a random 12-byte IV
        val ct = cipher.doFinal(plain)
        val out = ByteArray(MAGIC.size + iv.size + ct.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        System.arraycopy(iv, 0, out, MAGIC.size, iv.size)
        System.arraycopy(ct, 0, out, MAGIC.size + iv.size, ct.size)
        return out
    }

    private fun isEnvelope(blob: ByteArray): Boolean {
        if (blob.size < MAGIC.size + IV_LEN) return false
        for (i in MAGIC.indices) if (blob[i] != MAGIC[i]) return false
        return true
    }

    /** Decrypt a FileVault envelope. A blob without the [MAGIC] prefix (legacy plaintext) is returned
     *  as-is, so this is always safe to call on any stored file. */
    fun decrypt(blob: ByteArray): ByteArray {
        if (!isEnvelope(blob)) return blob
        val iv = blob.copyOfRange(MAGIC.size, MAGIC.size + IV_LEN)
        val ct = blob.copyOfRange(MAGIC.size + IV_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    /** Write [bytes] to [file], encrypted. */
    fun writeEncrypted(file: File, bytes: ByteArray) = file.writeBytes(encrypt(bytes))

    /** Read [file] and decrypt it, tolerating a legacy plaintext file (returned as-is). */
    fun readDecrypted(file: File): ByteArray = decrypt(file.readBytes())

    /** True if [file] already begins with our envelope marker — read only the header, so this stays
     *  cheap to call on every start for a directory of large attachments. */
    private fun looksEncrypted(file: File): Boolean {
        if (file.length() < MAGIC.size + IV_LEN) return false
        return runCatching {
            file.inputStream().use { ins ->
                val head = ByteArray(MAGIC.size)
                var n = 0
                while (n < head.size) {
                    val r = ins.read(head, n, head.size - n); if (r < 0) break; n += r
                }
                n == head.size && head.contentEquals(MAGIC)
            }
        }.getOrDefault(false)
    }

    /**
     * One-shot, idempotent upgrade of any legacy plaintext files under [dirs] to encrypted-at-rest.
     * Safe to run on every start: a file already carrying the marker is skipped after a 4-byte header
     * read. Encrypts via a temp file + atomic rename so an interrupted run never corrupts a file. Any
     * per-file failure is swallowed — the tolerant reader still handles whatever state a file is in.
     * Must be called off the main thread (it touches disk).
     */
    fun migrateLegacyPlaintext(dirs: List<File>) {
        for (dir in dirs) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                if (looksEncrypted(f)) continue
                runCatching {
                    val enc = encrypt(f.readBytes())
                    val tmp = File(f.parentFile, f.name + ".enc.tmp")
                    tmp.writeBytes(enc)
                    if (!tmp.renameTo(f)) { f.writeBytes(enc); tmp.delete() }
                }
            }
        }
    }
}
