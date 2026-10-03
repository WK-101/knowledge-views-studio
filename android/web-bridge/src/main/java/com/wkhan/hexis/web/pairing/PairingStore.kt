package com.wkhan.hexis.web.pairing

import android.content.Context

import com.wkhan.hexis.web.crypto.CryptoBox

import java.security.SecureRandom

/**
 * The per-install pairing secret: a 256-bit key shown (as base64url / QR) on the phone and carried to the
 * browser out-of-band. Possession of it is what authorizes a web client; the browser never sends it — it
 * proves possession by producing AES-GCM blobs the phone can open (see [CryptoBox]). Persisted so it is
 * stable across restarts; the user can regenerate it to revoke every browser at once.
 */
class PairingStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The raw pairing key, generated on first use. */
    @Synchronized
    fun key(): ByteArray {
        prefs.getString(KEY, null)?.let { return CryptoBox.unb64(it) }
        val fresh = ByteArray(KEY_LEN).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, CryptoBox.b64(fresh)).apply()
        return fresh
    }

    /** base64url of the pairing key — what the QR/paste flow carries to the browser. */
    fun keyB64(): String = CryptoBox.b64(key())

    /** The symmetric AEAD key both sides derive from the pairing key (never the raw key itself). */
    fun aeadKey(): ByteArray = CryptoBox.hkdf(key(), AEAD_INFO)

    /** Revoke every paired browser by rotating the secret. */
    @Synchronized
    fun regenerate() {
        prefs.edit().remove(KEY).apply()
        key()
    }

    companion object {
        const val AEAD_INFO = "hexis-web-aead-v1"
        private const val PREFS = "hexis_web_pairing"
        private const val KEY = "pairing_key"
        private const val KEY_LEN = 32
    }
}
