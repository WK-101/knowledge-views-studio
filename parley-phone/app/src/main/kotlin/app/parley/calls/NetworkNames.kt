package app.parley.calls

import android.content.Context
import app.parley.common.PhoneIdentity
import app.parley.common.calls.NetworkName
import app.parley.common.calls.NetworkNameSeen
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The names the network sent, for the lists that show numbers (Recents, Recall): a reader per change of the store or
 * of the private contacts. A private contact's number never gets one, whether discreet mode is on or not; the reader
 * waits for the private contacts to be listed, so it never shows one first.
 */
object NetworkNames {
    /** Number → the names the network sent for it, newest first (empty for none). Ask with [line]. */
    fun readers(c: DataContainer): Flow<(String) -> List<NetworkNameSeen>> =
        combine(c.networkNames.version, c.vault.listing.filterNotNull()) { _, vault ->
            val privateNumbers = PhoneIdentity.LineSet(vault.flatMap { it.numbers }, PhoneEnv.countryIso(c.appContext))
            runCatching { c.networkNames.reader { it in privateNumbers } }.getOrElse { { _: String -> emptyList() } }
        }.flowOn(Dispatchers.IO)

    /** No names: until the readers are ready. */
    val NONE: (String) -> List<NetworkNameSeen> = { emptyList() }

    /** The line a call's name is kept under: [number] as the SIM of [accountId] reads it, as it was written. */
    fun line(context: Context, number: String, accountId: String?): String =
        NetworkName.line(number, PhoneEnv.countryIso(context, accountId))

    private val pending = ConcurrentHashMap.newKeySet<Job>()

    /** A name being written as a call ends: [settle] waits for it. */
    fun track(job: Job) {
        pending += job
        job.invokeOnCompletion { pending -= job }
    }

    /**
     * Waits (at most [timeoutMs]) for the names still being written: Telecom's missed-call broadcast and the end of the
     * call reach Parley separately, and the notification shouldn't miss the name of a call that just ended.
     */
    suspend fun settle(timeoutMs: Long = SETTLE_MS) {
        val jobs = pending.toList()
        if (jobs.isNotEmpty()) withTimeoutOrNull(timeoutMs) { jobs.joinAll() }
    }

    private const val SETTLE_MS = 3_000L
}
