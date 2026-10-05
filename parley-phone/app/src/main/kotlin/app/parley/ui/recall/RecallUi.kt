package app.parley.ui.recall

import app.parley.common.AppSettings
import app.parley.common.CallEntry
import app.parley.common.catching
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.memory.MemorySource
import app.parley.common.people.ContactListSearch
import app.parley.common.recall.RecallCorpus
import app.parley.common.recall.RecallEngine
import app.parley.common.recall.RecallHit
import app.parley.common.recall.RecallQuery
import app.parley.common.recall.RecallRanking
import app.parley.common.recall.RecallResult
import app.parley.common.recall.RecallSource
import app.parley.data.DataContainer
import app.parley.data.recall.RecallSources
import app.parley.data.vault.VaultCrypto
import app.parley.security.AppLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Recall: the Contacts search's "Search everything" mode, one search over everything Parley remembers (calls with
 * their dates and kinds, notes, promises, call notes, chats, deleted contacts, the snapshots and number memory), with
 * plain date and call words understood ([RecallQuery]). It runs when the chip is on, and on its own when the contact
 * list finds nobody for the words typed.
 *
 * The stores are read once when Recall starts and kept in memory while it is in use; each keystroke then searches
 * them off the main thread ([RecallEngine]), with older archived calls read by the date asked about. Private
 * contacts' calls, notes and deleted copies are searched only while they may be shown (the vault unlocked, private
 * contacts not hidden, Parley not locked); a duress unlock hides what it hides everywhere. Owned by
 * [app.parley.AppViewModel] (`vm.recall`).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class RecallUi(
    private val c: DataContainer,
    scope: CoroutineScope,
    /** The Contacts search's text. */
    private val query: StateFlow<String>,
    /** The contacts as the Contacts search prepared them (null until listed). */
    private val contacts: StateFlow<List<ContactListSearch.Entry>?>,
    /** What the contact list shows for the query (null until searched). */
    shown: StateFlow<List<ContactSummary>?>,
    /** Number → device contact by line, as Recents names calls. */
    private val numberIndex: StateFlow<PhoneIdentity.LineMap<ContactSummary>>,
    settings: StateFlow<AppSettings>,
) {
    private val sources = RecallSources(c)

    /** The "Search everything" chip. Cleared with the search. */
    val everything = MutableStateFlow(false)

    private val reloads = MutableStateFlow(0)

    /** Reads the stores again (after the vault's unlock, or a restore). */
    fun reload() {
        reloads.value++
    }

    /** Recall runs: something is typed, and the chip is on or the contact list found nobody. */
    val active: StateFlow<Boolean> = combine(query, everything, shown) { q, all, list ->
        q.isNotBlank() && (all || list?.isEmpty() == true)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    /** May private contacts' calls, notes and deleted copies be searched now? (Asked off the main thread.) */
    private val privacy: Flow<Privacy> = combine(
        settings.map { it.hideVault }.distinctUntilChanged(), AppLock.locked, c.vault.forgets, reloads,
        // Asked again once the private listing has loaded (a search right after a cold start) and at each unlock or lock.
        combine(c.vault.listing.map { it.orEmpty().isNotEmpty() }, c.vault.lock.unlocked, ::Pair).distinctUntilChanged(),
    ) { hidden, locked, _, _, (hasPrivate, _) ->
        if (hidden || locked) {
            Privacy(shown = false, locked = false)
        } else {
            val needsUnlock = withContext(Dispatchers.IO) { catching { VaultCrypto.detailNeedsUnlock() }.getOrDefault(true) }
            Privacy(shown = hasPrivate && !needsUnlock, locked = hasPrivate && needsUnlock)
        }
    }.distinctUntilChanged()

    private data class Privacy(val shown: Boolean, val locked: Boolean)

    /** The stored part, read when Recall starts (and again on [reload] or a privacy change); dropped when it stops. */
    private val stored: Flow<Pair<Privacy, RecallSources.Stored>?> = combine(active, privacy, reloads) { on, p, _ -> if (on) p else null }
        .distinctUntilChanged()
        .mapLatest { p -> p?.let { it to sources.load(RecallSources.Access(it.shown)) } }

    /** The engine over the stored part, the contacts and the calls in memory. */
    private val engine: Flow<Prepared?> = combine(stored, contacts, c.history.calls, c.vault.privateCalls) { s, list, _, _ -> s to list }
        .mapLatest { (s, list) ->
            val (p, st) = s ?: return@mapLatest null
            val access = RecallSources.Access(p.shown)
            val calls = sources.calls(access)
            val privateIds = if (p.shown) sources.privateIds() else null
            val corpus = RecallCorpus(list.orEmpty(), calls, st.notes, st.deleted, st.snapshots, st.messaged, st.cases, sources.region)
            Prepared(RecallEngine(corpus), p, sources.archiveWindowStart(calls), calls, privateIds)
        }.flowOn(Dispatchers.Default)

    private class Prepared(
        val engine: RecallEngine,
        val privacy: Privacy,
        val windowStart: Long?,
        val calls: List<CallEntry>,
        val privateIds: PhoneIdentity.LineMap<Long>?,
    ) {
        /** The calls in memory by number and time, so an archived call read again isn't listed twice. */
        val seen: Set<String> by lazy { calls.mapTo(HashSet(calls.size * 2)) { PhoneIdentity.callRowKey(it.number, it.date) } }
    }

    /** What the Recall section shows. */
    data class State(
        val result: RecallResult? = null,
        val searching: Boolean = false,
        /** Private contacts exist but are locked: their calls and notes are left out until the unlock. */
        val privateLocked: Boolean = false,
    )

    val state: StateFlow<State> = combine(engine, query.debounce(DEBOUNCE_MS)) { e, q -> e to q }
        .transformLatest { (e, q) ->
            if (e == null || q.isBlank()) {
                emit(State())
                return@transformLatest
            }
            emit(State(searching = true, privateLocked = e.privacy.locked))
            emit(State(search(e, q), privateLocked = e.privacy.locked))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), State())

    private suspend fun search(p: Prepared, text: String): RecallResult {
        val zone = ZoneId.systemDefault()
        val q = RecallQuery.parse(text, LocalDate.now(zone), Locale.getDefault())
        val index = numberIndex.value
        val contactOf: (String) -> Long? = { n -> index[n]?.id ?: p.privateIds?.get(n) }
        val hits = HashMap(p.engine.search(q, contactOf, LIMIT))
        olderCalls(p, q, contactOf, hits[RecallSource.CALL].orEmpty())?.let { hits[RecallSource.CALL] = it }
        remembered(q)?.let { hits[RecallSource.REMEMBERED] = it }
        return RecallRanking.merge(q, hits, LIMIT, sources.region)
    }

    /**
     * [found] with archived calls older than those in memory, for a query with dates reaching back past them: read
     * through the date index, newest first, until [LIMIT].
     */
    private suspend fun olderCalls(p: Prepared, q: RecallQuery, contactOf: (String) -> Long?, found: List<RecallHit>): List<RecallHit>? {
        val dates = q.dates ?: return null
        if (found.size >= LIMIT) return null
        val zone = ZoneId.systemDefault()
        val from = dates.startMillis(zone)
        val until = minOf(dates.endMillis(zone), p.windowStart ?: Long.MAX_VALUE)
        if (until <= from) return null
        val matcher = p.engine.callMatcher(q, contactOf)
        val more = ArrayList(found)
        sources.archivedBetween(from, until) { call ->
            if (PhoneIdentity.callRowKey(call.number, call.date) !in p.seen) matcher.hit(call)?.let { more += it }
            more.size < LIMIT
        }
        return more
    }

    /** Number memory for a number typed whole: the names calls showed then, how many calls, To call. */
    private suspend fun remembered(q: RecallQuery): List<RecallHit>? {
        if (q.dates != null || q.onlyCalls) return null
        val number = q.words.trim()
        if (PhoneIdentity.digits(number).length < WHOLE_NUMBER || number.any { it.isLetter() }) return null
        // The other sources are searched directly; these only number memory keeps.
        val hints = sources.remembered(number).filter { it.source in ONLY_REMEMBERED }
        return hints.map { h -> RecallHit(RecallSource.REMEMBERED, number, at = h.at, number = number, memory = h, score = RecallRanking.BY_NAME) }
    }

    private companion object {
        const val DEBOUNCE_MS = 250L

        /** Results kept per group. */
        const val LIMIT = 50

        /** Digits that make a number typed whole (number memory is asked by line). */
        const val WHOLE_NUMBER = 7

        val ONLY_REMEMBERED = setOf(MemorySource.ARCHIVE_NAME, MemorySource.CALLS, MemorySource.TO_CALL)
    }
}
