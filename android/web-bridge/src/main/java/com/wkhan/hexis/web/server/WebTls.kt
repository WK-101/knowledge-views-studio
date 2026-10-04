package com.wkhan.hexis.web.server

import android.content.Context

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
 * The cert is self-signed, so the browser shows a one-time warning the user clicks through (standard for
 * self-hosted LAN tools). Persisting the keystore means that warning + the cert identity stay stable across
 * restarts instead of changing every time. The keystore password is local-only — it guards a file in
 * app-private storage, not a secret that crosses any boundary.
 */
object WebTls {

    const val ALIAS = "hexis"
    const val PASSWORD = "hexis-web-local"

    fun keyStore(context: Context): KeyStore {
        val file = File(context.applicationContext.filesDir, "hexis-web-tls.jks")
        if (file.exists()) {
            runCatching {
                return KeyStore.getInstance("JKS").apply {
                    file.inputStream().use { load(it, PASSWORD.toCharArray()) }
                }
            }
        }
        val ks = buildKeyStore {
            certificate(ALIAS) {
                password = PASSWORD
                domains = listOf("127.0.0.1", "localhost")
                keySizeInBits = KEY_BITS
            }
        }
        runCatching { file.outputStream().use { ks.store(it, PASSWORD.toCharArray()) } }
        return ks
    }

    private const val KEY_BITS = 2048
}
