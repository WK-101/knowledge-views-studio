package app.parley.data.vault

import android.content.Context
import app.parley.data.db.VaultCallerRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The private contacts' list rows ([VaultSummary]) as [VaultRepository] lists them: opened from each caller-ID copy once
 * and remembered with the copy they came from, so every change to the table lists the vault again without opening an
 * unchanged copy, and kept between runs ([PrivateSummaryCache]) so a cold start opens none of them one by one.
 */
internal class PrivateRows(context: Context) {
    /** Summaries already opened, by entry, with the caller-ID copy they came from. */
    private val summaries = ConcurrentHashMap<Long, Pair<ByteArray, VaultSummary>>()

    /** Each opened summary's row as [PrivateSummaryCache] keeps it, with the digest of the copy it came from. */
    private val parts = ConcurrentHashMap<Long, PrivateSummaryCache.Entry>()

    private val cache = PrivateSummaryCache(context)

    /** What [cache] held at start, read once by the first listing (null until then). */
    @Volatile private var kept: Map<Long, PrivateSummaryCache.Entry>? = null

    /**
     * Each row's summary, in one batch. A cold start takes the rows kept from the last run (one file, one Keystore
     * operation for all of them) wherever the caller-ID copy is unchanged; only the copies not kept, or changed since,
     * are opened, each a Keystore operation of a few milliseconds, by a few workers at once when there are many. Later
     * listings open only the copies that changed, and the kept rows follow.
     */
    suspend fun all(rows: List<VaultCallerRow>): List<VaultSummary> {
        var fresh = rows.filter { r -> summaries[r.id]?.first?.contentEquals(r.callerIdBlob) != true }
        if (fresh.isNotEmpty()) {
            val fromLastRun = kept ?: cache.load().also { kept = it }
            if (fromLastRun.isNotEmpty()) fresh = fresh.filterNot { r -> fromKept(r, fromLastRun[r.id]) }
        }
        if (fresh.size >= OPEN_TOGETHER_FROM) {
            val per = (fresh.size + OPENERS - 1) / OPENERS
            coroutineScope { fresh.chunked(per).map { chunk -> async(Dispatchers.IO) { chunk.forEach { one(it) } } }.awaitAll() }
        }
        val out = rows.mapNotNull { one(it) }
        keep(rows)
        return out
    }

    /** Row [e]'s summary: remembered when its copy is unchanged, else opened (a Keystore operation). Null if unreadable. */
    fun one(e: VaultCallerRow): VaultSummary? {
        summaries[e.id]?.let { (blob, s) -> if (blob.contentEquals(e.callerIdBlob)) return s.copy(expiresAt = e.expiresAt) }
        return open(e)
    }

    /** Entry [id] was deleted. */
    fun forget(id: Long) {
        summaries.remove(id)
        parts.remove(id)
    }

    /** Drops the kept rows' unwrapped key (with opened details: the app lock, the screen off, "Lock private contacts"). */
    fun forgetKey() = cache.forgetKey()

    /** Row [r]'s summary from [kept] when it was kept from the very caller-ID copy [r] holds now. */
    private fun fromKept(r: VaultCallerRow, kept: PrivateSummaryCache.Entry?): Boolean {
        if (kept == null || kept.digest != PrivateSummaryCache.digest(r.callerIdBlob)) return false
        val s = runCatching { CallerIdCopy.summary(r.id, kept.part, r.expiresAt, r.createdAt) }.getOrNull() ?: return false
        summaries[r.id] = r.callerIdBlob to s
        parts[r.id] = kept
        return true
    }

    /** Keeps the rows of [rows] for the next cold start (written only when they changed). */
    private fun keep(rows: List<VaultCallerRow>) {
        val out = HashMap<Long, PrivateSummaryCache.Entry>(rows.size)
        for (r in rows) parts[r.id]?.let { out[r.id] = it }
        cache.save(out)
    }

    private fun open(e: VaultCallerRow): VaultSummary? = runCatching {
        val o = JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob)))
        val s = CallerIdCopy.summary(e.id, o, e.expiresAt, e.createdAt)
        summaries[e.id] = e.callerIdBlob to s
        parts[e.id] = PrivateSummaryCache.Entry(PrivateSummaryCache.digest(e.callerIdBlob), CallerIdCopy.summaryPart(o))
        s
    }.getOrNull()

    private companion object {
        /** Caller-ID copies opened by several workers from this many on, and how many workers. */
        const val OPEN_TOGETHER_FROM = 16
        const val OPENERS = 4
    }
}
