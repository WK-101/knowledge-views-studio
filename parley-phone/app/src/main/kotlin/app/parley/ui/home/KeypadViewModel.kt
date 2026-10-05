package app.parley.ui.home

import app.parley.common.people.NameOrder
import app.parley.common.people.PrivateListing
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.parley.DialResult
import app.parley.common.ContactSummary
import app.parley.common.DialSearch
import app.parley.common.KeypadLayout
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

/**
 * The header search on the Keypad tab: the query and the contacts and visible private contacts matching it. [vault]:
 * the private ones as list rows (negative ids, photo), built here off the main thread (their photo is a file check).
 */
data class KeypadSearch(val query: String, val contacts: List<ContactSummary>, val vault: List<ContactSummary>)

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
    private val lastFirst = c.settings.settings.map { it.showNamesLastFirst }.distinctUntilChanged()

    /** A private contact as a row, its name shown as "Show names as" says (like the address book's in [directory]). */
    private fun privateRow(v: VaultSummary, lastFirst: Boolean) =
        PrivateListing.row(v.id, NameOrder.shown(v.name, v.nameAlt, lastFirst), v.numbers, v.starred, c.vault.photoUri(v.id), v.nameAlt)

    /** The typed number (or letters typed on a hardware keyboard). */
    val input = MutableStateFlow("")

    /** Keypad alphabet in use: the one chosen in settings, or the phone language's. */
    val layout: StateFlow<KeypadLayout> = c.messaging.keypadLayoutChoice.map { c.messaging.effectiveLayout(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), c.messaging.effectiveLayout())

    private fun keypadEntry(contact: ContactSummary, layout: KeypadLayout): DialSearch.Entry {
        val syllables = Romanizer.syllables(contact.displayName)
        // A name in another script (Cyrillic, Greek, Arabic…) is also found by its Latin spelling ("4826" finds Иван);
        // Chinese, Japanese and Korean names go by their syllables instead. Spellings are cached, so this runs once a name.
        val phonetic = Romanizer.phonetic(contact.phoneticName) ?: if (syllables == null) Romanizer.latin(contact.displayName) else null
        return DialSearch.Entry(contact, T9.Encoded(contact.displayName, layout, syllables, phonetic))
    }

    private val encoded = combine(directory.contacts, layout) { list, layout -> list.orEmpty().map { keypadEntry(it, layout) } }
        .flowOn(Dispatchers.Default)

    private val encodedVault = combine(c.vault.contacts, hideVault, layout, lastFirst) { list, hidden, layout, lastFirst ->
        if (hidden) emptyList() else list.map { v ->
            // The same row as in Contacts (negative id, photo, lock badge): a tap opens the one contact page.
            keypadEntry(privateRow(v, lastFirst), layout)
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

    private val contactIndex = directory.contacts
        .map { list -> TextSearchIndex(list.orEmpty(), { it.displayName }, { ct -> ct.phones.map { it.number } }, countryIso) }
        .flowOn(Dispatchers.Default)
    private val vaultIndex = combine(
        c.vault.contacts, hideVault,
    ) { list, hidden -> TextSearchIndex(if (hidden) emptyList() else list, { it.name }, { it.numbers }, countryIso) }
        .flowOn(Dispatchers.Default)

    /** Matches for [searchQuery]; null until the first search has run. */
    val search: StateFlow<KeypadSearch?> = combine(
        searchQuery.map { it.trim() }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MS), contactIndex, vaultIndex, lastFirst,
    ) { q, people, vault, lastFirst ->
        if (q.isEmpty()) {
            KeypadSearch(q, emptyList(), emptyList())
        } else {
            KeypadSearch(q, people.search(q), vault.search(q).map { v -> privateRow(v, lastFirst) })
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    // ---------------------------------------------------------------- SIM

    /** How many call accounts the phone has (the home screen keeps it current). */
    val simCount = MutableStateFlow(0)

    /** The SIM a plain Call would use for what's typed (remembered, a label's, else the default), shown on its segment. */
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
