package com.wkhan.hexis.bridge.security

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** A caller whose identity has been resolved from its Binder UID and checked against a pinned keyset. */
data class VerifiedCaller(
    val packageName: String,
    val uid: Int,
    val signatureTrusted: Boolean,
)

/**
 * A scoped, revocable, optionally-expiring grant. Minted by the granting side during consent and
 * presented (as the request header's token) on every call the grant authorizes.
 */
data class GrantToken(
    val value: String,
    val subjectPackage: String,
    val scopes: Set<String>,
    val issuedAtMs: Long,
    val expiresAtMs: Long, // 0 = never expires
) {
    fun isExpired(nowMs: Long): Boolean = expiresAtMs != 0L && nowMs >= expiresAtMs
    fun hasScope(scope: String): Boolean = scope in scopes
}

enum class TokenVerdict { OK, MISSING, UNKNOWN, EXPIRED, WRONG_SUBJECT, MISSING_SCOPE }

/**
 * Mints, verifies and revokes [GrantToken]s. The core persists these; the in-memory implementation
 * below is used by tests and as the default until persistence lands with the Registry.
 */
interface TokenAuthority {
    fun mint(subjectPackage: String, scopes: Set<String>, ttlMs: Long = 0L): GrantToken
    fun verify(
        value: String?,
        subjectPackage: String,
        requiredScope: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): TokenVerdict
    fun revoke(value: String)
    fun revokeAll(subjectPackage: String)
    fun activeTokens(): List<GrantToken>
}

class InMemoryTokenAuthority(
    private val clock: () -> Long = System::currentTimeMillis,
) : TokenAuthority {

    private val random = SecureRandom()
    private val tokens = ConcurrentHashMap<String, GrantToken>()

    override fun mint(subjectPackage: String, scopes: Set<String>, ttlMs: Long): GrantToken {
        val now = clock()
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
        return token
    }

    override fun verify(value: String?, subjectPackage: String, requiredScope: String?, nowMs: Long): TokenVerdict {
        if (value.isNullOrEmpty()) return TokenVerdict.MISSING
        val token = tokens[value] ?: return TokenVerdict.UNKNOWN
        if (token.isExpired(nowMs)) {
            tokens.remove(value)
            return TokenVerdict.EXPIRED
        }
        if (token.subjectPackage != subjectPackage) return TokenVerdict.WRONG_SUBJECT
        if (requiredScope != null && !token.hasScope(requiredScope)) return TokenVerdict.MISSING_SCOPE
        return TokenVerdict.OK
    }

    override fun revoke(value: String) {
        tokens.remove(value)
    }

    override fun revokeAll(subjectPackage: String) {
        tokens.values.removeIf { it.subjectPackage == subjectPackage }
    }

    override fun activeTokens(): List<GrantToken> = tokens.values.toList()

    private companion object {
        const val TOKEN_BYTES = 32 // 256-bit opaque, unguessable token value
    }
}
