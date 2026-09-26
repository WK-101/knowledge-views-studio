package com.wkhan.hexis.data.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SEC (R2-A) — a tiny KeyStore-wrapped key/value store for device-local secrets that must NOT sit in the
 * Room settings table in cleartext. Today it holds the sync/backup passphrase: a secret that encrypts
 * off-device backups but, until now, was stored as a plain settings row whose only protection was the DB
 * being SQLCipher-encrypted (default-on for fresh installs, off for existing/opted-out users).
 *
 * Each value is AES-256-GCM under a hardware-backed AndroidKeyStore key (StrongBox preferred), stored —
 * wrapped — in an app-private SharedPreferences file (`secure_prefs_v1`, excluded from cloud backup and
 * device transfer via data_extraction_rules). The plaintext value never touches the DB or a backup file.
 *
 * Same wrap/unwrap construction as [SecureDb]'s passphrase wrap, under a distinct alias. Fully offline.
 */
object SecurePrefs {
    private const val PREFS = "secure_prefs_v1"
    private const val KS_PROVIDER = "AndroidKeyStore"
    private const val KS_ALIAS = "hexis_secure_prefs_key"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Store [value] wrapped under [key]; a blank value clears the entry. Returns true on success. */
    fun putSecret(context: Context, key: String, value: String): Boolean = runCatching {
        if (value.isEmpty()) { clear(context, key); return true }
        val (iv, ct) = wrap(value.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString("$key.ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString("$key.iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
        true
    }.getOrDefault(false)

    /** Unwrap the value stored under [key], or null if absent / unreadable. */
    fun getSecret(context: Context, key: String): String? {
        val p = prefs(context)
        val ctB64 = p.getString("$key.ct", null) ?: return null
        val ivB64 = p.getString("$key.iv", null) ?: return null
        return runCatching {
            val ct = Base64.decode(ctB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            String(unwrap(iv, ct), Charsets.UTF_8)
        }.getOrNull()
    }

    /** True when a (non-empty) value is stored under [key]. Cheap; no unwrap. */
    fun has(context: Context, key: String): Boolean =
        prefs(context).getString("$key.ct", null) != null

    fun clear(context: Context, key: String) {
        prefs(context).edit().remove("$key.ct").remove("$key.iv").apply()
    }

    /** Wipe every stored secret — used by the panic wipe. */
    fun clearAll(context: Context) {
        runCatching { prefs(context).edit().clear().apply() }
        runCatching {
            val ks = KeyStore.getInstance(KS_PROVIDER).apply { load(null) }
            ks.deleteEntry(KS_ALIAS)
        }
    }

    // ── KeyStore wrap key (StrongBox-preferred, TEE fallback), same shape as SecureDb ──────────────
    private fun getOrCreateWrapKey(): SecretKey {
        val ks = KeyStore.getInstance(KS_PROVIDER).apply { load(null) }
        (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS_PROVIDER)
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        return try {
            kg.init(spec(true)); kg.generateKey()
        } catch (_: Exception) {
            kg.init(spec(false)); kg.generateKey()
        }
    }

    private fun wrap(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        return cipher.iv to cipher.doFinal(plain)
    }

    private fun unwrap(iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }
}
