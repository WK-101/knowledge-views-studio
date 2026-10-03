package com.wkhan.hexis.web.pairing

import android.content.Context

import com.wkhan.hexis.web.crypto.CryptoBox

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import java.security.SecureRandom

/**
 * A paired browser (a "client"). Each gets its OWN 256-bit key, so a device can be revoked on its own
 * without re-pairing the others, and a [readOnly] / expiring client is a share link. Possession of the key
 * is authorization — the browser proves it by producing AES-GCM blobs the phone can open (see [CryptoBox]);
 * the key itself is never sent.
 */
@Serializable
data class WebClient(
    val id: String,
    val name: String,
    val keyB64: String,
    val readOnly: Boolean = false,
    val expiresAt: Long = 0L, // 0 = never
    val createdAt: Long = 0L,
) {
    fun expired(now: Long): Boolean = expiresAt in 1..now

    /** The AEAD key derived from this client's key (never the raw key itself). */
    fun aeadKey(): ByteArray = CryptoBox.hkdf(CryptoBox.unb64(keyB64), AEAD_INFO)

    companion object {
        const val AEAD_INFO = "hexis-web-aead-v1"
    }
}

/**
 * Persists the set of paired clients. Adding mints a fresh key; revoking a client (or letting a share link
 * expire) locks that browser out instantly, since the server only accepts a key that matches a live client.
 */
class ClientStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun list(): List<WebClient> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<WebClient>>(raw) }.getOrDefault(emptyList())
    }

    /** Clients that are still valid right now (unexpired). */
    fun active(now: Long = System.currentTimeMillis()): List<WebClient> = list().filter { !it.expired(now) }

    @Synchronized
    fun add(name: String, readOnly: Boolean = false, ttlMillis: Long = 0L): WebClient {
        val now = System.currentTimeMillis()
        val key = ByteArray(KEY_LEN).also { SecureRandom().nextBytes(it) }
        val client = WebClient(
            id = CryptoBox.b64(ByteArray(ID_LEN).also { SecureRandom().nextBytes(it) }),
            name = name.ifBlank { "Device" },
            keyB64 = CryptoBox.b64(key),
            readOnly = readOnly,
            expiresAt = if (ttlMillis > 0) now + ttlMillis else 0L,
            createdAt = now,
        )
        save(list() + client)
        return client
    }

    @Synchronized
    fun remove(id: String) = save(list().filterNot { it.id == id })

    @Synchronized
    fun clear() = prefs.edit().remove(KEY).apply()

    private fun save(clients: List<WebClient>) {
        prefs.edit().putString(KEY, json.encodeToString(clients)).apply()
    }

    private companion object {
        const val PREFS = "hexis_web_clients"
        const val KEY = "clients"
        const val KEY_LEN = 32
        const val ID_LEN = 6
    }
}

/** Process-wide "last seen" for each client id, written by the server and read by the control screen. */
object ClientPresence {
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun mark(id: String) { lastSeen[id] = System.currentTimeMillis() }
    fun lastSeen(id: String): Long = lastSeen[id] ?: 0L
}
