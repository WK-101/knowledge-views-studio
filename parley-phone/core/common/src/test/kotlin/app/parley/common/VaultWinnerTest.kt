package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultWinnerTest {
    @Test fun newest_unexpired_vault_entry_wins() {
        val now = 1_000_000L
        val a = VaultNumberKeys.Candidate(id = 1, updatedAt = 500, createdAt = 100, expiresAt = null)
        val b = VaultNumberKeys.Candidate(id = 2, updatedAt = 900, createdAt = 50, expiresAt = null)
        val expired = VaultNumberKeys.Candidate(id = 3, updatedAt = 999, createdAt = 999, expiresAt = now - 1)
        assertEquals(2L, VaultNumberKeys.winner(listOf(a, b, expired), now)?.id)
        assertEquals(2L, VaultNumberKeys.winner(listOf(b, a), now)?.id) // order doesn't matter
        assertNull(VaultNumberKeys.winner(listOf(expired), now))
        // Ties are broken by creation time, then id.
        val c = VaultNumberKeys.Candidate(id = 4, updatedAt = 900, createdAt = 50, expiresAt = now + 1)
        assertEquals(4L, VaultNumberKeys.winner(listOf(b, c), now)?.id)
    }
}
