package app.parley.ui.people

import android.os.SystemClock
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
 * nothing is indexed on disk. They follow the vault's own rule for opened details: dropped when the search and
 * filters close, in discreet mode, while Parley's app lock is engaged, whenever opened details are forgotten (Parley
 * locks; the screen goes off, with or without the app lock), and once the search has been left alone for as long as the
 * vault keeps opened details ([app.parley.data.vault.VaultRepository.openedForMs]). After being forgotten they are
 * opened again only when asked ([retry]); after the idle time, at the next use of the search. Until then private
 * contacts are found by name and number, as listed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateSearch(
    private val c: DataContainer,
    scope: CoroutineScope,
    /** A search or a filter is in use, and private contacts are listed (discreet mode off). */
    wanted: StateFlow<Boolean>,
    /** Changes whenever the search is used (the query or the filters change). */
    activity: Flow<Any?>,
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

    /** The docs were dropped after the idle time: the next use of the search opens them again. */
    @Volatile private var expired = false

    init {
        scope.launch {
            activity.collectLatest {
                if (expired) {
                    expired = false
                    retry()
                }
                delay(c.vault.openedForMs)
                if (_docs.value.isNotEmpty()) {
                    _docs.value = emptyMap()
                    expired = true
                }
            }
        }
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
        var published = SystemClock.elapsedRealtime()
        val locked = try {
            for (v in list) {
                detailsOf(v.id)?.let { d ->
                    val id = ContactRef.Private(v.id).navId
                    out[id] = DetailsSearch.doc(id, d, labels[v.id] ?: v.labels.map { it.title }, region, v.name)
                }
                // Shown as they open when that takes a while (each opening may take a moment in secure hardware), but
                // at most every [PUBLISH_MS]: each publish rebuilds the whole list's search data.
                val now = SystemClock.elapsedRealtime()
                if (now - published >= PUBLISH_MS) {
                    _docs.value = HashMap(out)
                    published = now
                }
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
        const val PUBLISH_MS = 750L
    }
}
