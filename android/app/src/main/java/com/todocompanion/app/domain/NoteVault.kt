package com.todocompanion.app.domain

import com.todocompanion.app.util.PortableCrypto

/**
 * L11 — the Vault's crypto contract. A vaulted note's body is a [PortableCrypto] envelope: AES-GCM under a
 * key derived (PBKDF2-HMAC-SHA256) from the user's vault passphrase. This is *real* encryption at rest —
 * the ciphertext is what lands in the DB, in backups, and in `.md` exports — and it is portable: unlike the
 * KeyStore-bound SQLCipher key, a passphrase-derived envelope decrypts on any device with the passphrase,
 * so a device change doesn't lose the vault. The passphrase is never stored; a [makeCheck]/[verify] token
 * lets an entered passphrase be validated without persisting it. Pure JVM, unit-testable.
 */
object NoteVault {
    private const val TOKEN = "KAIRO-VAULT-OK-v1"

    /** True if [body] is an encrypted vault envelope (vs. plaintext). */
    fun isLocked(body: String): Boolean = PortableCrypto.isEnvelope(body)

    /** Encrypt [plaintext] into a vault envelope with [pass]. */
    fun lock(plaintext: String, pass: CharArray): String = PortableCrypto.encrypt(plaintext, pass)

    /** Decrypt a vault [envelope] with [pass]; null on a wrong passphrase or a non-envelope. */
    fun unlock(envelope: String, pass: CharArray): String? =
        if (isLocked(envelope)) PortableCrypto.decrypt(envelope, pass) else envelope

    /** Build the stored verifier for a freshly-chosen passphrase (an encrypted known token). */
    fun makeCheck(pass: CharArray): String = PortableCrypto.encrypt(TOKEN, pass)

    /** True if [pass] is the passphrase the vault was set up with (decrypts the verifier to the token). */
    fun verify(check: String, pass: CharArray): Boolean =
        check.isNotBlank() && PortableCrypto.decrypt(check, pass) == TOKEN
}
