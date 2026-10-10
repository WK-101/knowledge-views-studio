package app.parley.ui.calls

import app.parley.calls.ToCallReminders
import app.parley.common.CallEntry
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.calls.OwedMissedCall
import app.parley.common.calls.SettlingCall
import app.parley.common.calls.ToCall
import app.parley.common.calls.ToCallCount
import app.parley.common.calls.ToCallEntry
import app.parley.common.calls.ToCallState
import app.parley.common.circle.Promises
import app.parley.data.DataContainer
import app.parley.data.circle.CircleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the To call list, with who it is as the lists show them. */
data class ToCallRow(
    val entry: ToCallEntry,
    /** A device contact, or null. */
    val contact: ContactSummary?,
    /** A private contact's vault id (never in discreet mode), or null. */
    val vaultId: Long?,
    /** The name, or null to show the number. */
    val name: String?,
    /** The first open promise to them ("send the photos"), for a contact. */
    val promise: String?,
) {
    val number: String get() = entry.number
}

/**
 * The To call list (I9) as Recents and its screen show it: the stored items and the missed calls not returned yet
 * ([ToCall.entries]), named like Recents names calls (a private contact only outside discreet mode). Calls made or
 * answered since an item was set settle it ([ToCall.settle]). Lives in [app.parley.ui.home.RecentsViewModel], which
 * already holds the merged calls and the missed calls to return.
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToCallModel(
    private val c: DataContainer,
    private val scope: CoroutineScope,
    private val calls: StateFlow<List<CallEntry>?>,
    unreturned: StateFlow<Set<Long>>,
) {
    private val iso = c.directory.countryIso
    private val store = c.toCall

    val folded: StateFlow<Boolean> get() = store.folded

    /** Moves on each minute, so rows fall due without a new call. */
    private val minutes: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(MINUTE_MS)
        }
    }

    private val owed: Flow<List<OwedMissedCall>> = combine(calls, unreturned) { list, ids ->
        if (list == null || ids.isEmpty()) {
            emptyList()
        } else {
            list.filter { it.id in ids }.map { OwedMissedCall(PhoneIdentity.key(it.number, iso), it.number, it.date, it.accountId) }
        }
    }

    private val entries: Flow<List<ToCallEntry>> = combine(store.state, owed, minutes) { s, missed, now -> ToCall.entries(s, missed, now) }

    private val hideVault = c.privacy.privateHidden

    private val people = combine(c.directory.numberIndex, c.vault.contacts, hideVault) { index, vaults, hide ->
        Triple(index, if (hide) emptyMap() else vaults.flatMap { v -> v.numbers.map { PhoneIdentity.key(it, iso) to v } }.toMap(), hide)
    }

    /** The rows, due first. */
    val rows: StateFlow<List<ToCallRow>> = combine(entries, people) { list, (index, vaults, _) ->
        list.map { e ->
            val contact = index[e.number]
            val vault = if (contact == null) vaults[e.key] else null
            ToCallRow(e, contact, vault?.id, contact?.displayName ?: vault?.name, null)
        }
    }.mapLatest { rows -> rows.map { r -> r.contact?.let { ct -> r.copy(promise = promiseFor(ct)) } ?: r } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyList())

    /** For the strip: how many are due now and later. */
    val count: StateFlow<ToCallCount> = rows.map { ToCall.count(it.map { r -> r.entry }, System.currentTimeMillis()) }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), ToCallCount(0, 0))

    init {
        scope.launch { store.load() }
    }

    /**
     * While the strip or the list shows: a call with them since an item was set settles it (you called, tried or
     * talked). Returns when the caller's scope ends.
     */
    suspend fun followCalls() {
        combine(calls.filterNotNull(), store.state) { list, s -> list to s }.debounce(SETTLE_DEBOUNCE_MS).collect { (list, s) ->
            if (s.items.isEmpty()) return@collect
            val keys = s.items.map { it.key }.toSet()
            val settling = list.asSequence().filter(ToCall::settles).mapNotNull { e ->
                PhoneIdentity.key(e.number, iso).takeIf { it in keys }?.let { SettlingCall(it, e.date) }
            }.toList()
            if (ToCall.settle(s, settling, System.currentTimeMillis()) !== s) {
                ToCallReminders.update(c.appContext) { ToCall.settle(it, settling, System.currentTimeMillis()) }
            }
        }
    }

    private suspend fun promiseFor(contact: ContactSummary): String? = runCatching {
        val keys = contact.phones.flatMap { PhoneIdentity.lookupKeys(it.number, iso) }.toSet()
        c.circle.notesFor(contact.lookupKey, keys, limit = PROMISE_NOTES)
            .filter { it.source != CircleRepository.NoteSource.PINNED }
            .firstNotNullOfOrNull { n -> Promises.open(n.text).firstOrNull()?.text }
    }.getOrNull()

    private fun change(f: (ToCallState) -> ToCallState) {
        scope.launch { ToCallReminders.update(c.appContext, f) }
    }

    /** Done or removed; returns the list as it was, for Undo. */
    fun done(entry: ToCallEntry): ToCallState {
        val before = store.state.value
        change { ToCall.done(it, entry.key, System.currentTimeMillis()) }
        return before
    }

    fun undoDone(entry: ToCallEntry, before: ToCallState) = change { ToCall.undoDone(it, before, entry.key) }

    fun snooze(entry: ToCallEntry, at: Long) = change { ToCall.snooze(it, entry, at, System.currentTimeMillis()) }

    fun setTheirEvening(entry: ToCallEntry, on: Boolean) = change { ToCall.setTheirEvening(it, entry, on, System.currentTimeMillis()) }

    fun setFolded(folded: Boolean) = store.setFolded(folded)

    private companion object {
        const val MINUTE_MS = 60_000L
        const val STOP_AFTER_MS = 5_000L
        const val SETTLE_DEBOUNCE_MS = 2_000L
        const val PROMISE_NOTES = 20
    }
}
