package com.wkhan.hexis.bridge

import com.wkhan.hexis.bridge.security.InMemoryTokenAuthority
import com.wkhan.hexis.bridge.security.TokenVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Phase 0 — scoped, revocable, expiring grant tokens: the consent artifact the dispatcher enforces. */
class TokenAuthorityTest {

    @Test fun mintedToken_verifiesForItsSubjectAndScope() {
        val authority = InMemoryTokenAuthority()
        val token = authority.mint("com.wkhan.hexis", setOf(BridgeScopes.VOICE_STT_LISTEN))
        assertEquals(TokenVerdict.OK, authority.verify(token.value, "com.wkhan.hexis", BridgeScopes.VOICE_STT_LISTEN))
    }

    @Test fun wrongSubject_isRejected() {
        val authority = InMemoryTokenAuthority()
        val token = authority.mint("com.wkhan.hexis", setOf(BridgeScopes.VOICE_STT_LISTEN))
        assertEquals(TokenVerdict.WRONG_SUBJECT, authority.verify(token.value, "com.other.app", BridgeScopes.VOICE_STT_LISTEN))
    }

    @Test fun missingScope_isReported() {
        val authority = InMemoryTokenAuthority()
        val token = authority.mint("com.wkhan.hexis", setOf(BridgeScopes.TASKS_READ))
        assertEquals(TokenVerdict.MISSING_SCOPE, authority.verify(token.value, "com.wkhan.hexis", BridgeScopes.VOICE_STT_LISTEN))
    }

    @Test fun missing_unknown_and_revoked_areDistinguished() {
        val authority = InMemoryTokenAuthority()
        assertEquals(TokenVerdict.MISSING, authority.verify(null, "x", null))
        assertEquals(TokenVerdict.UNKNOWN, authority.verify("not-a-real-token", "x", null))
        val token = authority.mint("x", emptySet())
        authority.revoke(token.value)
        assertEquals(TokenVerdict.UNKNOWN, authority.verify(token.value, "x", null))
    }

    @Test fun expiry_isEnforced() {
        var now = 1_000L
        val authority = InMemoryTokenAuthority(clock = { now })
        val token = authority.mint("x", setOf("s"), ttlMs = 100L)
        assertEquals(TokenVerdict.OK, authority.verify(token.value, "x", "s", now))
        now = 1_101L
        assertEquals(TokenVerdict.EXPIRED, authority.verify(token.value, "x", "s", now))
    }

    @Test fun revokeAll_clearsOnlyThatSubject() {
        val authority = InMemoryTokenAuthority()
        val mine = authority.mint("com.wkhan.hexis", setOf("s"))
        val other = authority.mint("com.other.app", setOf("s"))
        authority.revokeAll("com.wkhan.hexis")
        assertEquals(TokenVerdict.UNKNOWN, authority.verify(mine.value, "com.wkhan.hexis", "s"))
        assertEquals(TokenVerdict.OK, authority.verify(other.value, "com.other.app", "s"))
    }

    @Test fun tokenValues_areUnique() {
        val authority = InMemoryTokenAuthority()
        assertNotEquals(authority.mint("x", emptySet()).value, authority.mint("x", emptySet()).value)
    }
}
