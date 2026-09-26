package com.wkhan.hexis.domain

import com.wkhan.hexis.util.PortableCrypto

/**
 * L11 — the Vault's crypto contract. A vaulted note's body is a [PortableCrypto] envelope: AES-GCM under a
 * key derived (PBKDF2-HMAC-SHA256) from the user's vault passphrase. This is *real* encryption at rest —
 * the ciphertext is what lands in the DB, in backups, and in `.md` exports — and it is portable: unlike the
 * KeyStore-bound SQLCipher key, a passphrase-derived envelope decrypts on any device with the passphrase,
 * so a device change doesn't lose the vault. The passphrase is never stored; a [makeCheck]/[verify] token
 * lets an entered passphrase be validated without persisting it. Pure JVM, unit-testable.
 *
 * SEC (R2-B/M2) — cost of a guess. The verifier is inherently a passphrase oracle: an attacker with the
 * DB/backup can grind guesses against the stored check (or, equally, against any real vaulted note, since
 * both are the same passphrase-derived AES-GCM envelope). We can't remove the oracle without removing the
 * portability that is the whole point of a passphrase vault — so the defence is per-guess cost, which now
 * rides on [PortableCrypto]'s raised KDF work factor (600k, and the Argon2id seam). [verifyByNote] is the
 * token-free alternative: validate by trial-decrypting an actual vaulted note, so no known-plaintext
 * verifier need be stored at all.
 */
object NoteVault {
    private const val TOKEN = "HEXIS-VAULT-OK-v1"
    // Pre-rebrand verifier token — still accepted on [verify] so a vault set up under an older build (and
    // carried in via a portable backup) keeps unlocking. New verifiers are always written with [TOKEN].
    private const val LEGACY_TOKEN = "KAIRO-VAULT-OK-v1"

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
    fun verify(check: String, pass: CharArray): Boolean {
        if (check.isBlank()) return false
        val decoded = PortableCrypto.decrypt(check, pass) ?: return false
        return decoded == TOKEN || decoded == LEGACY_TOKEN
    }

    /** SEC (R2-B/M2) — validate [pass] against a REAL vaulted note ([sampleEnvelope]) instead of a stored
     *  known-plaintext token: the GCM tag authenticates, so a correct passphrase decrypts and a wrong one
     *  returns null. Preferable to [verify] when at least one vaulted note exists, since it stores no extra
     *  oracle in the settings table / backups. Returns false for a blank or non-envelope sample. */
    fun verifyByNote(sampleEnvelope: String, pass: CharArray): Boolean =
        isLocked(sampleEnvelope) && PortableCrypto.decrypt(sampleEnvelope, pass) != null
}
