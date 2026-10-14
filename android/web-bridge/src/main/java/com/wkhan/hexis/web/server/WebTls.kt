package com.wkhan.hexis.web.server

import android.content.Context
import android.util.Log

import io.ktor.network.tls.certificates.buildKeyStore

import java.io.File
import java.security.KeyStore

/**
 * A persistent self-signed TLS keystore for the local HTTPS server.
 *
 * HTTPS is **required**, not cosmetic: browsers expose WebCrypto's `crypto.subtle` only in a *secure
 * context*, and `http://<lan-ip>:port` is not one — so the app-layer AES-GCM (which the whole security model
 * depends on) simply can't run over plain HTTP to the phone's LAN address. Serving HTTPS makes the origin a
 * secure context so the crypto works, and adds a real transport-encryption layer under our E2E.
 *
 * Startup cost: the first-ever launch generates an RSA keypair, which can take up to a couple of seconds on
 * a phone (Ktor's `buildKeyStore` only does RSA — its `KeyType` selects the cert *role*, not EC). To keep
 * that off the "Start server" tap, [prewarm] generates/parses the keystore on a background thread (e.g. when
 * the control screen opens), and the parsed [KeyStore] is cached for the process. Subsequent starts just
 * reuse the cache; the persisted file keeps the cert identity stable across restarts.
 *
 * The cert is self-signed, so the browser shows a one-time warning the user clicks through (standard for
 * self-hosted LAN tools). The keystore password is local-only — it guards a file in app-private storage.
 */
object WebTls {

    const val ALIAS = "hexis"
    const val PASSWORD = "hexis-web-local"
    private const val TAG = "WebTls"
    private const val KEY_BITS = 2048

    @Volatile private var cached: KeyStore? = null
    private val lock = Any()

    /** The keystore, generated or loaded once and cached for the process. Safe to call from any thread. */
    fun keyStore(context: Context): KeyStore {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val started = System.currentTimeMillis()
            val ks = loadOrGenerate(context.applicationContext)
            cached = ks
            Log.i(TAG, "keystore ready in ${System.currentTimeMillis() - started}ms")
            return ks
        }
    }

    /**
     * Prepare the keystore off the critical path. Call when the control screen opens so the first-run RSA
     * keygen + PKCS#12 parse never block the user's "Start server" tap. Idempotent and cheap once cached.
     */
    fun prewarm(context: Context) {
        if (cached != null) return
        val app = context.applicationContext
        Thread({ runCatching { keyStore(app) }.onFailure { Log.w(TAG, "prewarm failed", it) } }, "hexis-tls-prewarm")
            .apply { isDaemon = true }
            .start()
    }

    private fun loadOrGenerate(context: Context): KeyStore {
        val file = File(context.filesDir, "hexis-web-tls.jks")
        if (file.exists()) {
            runCatching {
                return KeyStore.getInstance("JKS").apply {
                    file.inputStream().use { load(it, PASSWORD.toCharArray()) }
                }
            }.onFailure { Log.w(TAG, "keystore reload failed; regenerating", it) }
        }
        val ks = buildKeyStore {
            certificate(ALIAS) {
                password = PASSWORD
                domains = listOf("127.0.0.1", "localhost")
                keySizeInBits = KEY_BITS
            }
        }
        runCatching { file.outputStream().use { ks.store(it, PASSWORD.toCharArray()) } }
            .onFailure { Log.w(TAG, "keystore persist failed", it) }
        return ks
    }
}
