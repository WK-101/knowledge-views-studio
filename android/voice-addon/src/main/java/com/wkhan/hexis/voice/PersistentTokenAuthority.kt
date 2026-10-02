package com.wkhan.hexis.voice

import android.content.Context

import com.wkhan.hexis.bridge.security.GrantToken
import com.wkhan.hexis.bridge.security.TokenAuthority
import com.wkhan.hexis.bridge.security.TokenVerdict

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

import java.security.SecureRandom
import java.util.Base64

/**
 * A [TokenAuthority] whose grants survive process death by persisting to the addon's private
 * SharedPreferences.
 *
 * The in-memory authority lost every minted token when the addon process was reclaimed after the
 * consent activity finished; a later call from the core then presented a token this fresh process
 * had never seen, which verified as UNKNOWN ("invalid or missing token"). Persisting the grants
 * fixes that: consent mints + stores, and the bridge service — even in a brand-new process — loads
 * and verifies against the same store.
 *
 * The tokens live only in the addon's own private storage; a token value only ever leaves the addon
 * to reach the core, which the bridge has already verified by signing keyset.
 */
class PersistentTokenAuthority(context: Context) : TokenAuthority {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
        const val PREFS = "hexis_voice_grants"
        const val KEY = "tokens"
        const val TOKEN_BYTES = 32 // 256-bit opaque, unguessable token value
    }
}
