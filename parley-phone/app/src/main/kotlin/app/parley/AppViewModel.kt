package app.parley

import android.annotation.SuppressLint
import android.Manifest
import android.app.Application
import android.telecom.TelecomManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.ContactSummary
import app.parley.common.PhoneNumbers
import app.parley.common.SimAccount
import app.parley.common.T9
import app.parley.common.TextSearch
import app.parley.data.Permissions
import app.parley.data.PhoneEnv
import app.parley.data.PlaceResult
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

enum class RecentFilter { ALL, MISSED, INCOMING, OUTGOING, BLOCKED }

data class RecentGroup(
    val key: String,
    val number: String,
    val contact: ContactSummary?,
    val cachedName: String?,
    val calls: List<CallEntry>,
    val hidden: Boolean,
) {
    val latest: CallEntry get() = calls.first()
    val title: String get() = contact?.displayName ?: cachedName?.takeIf { it.isNotBlank() } ?: number.ifBlank { if (hidden) "Private number" else "Unknown" }
}

data class DialResult(val contact: ContactSummary?, val number: String, val match: T9.Match)

data class PendingCall(
    val number: String,
    val name: String?,
    val needConfirm: Boolean,
    val chooseSim: Boolean,
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data object RequestCallPermission : UiEvent
}

sealed interface NavEvent {
    data class Contact(val id: Long) : NavEvent
    data class History(val number: String) : NavEvent
    data class NewContact(val prefill: app.parley.data.ContactDetails) : NavEvent
    data class InsertOrEdit(val prefill: app.parley.data.ContactDetails) : NavEvent
    data class ImportVcf(val uri: android.net.Uri) : NavEvent
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
        if (wasDefault != isDefaultDialer.value || hadContacts != hasContactsPermission.value) {
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

    val favorites: StateFlow<List<ContactSummary>> = contacts.map { it.orEmpty().filter { c -> c.starred } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- Recents ----------

    val recentFilter = MutableStateFlow(RecentFilter.ALL)
    val recentQuery = MutableStateFlow("")

    val recentGroups: StateFlow<List<RecentGroup>?> = combine(c.callLog.calls, numberIndex, recentFilter, recentQuery.debounce(80)) { calls, index, filter, q ->
        calls?.let { group(it, index, filter, q) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun group(calls: List<CallEntry>, index: Map<String, ContactSummary>, filter: RecentFilter, q: String): List<RecentGroup> {
        val filtered = calls.filter {
            when (filter) {
                RecentFilter.ALL -> true
                RecentFilter.MISSED -> it.type == CallType.MISSED || it.type == CallType.REJECTED
                RecentFilter.INCOMING -> it.type == CallType.INCOMING || it.type == CallType.ANSWERED_EXTERNALLY
                RecentFilter.OUTGOING -> it.type == CallType.OUTGOING
                RecentFilter.BLOCKED -> it.type == CallType.BLOCKED
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

    fun markMissedSeen() {
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

    private val encoded = contacts.map { list -> list.orEmpty().map { it to T9.Encoded(it.displayName) } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val dialResults: StateFlow<List<DialResult>> = combine(dialInput, encoded, c.callLog.calls) { input, enc, calls ->
        val q = PhoneNumbers.clean(input).removePrefix("+")
        if (q.isEmpty() || q.any { it == '*' || it == '#' }) return@combine emptyList()
        val results = ArrayList<DialResult>()
        for ((contact, e) in enc) {
            val m = T9.match(q, e, contact.phones.map { it.number }) ?: continue
            results += DialResult(contact, m.matchedNumber ?: contact.phones.firstOrNull()?.number.orEmpty(), m)
        }
        results.sortByDescending { it.match.score }
        val seen = results.flatMap { r -> r.contact?.phones.orEmpty().map { PhoneNumbers.matchKey(it.number) } }.toHashSet()
        calls.orEmpty().asSequence()
            .filter { it.number.isNotBlank() && !it.presentationHidden }
            .distinctBy { PhoneNumbers.matchKey(it.number) }
            .filter { PhoneNumbers.matchKey(it.number) !in seen && PhoneNumbers.digits(it.number).contains(q) }
            .take(5)
            .forEach { results += DialResult(null, it.number, T9.Match(400, emptyList(), it.number)) }
        results.filter { it.contact == null || it.number.isNotEmpty() }.take(50)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- Calling ----------

    fun requestCall(number: String, name: String? = null, skipConfirm: Boolean = false) {
        if (number.isBlank()) return
        val ctx = getApplication<Application>()
        if (!Permissions.has(ctx, Manifest.permission.CALL_PHONE)) {
            events.trySend(UiEvent.RequestCallPermission)
            return
        }
        viewModelScope.launch {
            val simCount = sims.value.size
            val remembered = withContext(Dispatchers.IO) { c.prefs.simFor(number) }
            val chooseSim = simCount >= 2 && remembered == null && withContext(Dispatchers.IO) { c.sims.defaultOutgoing() } == null &&
                !PhoneNumbers.isServiceCode(number)
            val confirm = settings.value.confirmBeforeCall && !skipConfirm
            if (confirm || chooseSim) {
                pendingCall.value = PendingCall(number, name, confirm, chooseSim)
            } else {
                place(number, null)
            }
        }
    }

    fun place(number: String, simId: String?, remember: Boolean = false) {
        pendingCall.value = null
        viewModelScope.launch {
            if (remember && simId != null) c.prefs.setSimFor(number, simId)
            when (val r = c.placer.call(number, simId)) {
                is PlaceResult.Failed -> toast(r.reason)
                else -> Unit
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

    /** Deletes contacts (the journal keeps a copy for 30 days) and offers undo. */
    fun deleteContacts(ids: List<Long>) {
        viewModelScope.launch {
            c.contacts.delete(ids)
            toast(if (ids.size == 1) "Contact deleted" else "${ids.size} contacts deleted")
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
}
