package com.todocompanion.app.domain.done

import java.security.MessageDigest

/**
 * Phase 7 — a verifiable, tamper-evident timeline over the record, entirely on-device. Each completed
 * entry seals the one before it in a SHA-256 hash-chain; sealing stores the current head, so a later
 * back-date, edit or deletion of any sealed entry changes the recomputed head and is caught. No server,
 * no account, no blockchain, no network — just a local chain over a record you already own outright.
 */
object Integrity {
    /** A stored proof: the head hash over the first [count] entries at the moment the user sealed. */
    data class Seal(val count: Int, val head: String, val sealedAt: Long) {
        fun encode() = "$count:$head:$sealedAt"
        companion object {
            fun decode(s: String?): Seal? {
                val p = s?.takeIf { it.isNotBlank() }?.split(":") ?: return null
                if (p.size != 3) return null
                return Seal(p[0].toIntOrNull() ?: return null, p[1], p[2].toLongOrNull() ?: return null)
            }
        }
    }

    enum class State { UNSEALED, VERIFIED, TAMPERED }
    data class Status(
        val state: State, val head: String, val total: Int,
        val sealedCount: Int, val newSinceSeal: Int, val sealedAt: Long?,
    )

    private fun sha(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) { val v = b.toInt() and 0xff; sb.append(hex[v ushr 4]); sb.append(hex[v and 0x0f]) }
        return sb.toString()
    }

    /** Per-entry hash sealing the previous head. Depends only on stored fields, so it is stable across runs. */
    fun entryHash(prev: String, a: Accomplishment): String =
        sha("$prev|${a.kind}|${a.refId}|${a.whenMillis}|${a.title}")

    /** The chain head over the first [count] entries, oldest-first. */
    fun headOf(ascending: List<Accomplishment>, count: Int = ascending.size): String {
        var h = "GENESIS"
        val n = count.coerceIn(0, ascending.size)
        for (i in 0 until n) h = entryHash(h, ascending[i])
        return h
    }

    fun status(items: List<Accomplishment>, seal: Seal?): Status {
        val asc = items.sortedBy { it.whenMillis }
        val head = headOf(asc)
        if (seal == null) return Status(State.UNSEALED, head, asc.size, 0, 0, null)
        val recomputed = headOf(asc, seal.count)
        // Verified only if the sealed prefix still hashes to the sealed head AND all sealed entries survive.
        val state = if (recomputed == seal.head && asc.size >= seal.count) State.VERIFIED else State.TAMPERED
        return Status(state, head, asc.size, seal.count, (asc.size - seal.count).coerceAtLeast(0), seal.sealedAt)
    }

    fun seal(items: List<Accomplishment>): Seal {
        val asc = items.sortedBy { it.whenMillis }
        return Seal(asc.size, headOf(asc), System.currentTimeMillis())
    }

    /** A short, human-facing fingerprint for one accomplishment — printed on its proof-of-work receipt. */
    fun fingerprint(a: Accomplishment): String =
        sha("${a.refId}|${a.whenMillis}").take(12).uppercase().chunked(4).joinToString("-")
}
