package app.parley

import android.annotation.SuppressLint
import android.Manifest
import android.app.Application
import android.telecom.TelecomManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.DialSearch
import app.parley.common.KeypadLayout
import app.parley.common.ContactSummary
import app.parley.common.PhoneNumbers
import app.parley.common.PhoneEntry
import app.parley.common.SimAccount
import app.parley.common.T9
import app.parley.common.TextSearch
import app.parley.data.Permissions
import app.parley.data.CallLogRepository
import app.parley.data.PhoneEnv
import app.parley.data.PlaceResult
import app.parley.data.messaging.Romanizer
import app.parley.shortcuts.Shortcuts
import app.parley.calltime.CallTimePlanner
import app.parley.calltime.UssdSession
import app.parley.common.calltime.Ussd
import app.parley.common.calls.CallSource
import app.parley.common.calls.PocketGuard
import app.parley.calls.MissedCallNotifier
import app.parley.calls.ProximityProbe
import app.parley.data.DialWarning
import app.parley.telecom.CallManager
import app.parley.ui.people.PeopleUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.concurrent.TimeUnit

/** Recents filter chips. VOICEMAIL shows the voicemail inbox (V1) instead of the call list. */
enum class RecentFilter { ALL, MISSED, INCOMING, OUTGOING, BLOCKED, VOICEMAIL }

data class RecentGroup(
    val key: String,
    val number: String,
    val contact: ContactSummary?,
    val cachedName: String?,
    val calls: List<CallEntry>,
    val hidden: Boolean,
    /** Set for calls with private (vault) contacts; they live only in Parley's encrypted history. */
    val vaultId: Long? = null,
) {
    val latest: CallEntry get() = calls.first()
    val title: String get() = contact?.displayName ?: cachedName?.takeIf { it.isNotBlank() } ?: number.ifBlank { if (hidden) "Private number" else "Unknown" }
}

/** One keypad result row (see [app.parley.common.DialHit]). */
typealias DialResult = app.parley.common.DialHit

data class PendingCall(
    val number: String,
    val name: String?,
    val needConfirm: Boolean,
    val chooseSim: Boolean,
    /** Why the call needs confirming beyond the usual question, e.g. a used-up call-time allowance. */
    val note: String? = null,
    val simId: String? = null,
    /** Shown first in the shared dial-guard sheet (premium line, one-ring scam, listed number). */
    val warnings: List<app.parley.data.DialWarning> = emptyList(),
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Undo(val text: String, val journalIds: List<Long>) : UiEvent
    /** U4: calls deleted from history (a swipe), with Undo from the archive's deleted-calls batch. */
    data class UndoCalls(val text: String, val batchId: Long) : UiEvent
    data object RequestCallPermission : UiEvent
}

sealed interface NavEvent {
    data class Contact(val id: Long) : NavEvent
    data class History(val number: String) : NavEvent
    data class NewContact(val prefill: app.parley.data.ContactDetails) : NavEvent
    data class InsertOrEdit(val prefill: app.parley.data.ContactDetails) : NavEvent
    data class ImportVcf(val uri: android.net.Uri) : NavEvent
    data class SecureQr(val uri: android.net.Uri) : NavEvent
    data class Vault(val id: Long) : NavEvent
    data class Route(val route: String) : NavEvent
    data class Tab(val tab: app.parley.common.StartTab, val dial: String? = null, val missedOnly: Boolean = false) : NavEvent
}

// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
@OptIn(FlowPreview::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val c = app.container
    val settings = c.settings.settings
    val countryIso: String = PhoneEnv.countryIso(app)

    val isDefaultDialer = MutableStateFlow(Permissions.isDefaultDialer(app))
    val hasContactsPermission = MutableStateFlow(Permissions.has(app, Manifest.permission.READ_CONTACTS))
    val sims = MutableStateFlow<List<SimAccount>>(emptyList())

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()
    private val nav = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents = nav.receiveAsFlow()

    val pendingCall = MutableStateFlow<PendingCall?>(null)

    /** Draft handed to the editor by other apps (Insert extras) or "add to contact" flows. */
    var pendingPrefill: app.parley.data.ContactDetails? = null

    private var hasCallLogPermission = false

    init {
        refreshEnvironment()
    }

    /** Called on resume: roles or permissions may have changed in system settings. */
    fun refreshEnvironment() {
        val ctx = getApplication<Application>()
        val wasDefault = isDefaultDialer.value
        val hadContacts = hasContactsPermission.value
        isDefaultDialer.value = Permissions.isDefaultDialer(ctx)
        hasContactsPermission.value = Permissions.has(ctx, Manifest.permission.READ_CONTACTS)
        // F29: call-log access granted later (outside the dialer role) must also re-register the observers.
        val hadCallLog = hasCallLogPermission
        hasCallLogPermission = Permissions.has(ctx, Manifest.permission.READ_CALL_LOG)
        if (wasDefault != isDefaultDialer.value || hadContacts != hasContactsPermission.value || hadCallLog != hasCallLogPermission) {
            c.contacts.refresh()
            c.callLog.refresh()
        }
        viewModelScope.launch(Dispatchers.IO) { sims.value = c.sims.accounts() }
    }

    fun navigate(e: NavEvent) {
        nav.trySend(e)
    }

    fun toast(text: String) {
        events.trySend(UiEvent.Message(text))
    }

    // ---------- Contacts ----------

    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    val contacts: StateFlow<List<ContactSummary>?> = combine(c.contacts.contacts, settings.map { it.sortByFirstName }.distinctUntilChanged()) { list, first ->
        when {
            list == null -> null
            first -> list
            else -> list.map { it.copy(displayName = it.displayNameAlt) }.sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** matchKey → contact, for naming call-log entries. */
    val numberIndex: StateFlow<Map<String, ContactSummary>> = contacts.map { list ->
        val m = HashMap<String, ContactSummary>()
        list.orEmpty().forEach { ct -> ct.phones.forEach { p -> m.putIfAbsent(PhoneNumbers.matchKey(p.number), ct) } }
        m as Map<String, ContactSummary>
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun contactFor(number: String?): ContactSummary? {
        if (number.isNullOrBlank()) return null
        val key = PhoneNumbers.matchKey(number)
        if (key.length < 3) return null
        return numberIndex.value[key]
    }

    val contactQuery = MutableStateFlow("")

    /** Contacts tab shows the private vault instead of phone contacts. */
    val showVault = MutableStateFlow(false)

    /** Contacts selected in the Contacts tab (multi-select mode when non-empty). */
    val selection = MutableStateFlow<Set<Long>>(emptySet())

    fun toggleSelection(id: Long) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }
    val selectedGroup = MutableStateFlow<Long?>(null)
    private val groupMembers = MutableStateFlow<Set<Long>?>(null)

    fun selectGroup(id: Long?) {
        selectedGroup.value = id
        if (id == null) {
            groupMembers.value = null
        } else {
            viewModelScope.launch(Dispatchers.IO) { groupMembers.value = c.contacts.contactIdsInGroup(id) }
        }
    }

    val filteredContacts: StateFlow<List<ContactSummary>?> = combine(contacts, contactQuery.debounce(80), groupMembers) { list, q, members ->
        list?.filter { ct ->
            (members == null || ct.id in members) &&
                (q.isBlank() || TextSearch.matches(q, ct.displayName, ct.phones.map { it.number }, ct.emails))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Contacts-feature state: label and account filters, second line, favourites order. */
    val people = PeopleUi(c, viewModelScope, contacts, contactQuery, countryIso)

    val favorites: StateFlow<List<ContactSummary>> = contacts.map { it.orEmpty().filter { c -> c.starred } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- Recents ----------

    val recentFilter = MutableStateFlow(RecentFilter.ALL)

    /** Recents rows selected for bulk actions (keys of [RecentGroup]). */
    val recentSelection = MutableStateFlow<Set<String>>(emptySet())
    val recentQuery = MutableStateFlow("")

    /** System call log (plus Parley's archive) + private (vault) calls, newest first. */
    // V11: until the full log (and the archive) have loaded, the first page of the call log is shown.
    private val allCalls = combine(c.history.calls, c.callLog.preview, c.vault.privateCalls, settings.map { it.hideVault }.distinctUntilChanged()) { full, preview, priv, hidden ->
        val sys = full ?: preview ?: return@combine null
        if (hidden || priv.isEmpty()) return@combine sys
        (sys + priv.map { p ->
            CallEntry(-p.id, p.number, p.name, CallLogRepository.mapType(p.type), p.date, p.durationSec, null, false, false)
        }).sortedByDescending { it.date }
    }

    // F7: keyed by line (E.164 with this phone's country), so a foreign number sharing the last 9 digits isn't shown as private.
    private val vaultByKey = c.vault.contacts.map { list -> list.flatMap { v -> v.numbers.map { PhoneNumbers.lineKey(it, countryIso) to v.id } }.toMap() }

    /** [allCalls] with the Recents filter chips applied (SIM, type, period, duration). */
    private val filteredCalls = combine(allCalls, c.history.activeFilter) { calls, f ->
        if (calls == null || f.isEmpty) calls else calls.filter(f.matcher(System.currentTimeMillis(), java.time.ZoneId.systemDefault()))
    }

    val recentGroups: StateFlow<List<RecentGroup>?> = combine(filteredCalls, numberIndex, recentFilter, recentQuery.debounce(80), vaultByKey) { calls, index, filter, q, vaults ->
        calls?.let { group(it, index, filter, q).map { g -> if (g.calls.first().id < 0) g.copy(vaultId = vaults[PhoneNumbers.lineKey(g.number, countryIso)]) else g } }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun group(calls: List<CallEntry>, index: Map<String, ContactSummary>, filter: RecentFilter, q: String): List<RecentGroup> {
        val filtered = calls.filter {
            when (filter) {
                RecentFilter.ALL -> true
                RecentFilter.MISSED -> it.type == CallType.MISSED || it.type == CallType.REJECTED
                RecentFilter.INCOMING -> it.type == CallType.INCOMING || it.type == CallType.ANSWERED_EXTERNALLY
                RecentFilter.OUTGOING -> it.type == CallType.OUTGOING
                RecentFilter.BLOCKED -> it.type == CallType.BLOCKED
                RecentFilter.VOICEMAIL -> it.type == CallType.VOICEMAIL
            }
        }
        val out = ArrayList<RecentGroup>()
        var current: MutableList<CallEntry>? = null
        var currentKey = ""
        var currentDay = -1L
        for (e in filtered) {
            val key = if (e.presentationHidden || e.number.isBlank()) "hidden" else PhoneNumbers.matchKey(e.number)
            val day = TimeUnit.MILLISECONDS.toDays(e.date + java.util.TimeZone.getDefault().getOffset(e.date))
            if (current != null && key == currentKey && day == currentDay) {
                current += e
            } else {
                current = mutableListOf(e)
                currentKey = key
                currentDay = day
                out += RecentGroup(key + ":" + e.id, e.number, if (key == "hidden") null else index[key], e.cachedName, current, key == "hidden")
            }
        }
        val grouped = out.map { it.copy(calls = it.calls.toList()) }
        if (q.isBlank()) return grouped
        return grouped.filter { TextSearch.matches(q, it.title, listOf(it.number)) }
    }

    /** Most-called numbers in the last 60 days that aren't favourites. */
    val frequents: StateFlow<List<RecentGroup>> = combine(c.callLog.calls, numberIndex) { calls, index ->
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(60)
        calls.orEmpty()
            .filter { it.date > since && it.number.isNotBlank() && !it.presentationHidden && it.type != CallType.BLOCKED }
            .groupBy { PhoneNumbers.matchKey(it.number) }
            .filter { it.value.size >= 2 }
            .entries.sortedByDescending { it.value.size }
            .map { (k, v) -> RecentGroup(k, v.first().number, index[k], v.first().cachedName, v, false) }
            .filter { it.contact?.starred != true }
            .take(8)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val missedCount: StateFlow<Int> = c.callLog.calls.map { list -> list.orEmpty().count { it.type == CallType.MISSED && it.isNew } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** V11: Recents is on screen: Telecom's missed-call count goes, and so does the re-alert. */
    fun onRecentsShown() {
        MissedCallNotifier.stopReAlert(getApplication())
        try {
            getApplication<Application>().getSystemService(TelecomManager::class.java).cancelMissedCallsNotification()
        } catch (_: Exception) {
        }
        if (missedCount.value > 0) markMissedSeen()
    }

    fun markMissedSeen() {
        MissedCallNotifier.stopReAlert(getApplication())
        viewModelScope.launch {
            c.callLog.markMissedRead()
            try {
                getApplication<Application>().getSystemService(TelecomManager::class.java).cancelMissedCallsNotification()
            } catch (_: Exception) {
            }
        }
    }

    // ---------- Dialer ----------

    val dialInput = MutableStateFlow("")

    /** Keypad alphabet in use: the one chosen in settings, or the phone language's. */
    val keypadLayout: StateFlow<KeypadLayout> = c.messaging.keypadLayoutChoice.map { c.messaging.effectiveLayout(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.messaging.effectiveLayout())

    private fun keypadEntry(contact: ContactSummary, layout: KeypadLayout) = DialSearch.Entry(
        contact,
        T9.Encoded(contact.displayName, layout, Romanizer.syllables(contact.displayName), Romanizer.phonetic(contact.phoneticName)),
    )

    private val encoded = combine(contacts, keypadLayout) { list, layout -> list.orEmpty().map { keypadEntry(it, layout) } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val encodedVault = combine(c.vault.contacts, settings.map { it.hideVault }.distinctUntilChanged(), keypadLayout) { list, hidden, layout ->
        if (hidden) emptyList() else list.map { v ->
            keypadEntry(ContactSummary(id = -v.id, lookupKey = "", displayName = v.name, photoUri = null, starred = false, phones = v.numbers.map { PhoneEntry(it, 2, null) }), layout)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val dialSearch = DialSearch()

    /** Keypad results; narrows the previous results while you type (see [DialSearch]). */
    val dialResults: StateFlow<List<DialResult>> = combine(dialInput, combine(encoded, encodedVault) { a, b -> a + b }, c.callLog.calls) { input, entries, calls ->
        dialSearch.search(input, entries, calls)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Call pressed with nothing typed: the last number you called goes back on the keypad (like most dialers). */
    fun recallLastNumber(): Boolean {
        val n = DialSearch.lastOutgoing(c.callLog.calls.value) ?: return false
        dialInput.value = n
        return true
    }

    // ---------- Calling ----------

    /** USSD codes typed on the keypad (A13). */
    val ussd = UssdSession(c, viewModelScope)
    private val gate = CallGate(c)

    /**
     * Every call starts here (see [CallGate]): one question for the dial guard, the allowance, confirm-before-call
     * and the SIM, then the call. [simId]: a SIM the user already picked ("call with SIM").
     */
    fun requestCall(number: String, name: String? = null, skipConfirm: Boolean = false, simId: String? = null, source: CallSource = CallSource.OTHER) {
        if (number.isBlank()) return
        if (Ussd.isUssd(number)) {
            ussd.start(number.trim(), sims.value, null)
            return
        }
        val ctx = getApplication<Application>()
        if (!Permissions.has(ctx, Manifest.permission.CALL_PHONE)) {
            events.trySend(UiEvent.RequestCallPermission)
            return
        }
        viewModelScope.launch {
            val p = gate.check(number, name, sims.value.size, simId, skipConfirm)
            // V8: a one-tap call (favourite) while the proximity sensor is covered asks first: probably a pocket.
            val pocket = PocketGuard.GUARDED.contains(source) && c.callExtras.config.value.pocketGuard &&
                PocketGuard.shouldAsk(true, source, ProximityProbe.isCovered(ctx))
            val ask = if (pocket) {
                val covered = DialWarning("Phone covered", PocketGuard.QUESTION + " Call only if you meant to.")
                (p ?: PendingCall(number, name, needConfirm = true, chooseSim = false, simId = simId)).let { it.copy(needConfirm = true, warnings = listOf(covered) + it.warnings) }
            } else {
                p
            }
            // Nothing to ask: the allowance was checked too.
            if (ask != null) pendingCall.value = ask else place(number, simId, confirmed = true)
        }
    }

    /** [confirmed]: the user already said yes to a used-up call-time allowance (T6). */
    fun place(number: String, simId: String?, remember: Boolean = false, confirmed: Boolean = false) {
        pendingCall.value = null
        if (Ussd.isUssd(number)) {
            ussd.start(number.trim(), sims.value, simId)
            return
        }
        viewModelScope.launch {
            when (val r = gate.place(number, simId, contactFor(number)?.displayName, sims.value, remember, confirmed)) {
                is CallGate.Placed.Ask -> pendingCall.value = r.pending
                is CallGate.Placed.Done -> (r.result as? PlaceResult.Failed)?.let { toast(it.reason) }
            }
        }
    }

    fun callVoicemail() {
        when (val r = c.placer.callVoicemail()) {
            is PlaceResult.Failed -> toast(r.reason)
            else -> Unit
        }
    }

    fun speedDial(key: Int, onUnassigned: () -> Unit) {
        viewModelScope.launch {
            val e = c.prefs.speedDial(key)
            if (e == null) onUnassigned() else requestCall(e.number, e.label)
        }
    }

    /**
     * Moves a phone contact into the vault, leaving no readable copy in Parley: no journal entry, and its
     * time-machine versions are purged. Throws [app.parley.data.vault.VaultCrypto.LockedException] if locked.
     */
    suspend fun moveToVault(contactId: Long, d: app.parley.data.ContactDetails): Long {
        // Lossless: the vault keeps the full contact record (photo included); local copies are purged at once (F4).
        val moved = c.vaultMoves.moveIn(contactId, d)
        if (d.lookupKey.isNotEmpty()) {
            c.journal.forget(d.lookupKey)
            c.timeMachine.purge(d.lookupKey)
        }
        if (moved.removedAfterSync) toast("Removed from other apps after the next sync")
        return moved.vaultId
    }

    /** Deletes contacts (the journal keeps a copy for 30 days) and offers undo. */
    fun deleteContacts(ids: List<Long>) {
        viewModelScope.launch {
            try {
                c.contacts.delete(ids)
            } catch (e: Exception) {
                toast(e.message ?: "Couldn't delete")
                return@launch
            }
            val journal = c.contacts.lastJournalIds
            val text = if (ids.size == 1) "Contact deleted" else "${ids.size} contacts deleted"
            if (journal.isNotEmpty()) events.trySend(UiEvent.Undo(text, journal)) else toast(text)
        }
    }

    /** U4: deletes one Recents row's calls, offering Undo (the archive keeps them 30 days). */
    fun deleteCallsWithUndo(entries: List<CallEntry>) {
        viewModelScope.launch {
            val batch = c.history.delete(entries.filter { it.id > 0 })
            val text = if (entries.size == 1) "Call deleted" else "${entries.size} calls deleted"
            if (batch != null) events.trySend(UiEvent.UndoCalls(text, batch)) else toast(text)
        }
    }

    fun undo(journalIds: List<Long>) {
        viewModelScope.launch {
            journalIds.forEach { c.journal.restore(it) }
            toast("Restored")
        }
    }

    fun blockNumber(number: String) {
        viewModelScope.launch {
            val ok = c.blocks.blockNumber(number)
            toast(if (ok) "Blocked $number" else "Couldn't block. Make Parley your default phone app first.")
        }
    }

    fun unblockNumber(number: String) {
        viewModelScope.launch {
            c.blocks.unblockNumber(number)
            toast("Unblocked $number")
        }
    }

    // Declared last: needs [favorites] initialised.
    init {
        // Folder sync shortly after local contact changes (no-op when nothing changed).
        viewModelScope.launch(Dispatchers.IO) {
            c.contacts.contacts.debounce(20_000).collect {
                val st = c.folderSync.status.value
                if (it != null && st.folderUri != null && st.auto) runCatching { c.folderSync.syncNow() }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            favorites.debounce(1000).distinctUntilChanged().collect { Shortcuts.updateDynamic(getApplication(), it) }
        }
    }
}
