package app.parley.calls

import app.parley.common.PhoneIdentity
import app.parley.common.calls.NetworkNameSeen
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn

/**
 * The names the network sent, for the lists that show numbers (Recents, favourites' frequent callers, Recall): a
 * reader per change of the store or of the private contacts. A private contact's number never gets one, whether
 * discreet mode is on or not; the reader waits for the private contacts to be listed, so it never shows one first.
 */
object NetworkNames {
    /** Number → the names the network sent for it, newest first (empty for none). */
    fun readers(c: DataContainer): Flow<(String) -> List<NetworkNameSeen>> =
        combine(c.networkNames.version, c.vault.listing.filterNotNull()) { _, vault ->
            val privateNumbers = PhoneIdentity.LineSet(vault.flatMap { it.numbers }, PhoneEnv.countryIso(c.appContext))
            runCatching { c.networkNames.reader { it in privateNumbers } }.getOrElse { { _: String -> emptyList() } }
        }.flowOn(Dispatchers.IO)

    /** No names: until the readers are ready. */
    val NONE: (String) -> List<NetworkNameSeen> = { emptyList() }
}
