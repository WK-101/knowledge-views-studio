package com.wkhan.hexis.bridge.security

import android.content.Context

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

import java.security.SecureRandom
import java.util.Base64

/**
 * A [TokenAuthority] whose grants survive process death by persisting to the owning app's private
 * SharedPreferences. Used by every bridge *provider* that mints grant tokens — the voice addon (for
 * `voice.stt`) and the core (for the `data` capability) — each with its own [prefsName] so their stores
 * never collide.
 *
 * Why persistence: an in-memory authority loses every minted token when the provider process is reclaimed
 * after consent finishes; a later call then presents a token the fresh process has never seen, verifying
 * as UNKNOWN ("invalid or missing token"). Persisting fixes that — consent mints + stores, and the bridge
 * service, even in a brand-new process, loads and verifies against the same store.
 *
 * Tokens live only in the provider's own private storage; a value only ever leaves to reach the consumer,
 * whose signing keyset the bridge has already verified.
 */
class PersistentTokenAuthority(
    context: Context,
    prefsName: String = DEFAULT_PREFS,
) : TokenAuthority {

    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }
    private val tokens = linkedMapOf<String, GrantToken>()

    @Serializable
    private data class Dto(
        val value: String,
        val subjectPackage: String,
        val scopes: List<String>,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
    )

    init {
        load()
    }

    @Synchronized
    override fun mint(subjectPackage: String, scopes: Set<String>, ttlMs: Long): GrantToken {
        val now = System.currentTimeMillis()
        val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
        val value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val token = GrantToken(
            value = value,
            subjectPackage = subjectPackage,
            scopes = scopes.toSet(),
            issuedAtMs = now,
            expiresAtMs = if (ttlMs <= 0L) 0L else now + ttlMs,
        )
        tokens[value] = token
        save()
        return token
    }

    @Synchronized
    override fun verify(
        value: String?,
        subjectPackage: String,
        requiredScope: String?,
        nowMs: Long,
    ): TokenVerdict {
        if (value.isNullOrEmpty()) return TokenVerdict.MISSING
        val token = tokens[value] ?: return TokenVerdict.UNKNOWN
        if (token.isExpired(nowMs)) {
            tokens.remove(value)
            save()
            return TokenVerdict.EXPIRED
        }
        if (token.subjectPackage != subjectPackage) return TokenVerdict.WRONG_SUBJECT
        if (requiredScope != null && !token.hasScope(requiredScope)) return TokenVerdict.MISSING_SCOPE
        return TokenVerdict.OK
    }

    @Synchronized
    override fun resolve(value: String?): GrantToken? {
        if (value.isNullOrEmpty()) return null
        val token = tokens[value] ?: return null
        if (token.isExpired(System.currentTimeMillis())) {
            tokens.remove(value)
            save()
            return null
        }
        return token
    }

    @Synchronized
    override fun revoke(value: String) {
        if (tokens.remove(value) != null) save()
    }

    @Synchronized
    override fun revokeAll(subjectPackage: String) {
        if (tokens.values.removeAll { it.subjectPackage == subjectPackage }) save()
    }

    @Synchronized
    override fun activeTokens(): List<GrantToken> = tokens.values.toList()

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        runCatching {
            json.decodeFromString(ListSerializer(Dto.serializer()), raw).forEach {
                tokens[it.value] = GrantToken(
                    value = it.value,
                    subjectPackage = it.subjectPackage,
                    scopes = it.scopes.toSet(),
                    issuedAtMs = it.issuedAtMs,
                    expiresAtMs = it.expiresAtMs,
                )
            }
        }
    }

    private fun save() {
        val dto = tokens.values.map {
            Dto(it.value, it.subjectPackage, it.scopes.toList(), it.issuedAtMs, it.expiresAtMs)
        }
        prefs.edit().putString(KEY, json.encodeToString(ListSerializer(Dto.serializer()), dto)).apply()
    }

    private companion object {
        const val DEFAULT_PREFS = "hexis_bridge_grants"
        const val KEY = "tokens"
        const val TOKEN_BYTES = 32 // 256-bit opaque, unguessable token value
    }
}
