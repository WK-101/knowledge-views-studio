package app.parley.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.parley.DialResult
import app.parley.common.ContactSummary
import app.parley.common.DialSearch
import app.parley.common.KeypadLayout
import app.parley.common.PhoneEntry
import app.parley.common.T9
import app.parley.common.TextSearchIndex
import app.parley.common.calls.CallPill
import app.parley.common.suspendRunCatching
import app.parley.data.DataContainer
import app.parley.data.TemporaryContacts
import app.parley.data.messaging.Romanizer
import app.parley.data.vault.VaultSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The header search on the Keypad tab: the query and the contacts and visible private contacts matching it. */
data class KeypadSearch(val query: String, val contacts: List<ContactSummary>, val vault: List<VaultSummary>)

/**
 * The keypad: what is typed, the T9 and name results for it, the header's contact search, the SIM a plain Call would
 * use, and the keypad's own actions (last number, speed dial, save for a while). The contacts are encoded for T9 and
 * folded for text search once per contacts change; each keystroke only runs the search, on a background thread.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class KeypadViewModel(private val c: DataContainer) : ViewModel() {
    private val directory = c.directory
    val countryIso: String = directory.countryIso
    private val hideVault = c.settings.settings.map { it.hideVault }.distinctUntilChanged()

    /** The typed number (or letters typed on a hardware keyboard). */
    val input = MutableStateFlow("")

    /** Keypad alphabet in use: the one chosen in settings, or the phone language's. */
    val layout: StateFlow<KeypadLayout> = c.messaging.keypadLayoutChoice.map { c.messaging.effectiveLayout(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), c.messaging.effectiveLayout())

    private fun keypadEntry(contact: ContactSummary, layout: KeypadLayout) = DialSearch.Entry(
        contact,
        T9.Encoded(contact.displayName, layout, Romanizer.syllables(contact.displayName), Romanizer.phonetic(contact.phoneticName)),
    )

    private val encoded = combine(directory.contacts, layout) { list, layout -> list.orEmpty().map { keypadEntry(it, layout) } }
        .flowOn(Dispatchers.Default)

    private val encodedVault = combine(c.vault.contacts, hideVault, layout) { list, hidden, layout ->
        if (hidden) emptyList() else list.map { v ->
            keypadEntry(ContactSummary(id = -v.id, lookupKey = "", displayName = v.name, photoUri = null, starred = false, phones = v.numbers.map { PhoneEntry(it, 2, null) }), layout)
        }
    }.flowOn(Dispatchers.Default)

    /** Every entry, built once per contacts (or alphabet) change and shared by the searches below. */
    private val entries = combine(encoded, encodedVault) { a, b -> a + b }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyList())

    private val dialSearch = DialSearch(countryIso)

    /**
     * Keypad results; narrows the previous results while you type (see [DialSearch]). The search runs in this one
     * pipeline (never two at once), and a burst of keys is searched for the latest input only.
     */
    val results: StateFlow<List<DialResult>> = combine(input, entries, c.history.calls) { input, entries, calls ->
        dialSearch.search(input, entries, calls)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyList())

    /** Call pressed with nothing typed: the last number you called goes back on the keypad (like most dialers). */
    fun recallLastNumber(): Boolean {
        val n = DialSearch.lastOutgoing(c.history.calls.value) ?: return false
        input.value = n
        return true
    }

    // ---------------------------------------------------------------- the header's contact search

    val searchQuery = MutableStateFlow("")

    private val contactIndex = directory.contacts.map { list -> TextSearchIndex(list.orEmpty(), { it.displayName }, { ct -> ct.phones.map { it.number } }) }
        .flowOn(Dispatchers.Default)
    private val vaultIndex = combine(c.vault.contacts, hideVault) { list, hidden -> TextSearchIndex(if (hidden) emptyList() else list, { it.name }, { it.numbers }) }
        .flowOn(Dispatchers.Default)

    /** Matches for [searchQuery]; null until the first search has run. */
    val search: StateFlow<KeypadSearch?> = combine(searchQuery.map { it.trim() }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MS), contactIndex, vaultIndex) { q, people, vault ->
        if (q.isEmpty()) KeypadSearch(q, emptyList(), emptyList()) else KeypadSearch(q, people.search(q), vault.search(q))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    // ---------------------------------------------------------------- SIM

    /** How many call accounts the phone has (the home screen keeps it current). */
    val simCount = MutableStateFlow(0)

    /** K1: the SIM a plain Call would use for what's typed (remembered, a label's, else the default), shown on its segment. */
    val preferredSim: StateFlow<String?> = combine(input, simCount) { i, n -> i to n }
        .debounce(150)
        .mapLatest { (i, n) ->
            if (n !in 2..CallPill.MAX_SEGMENTS) return@mapLatest null
            val number = i.trim()
            (if (number.isNotEmpty() && number.none { it.isLetter() }) c.placer.resolveSim(number) else null)
                ?: withContext(Dispatchers.IO) { c.sims.defaultOutgoing() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    // ---------------------------------------------------------------- actions

    /** Long-press 2–9: the number saved on [key], or [onUnassigned] when there is none. */
    fun speedDial(key: Int, onCall: (number: String, label: String?) -> Unit, onUnassigned: () -> Unit) {
        viewModelScope.launch {
            val e = c.prefs.speedDial(key)
            if (e == null) onUnassigned() else onCall(e.number, e.label)
        }
    }

    /** "Save for a while": a temporary contact for [number]; [onResult] gets null when it couldn't be saved. */
    fun saveTemporary(number: String, name: String, days: Int, deleteHistory: Boolean, visible: Boolean, onResult: (TemporaryContacts.Saved?) -> Unit) {
        viewModelScope.launch {
            val saved = suspendRunCatching {
                TemporaryContacts.save(c, name, number, days, private = !visible, purgeHistory = deleteHistory)
            }.getOrNull()
            onResult(saved)
        }
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
        const val SEARCH_DEBOUNCE_MS = 80L
    }
}
