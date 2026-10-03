package app.parley.ui.people

import app.parley.common.people.ContactRef
import app.parley.common.people.ContactSearch
import app.parley.data.DataContainer
import app.parley.data.ContactDetails
import app.parley.data.PhoneEnv
import app.parley.data.people.DetailsSearch
import app.parley.data.vault.VaultCrypto
import app.parley.data.vault.VaultSummary
import app.parley.security.AppLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Private contacts' details for the Contacts search and filters, while a search or filter is in use. Their sealed
 * details are opened (the vault's own unlock, as on their page) and turned into search docs held in memory only:
 * nothing is indexed on disk. The docs are dropped when the search and filters close, in discreet mode, while Parley's
 * app lock is engaged, and whenever opened details are forgotten (the lock, the screen going off); after that they
 * are opened again only when asked ([retry]). Until then private contacts are found by name and number, as listed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateSearch(
    private val c: DataContainer,
    scope: CoroutineScope,
    /** A search or a filter is in use, and private contacts are listed (discreet mode off). */
    wanted: StateFlow<Boolean>,
) {
    private val _docs = MutableStateFlow<Map<Long, ContactSearch.Doc>>(emptyMap())

    /** Docs by list id (negative), for the private contacts whose details are open. */
    val docs: StateFlow<Map<Long, ContactSearch.Doc>> = _docs

    private val _locked = MutableStateFlow(false)

    /** Some private contacts' details couldn't be opened without the vault's unlock: only their name and number are searched. */
    val locked: StateFlow<Boolean> = _locked

    private val asks = MutableStateFlow(0)

    /** Opens the details again (after the vault's unlock succeeded). */
    fun retry() {
        asks.value++
    }

    init {
        scope.launch {
            var forgetsSeen = c.vault.forgets.value
            var asked = asks.value
            // Opened details were forgotten while searching: wait for the unlock rather than open them again.
            var held = false
            combine(wanted, AppLock.locked, c.vault.contacts, c.vault.forgets, asks) { on, appLocked, list, forgets, ask ->
                Snapshot(on && !appLocked, list, forgets, ask)
            }.distinctUntilChanged().collectLatest { s ->
                if (s.ask != asked) held = false
                if (s.forgets != forgetsSeen && s.ask == asked) held = true
                forgetsSeen = s.forgets
                asked = s.ask
                if (!s.on || s.list.isEmpty()) {
                    held = false
                    _docs.value = emptyMap()
                    _locked.value = false
                    return@collectLatest
                }
                if (held) {
                    // The app lock, the screen off: these go with the opened details, until the unlock is asked for.
                    _docs.value = emptyMap()
                    _locked.value = true
                    return@collectLatest
                }
                open(s.list)
            }
        }
    }

    private data class Snapshot(val on: Boolean, val list: List<VaultSummary>, val forgets: Int, val ask: Int)

    private suspend fun open(list: List<VaultSummary>) {
        val region = PhoneEnv.countryIso(c.appContext)
        val ids = list.map { ContactRef.Private(it.id).navId }.toSet()
        val out = HashMap(_docs.value.filterKeys { it in ids })
        val labels = c.privateLabels.titles.value
        val locked = try {
            for ((i, v) in list.withIndex()) {
                detailsOf(v.id)?.let { d ->
                    val id = ContactRef.Private(v.id).navId
                    out[id] = DetailsSearch.doc(id, d, labels[v.id] ?: v.labels.map { it.title }, region, v.name)
                }
                // Shown as they open, a few at a time (each opening may take a moment in secure hardware).
                if (i % PUBLISH_EVERY == PUBLISH_EVERY - 1) _docs.value = HashMap(out)
                yield()
            }
            false
        } catch (_: VaultCrypto.LockedException) {
            true
        }
        _docs.value = out
        _locked.value = locked
    }

    /**
     * Entry [vaultId]'s details; throws [VaultCrypto.LockedException] when the vault must be unlocked first. Null when
     * they can't be opened right now (the Keystore is busy): that contact is found by name and number, as listed.
     */
    private suspend fun detailsOf(vaultId: Long): ContactDetails? {
        val r = runCatching { c.vault.details(vaultId) }
        when (val e = r.exceptionOrNull()) {
            is VaultCrypto.LockedException, is CancellationException -> throw e
        }
        return r.getOrNull()
    }

    private companion object {
        const val PUBLISH_EVERY = 8
    }
}
