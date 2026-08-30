package com.todocompanion.app.domain.done

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Frontier F3 — peer co-sign: two phones, no cloud. Each device holds an EC keypair in the Android
 * KeyStore. It can sign a proof's verify token (witnessing "yes, I saw you finish this") and verify a
 * co-signature someone else produced — entirely offline, moved as a short token or a QR. The identity is
 * the public key itself (self-asserted); the guarantee is tamper-evidence, not a certificate authority.
 */
object PeerSign {
    private const val ALIAS = "todo_cosign_ec"
    private const val KS = "AndroidKeyStore"

    private fun ks(): KeyStore = KeyStore.getInstance(KS).apply { load(null) }

    private fun ensureKey() {
        val store = ks()
        if (store.containsAlias(ALIAS)) return
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KS)
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        gen.generateKeyPair()
    }

    private fun pubKeyBytes(): ByteArray {
        ensureKey()
        return ks().getCertificate(ALIAS).publicKey.encoded
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP or Base64.URL_SAFE)
    private fun shortId(pub: ByteArray) = MessageDigest.getInstance("SHA-256").digest(pub).take(6).joinToString("") { "%02x".format(it) }.uppercase()

    /** A short, stable id for this device's signing key. */
    fun deviceId(): String = runCatching { shortId(pubKeyBytes()) }.getOrDefault("——")

    /** Produce a co-signature token over [payload] (a receipt's verify string). */
    fun coSign(payload: String): String? = runCatching {
        ensureKey()
        val entry = ks().getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(entry.privateKey)
        sig.update(payload.toByteArray(Charsets.UTF_8))
        val signature = sig.sign()
        "COSIGN|${b64(payload.toByteArray())}|${b64(pubKeyBytes())}|${b64(signature)}|${System.currentTimeMillis()}"
    }.getOrNull()

    data class Verified(val payload: String, val signerId: String, val at: Long)

    /** Verify a co-signature token; null if malformed or the signature doesn't check out. */
    fun verify(token: String): Verified? {
        val p = token.trim().split("|")
        if (p.size != 5 || p[0] != "COSIGN") return null
        return runCatching {
            val payload = String(unb64(p[1]), Charsets.UTF_8)
            val pub = unb64(p[2]); val signature = unb64(p[3]); val at = p[4].toLong()
            val pk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pk); sig.update(payload.toByteArray(Charsets.UTF_8))
            if (!sig.verify(signature)) null else Verified(payload, shortId(pub), at)
        }.getOrNull()
    }
}
