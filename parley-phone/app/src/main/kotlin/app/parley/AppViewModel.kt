package app.parley

import app.parley.work.FolderSyncNotice
import app.parley.ui.home.PrivateMoves
import app.parley.ui.Destination
import android.net.Uri
import app.parley.blocking.DialText
import app.parley.common.DialHit
import app.parley.common.suspendRunCatching
import app.parley.common.StartTab
import app.parley.data.ContactDetails
import app.parley.ui.circle.CircleUi
import android.annotation.SuppressLint
import android.Manifest
import android.app.Application
import android.telecom.TelecomManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.PhoneNumbers
import app.parley.common.SimAccount
import app.parley.data.Permissions
import app.parley.data.PlaceResult
import app.parley.shortcuts.Shortcuts
import app.parley.calltime.CallTimePlanner
import app.parley.calltime.UssdSession
import app.parley.common.calltime.Ussd
import app.parley.common.calls.CallSource
import app.parley.common.calls.EmergencyPolicy
import app.parley.common.calls.PocketGuard
import app.parley.calls.MissedCallNotifier
import app.parley.calls.ProximityProbe
import app.parley.data.DialWarning
import app.parley.telecom.CallManager
import app.parley.ui.Bidi
import app.parley.ui.people.PeopleUi
import kotlinx.coroutines.Dispatchers
import app.parley.common.people.PrivateListing
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
import java.util.concurrent.TimeUnit

/** Recents filter chips. VOICEMAIL shows the voicemail inbox instead of the call list. */
/** Recents' chips, in their order: by call type, then by who called (Unknown, Contacts), then Blocked and Voicemail. */
enum class RecentFilter { ALL, MISSED, INCOMING, OUTGOING, UNKNOWN, CONTACTS, BLOCKED, VOICEMAIL }

data class RecentGroup(
    val key: String,
    val number: String,
    val contact: ContactSummary?,
    val cachedName: String?,
    val calls: List<CallEntry>,
    val hidden: Boolean,
    /** Set for calls with private (vault) contacts; they live only in Parley's encrypted history. */
    val vaultId: Long? = null,
    /** Localised "Private number" / "Unknown", for a row with neither a name nor a number. */
    val fallbackTitle: String = "",
) {
    val latest: CallEntry get() = calls.first()
    val title: String get() = contact?.displayName ?: cachedName?.takeIf { it.isNotBlank() } ?: number.ifBlank { fallbackTitle }
}

/** One keypad result row (see [app.parley.common.DialHit]). */
typealias DialResult = DialHit

data class PendingCall(
    val number: String,
    val name: String?,
    val needConfirm: Boolean,
    val chooseSim: Boolean,
    /** Why the call needs confirming beyond the usual question, e.g. a used-up call-time allowance. */
    val note: String? = null,
    val simId: String? = null,
    /** Shown first in the shared dial-guard sheet (premium line, one-ring scam, listed number). */
    val warnings: List<DialWarning> = emptyList(),
    /** I12: the reason sent with the call (`EXTRA_CALL_SUBJECT`), or null. */
    val subject: String? = null,
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Undo(val text: String, val journalIds: List<Long>) : UiEvent
    /** Calls deleted from history (a swipe), with Undo from the archive's deleted-calls batch. */
    data class UndoCalls(val text: String, val batchId: Long) : UiEvent

    /** A change with its own way back (e.g. relations added to other contacts). */
    data class UndoAction(val text: String, val undo: suspend () -> Unit) : UiEvent

    /** Something that didn't happen, with one way to do it anyway ([actionLabel]), e.g. "Delete without a copy". */
    data class Offer(val text: String, val actionLabel: String, val action: suspend () -> Unit) : UiEvent

    data object RequestCallPermission : UiEvent
}

sealed interface NavEvent {
    data class Contact(val id: Long) : NavEvent
    data class History(val number: String) : NavEvent
    data class NewContact(val prefill: ContactDetails) : NavEvent
    data class InsertOrEdit(val prefill: ContactDetails) : NavEvent
    data class ImportVcf(val uri: Uri) : NavEvent
    data class SecureQr(val uri: Uri) : NavEvent
    data class Vault(val id: Long) : NavEvent
    data class Route(val route: Destination) : NavEvent
    data class Tab(val tab: StartTab, val dial: String? = null, val missedOnly: Boolean = false) : NavEvent
}

// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
@OptIn(FlowPreview::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val c = app.container
    val settings = c.settings.settings
    val countryIso: String = c.directory.countryIso

    val isDefaultDialer = MutableStateFlow(Permissions.isDefaultDialer(app))
    val hasContactsPermission = MutableStateFlow(Permissions.has(app, Manifest.permission.READ_CONTACTS))
    val sims = MutableStateFlow<List<SimAccount>>(emptyList())

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()
    private val nav = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents = nav.receiveAsFlow()

    val pendingCall = MutableStateFlow<PendingCall?>(null)

    /** Draft handed to the editor by other apps (Insert extras) or "add to contact" flows. */
    var pendingPrefill: ContactDetails? = null

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
        // Call-log access granted later (outside the dialer role) must also re-register the observers.
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

    /** [text] with Undo, which runs [undo]. */
    fun offerUndo(text: String, undo: suspend () -> Unit) {
        events.trySend(UiEvent.UndoAction(text, undo))
    }

    /** Runs an Undo from [offerUndo] here, so leaving the screen doesn't stop it half way. */
    fun runUndo(undo: suspend () -> Unit) {
        viewModelScope.launch { suspendRunCatching { undo() } }
    }

    /** A text in the app's language, for toasts and messages built here. */
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun plural(id: Int, count: Int, vararg args: Any): String = getApplication<Application>().resources.getQuantityString(id, count, *args)

    // ---------- Contacts ----------

    /**
     * The contacts as shown (sorted by first or last name) and the number → contact index, from the shared
     * [app.parley.data.ContactDirectory]. Kept running while this view model lives: [contactFor] reads the index
     * synchronously when a call is placed.
     */
    val contacts: StateFlow<List<ContactSummary>?> = c.directory.contacts.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Number → contact by line ([PhoneIdentity]), for naming call-log entries. */
    val numberIndex: StateFlow<PhoneIdentity.LineMap<ContactSummary>> = c.directory.numberIndex
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhoneIdentity.LineMap(countryIso))

    fun contactFor(number: String?): ContactSummary? {
        if (number.isNullOrBlank() || PhoneNumbers.digits(number).length < 3) return null
        return numberIndex.value[number]
    }

    val contactQuery = MutableStateFlow("")

    /** The Contacts tab's "Private" filter: only private contacts are listed. */
    val showVault = MutableStateFlow(false)

    /**
     * The contacts Parley's own lists show (Contacts, Favourites, the Circle): the address book's plus private contacts,
     * which carry a lock badge, unless discreet mode hides them. Only these screens use it; the list other apps can
     * query ([app.parley.data.ContactDirectory]) never contains private contacts.
     */
    val everyone: StateFlow<List<ContactSummary>?> = combine(
        contacts, c.vault.contacts, settings.map { it.hideVault }.distinctUntilChanged(),
    ) { list, vault, hidden ->
        if (list == null || hidden || vault.isEmpty()) return@combine list
        val names = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        val rows = vault.map { v -> PrivateListing.row(v.id, v.name, v.numbers, v.starred, c.vault.photoUri(v.id)) }
        PrivateListing.merge(list, rows) { a, b -> names.compare(a, b) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Contacts selected in the Contacts tab (multi-select mode when non-empty). */
    val selection = MutableStateFlow<Set<Long>>(emptySet())

    fun toggleSelection(id: Long) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    /** Contacts-feature state: label and account filters, second line, favourites order. */
    val people = PeopleUi(
        c, viewModelScope, everyone, contactQuery, countryIso,
        privateOnly = combine(showVault, settings) { on, s -> on && !s.hideVault }.stateIn(viewModelScope, SharingStarted.Eagerly, false),
        includePrivate = settings.map { !it.hideVault }.stateIn(viewModelScope, SharingStarted.Eagerly, !settings.value.hideVault),
    )

    /** The Circle (people with keep-in-touch set) and its suggestions. */
    val circle = CircleUi(c, viewModelScope, everyone)

    val favorites: StateFlow<List<ContactSummary>> = contacts.map { it.orEmpty().filter { c -> c.starred } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- Recents (the list itself lives in ui.home.RecentsViewModel) ----------

    /** Most-called numbers in the last 60 days that aren't favourites (from the call history, archive included). */
    val frequents: StateFlow<List<RecentGroup>> = combine(c.history.calls, numberIndex) { calls, index ->
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(60)
        calls.orEmpty()
            .filter { it.date > since && it.number.isNotBlank() && !it.presentationHidden && it.type != CallType.BLOCKED }
            .groupBy { PhoneIdentity.key(it.number, countryIso) }
            .filter { it.value.size >= 2 }
            .entries.sortedByDescending { it.value.size }
            .map { (k, v) -> RecentGroup(k, v.first().number, index[v.first().number], v.first().cachedName, v, false) }
            .filter { it.contact?.starred != true }
            .take(8)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val missedCount: StateFlow<Int> = c.history.calls.map { list -> list.orEmpty().count { it.type == CallType.MISSED && it.isNew } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Recents is on screen: Telecom's missed-call count goes, and so does the re-alert. */
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

    // ---------- Calling ----------

    /** USSD codes typed on the keypad. */
    val ussd = UssdSession(c, viewModelScope)
    private val gate = CallGate(c)

    /**
     * Every call starts here (see [CallGate]): one question for the dial guard, the allowance, confirm-before-call
     * and the SIM, then the call. [simId]: a SIM the user already picked ("call with SIM").
     */
    fun requestCall(
        number: String, name: String? = null, skipConfirm: Boolean = false, simId: String? = null, source: CallSource = CallSource.OTHER,
        subject: String? = null,
    ) {
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
            // A one-tap call (favourite) while the proximity sensor is covered asks first: probably a pocket.
            val pocket = PocketGuard.GUARDED.contains(source) && c.callExtras.config.value.pocketGuard &&
                !EmergencyPolicy.bypasses(EmergencyPolicy.Safeguard.POCKET_GUARD, gate.isEmergency(number)) &&
                PocketGuard.shouldAsk(true, source, ProximityProbe.isCovered(ctx))
            val ask = if (pocket) {
                val covered = DialWarning(str(R.string.pocket_title), str(R.string.pocket_body))
                (p ?: PendingCall(number, name, needConfirm = true, chooseSim = false, simId = simId)).let { it.copy(needConfirm = true, warnings = listOf(covered) + it.warnings) }
            } else {
                p
            }
            // Nothing to ask: the allowance was checked too.
            if (ask != null) pendingCall.value = ask.copy(subject = subject) else place(number, simId, confirmed = true, subject = subject)
        }
    }

    /** [confirmed]: the user already said yes to a used-up call-time allowance. */
    fun place(number: String, simId: String?, remember: Boolean = false, confirmed: Boolean = false, subject: String? = null) {
        pendingCall.value = null
        if (Ussd.isUssd(number)) {
            ussd.start(number.trim(), sims.value, simId)
            return
        }
        viewModelScope.launch {
            when (val r = gate.place(number, simId, contactFor(number)?.displayName, sims.value, remember, confirmed, subject)) {
                is CallGate.Placed.Ask -> pendingCall.value = r.pending
                is CallGate.Placed.Done -> (r.result as? PlaceResult.Failed)?.let { toast(DialText.placeFailure(getApplication(), it.reason)) }
            }
        }
    }

    fun callVoicemail() {
        when (val r = c.placer.callVoicemail()) {
            is PlaceResult.Failed -> toast(DialText.placeFailure(getApplication(), r.reason))
            else -> Unit
        }
    }

    /**
     * Makes a phone contact private ([app.parley.ui.contact.ContactConversions.makePrivate]): into the vault with
     * every field, and what Parley keeps about them re-keyed to it; no readable copy stays outside the vault (no
     * journal entry, snapshots purged). Throws [app.parley.data.vault.VaultCrypto.LockedException] if locked.
     */
    /** The Contacts tab's bulk "Move to private": in this scope, so closing its dialog never stops it. */
    val privateMoves: PrivateMoves by lazy { PrivateMoves(c, viewModelScope) }

    suspend fun moveToVault(contactId: Long, d: ContactDetails): Long {
        val moved = app.parley.ui.contact.ContactConversions(c).makePrivate(contactId, d)
        when {
            moved.messengerCopies -> toast(str(R.string.vm_messenger_copies_remain))
            moved.removedAfterSync -> toast(str(R.string.vm_removed_after_sync))
        }
        return moved.vaultId
    }

    /**
     * Deletes contacts (the journal keeps a copy for 30 days) and offers undo. Private contacts (negative ids, as the
     * Contacts list shows them) are deleted from the vault with what Parley kept about them; like before, the vault
     * keeps no copy of them anywhere.
     */
    fun deleteContacts(ids: List<Long>) {
        viewModelScope.launch {
            val (private, device) = ids.partition { it < 0 }
            val conversions = app.parley.ui.contact.ContactConversions(c)
            // A private contact whose sealed copy couldn't be kept isn't deleted: the user is offered to delete it anyway.
            val notDeleted = private.filterNot { suspendRunCatching { conversions.deletePrivate(-it) }.getOrDefault(false) }
            if (notDeleted.isNotEmpty()) {
                events.trySend(
                    UiEvent.Offer(plural(R.plurals.vault_delete_no_copy_bulk, notDeleted.size, notDeleted.size), str(R.string.vault_delete_no_copy_confirm)) {
                        notDeleted.forEach { conversions.deletePrivate(-it, keepCopy = false) }
                    },
                )
            }
            val deleted = ids.size - notDeleted.size
            if (device.isEmpty()) {
                if (deleted > 0) toast(plural(R.plurals.vm_contacts_deleted, deleted, deleted))
                return@launch
            }
            // Relations Parley wrote on other contacts for these ("Child: Sam" on Ana) go with them.
            if (c.people.relationMirrors.any()) {
                device.forEach { id ->
                    val key = withContext(Dispatchers.IO) { c.contacts.lookupKeyOf(id) } ?: return@forEach
                    suspendRunCatching { c.people.relationMirrors.takeBack(id, key) }
                }
            }
            try {
                c.contacts.delete(device)
            } catch (e: Exception) {
                toast(e.message ?: str(R.string.vm_couldnt_delete))
                return@launch
            }
            val journal = c.contacts.lastJournalIds
            val text = plural(R.plurals.vm_contacts_deleted, deleted, deleted)
            if (journal.isNotEmpty()) events.trySend(UiEvent.Undo(text, journal)) else toast(text)
        }
    }

    /** Deletes one Recents row's calls, offering Undo (the archive keeps them 30 days). */
    /** [keepPrivate]: leaves out calls with private contacts the vault hasn't moved out of the system log yet. */
    fun deleteCallsWithUndo(entries: List<CallEntry>, keepPrivate: Boolean = false) {
        viewModelScope.launch {
            val privateNumbers = HashMap<String, Boolean>()
            val kept = if (!keepPrivate) entries else entries.filter { e ->
                e.number.isBlank() || !privateNumbers.getOrPut(e.number) { runCatching { c.vault.lookup(e.number, countryIso) != null }.getOrDefault(true) }
            }
            if (kept.isEmpty()) return@launch
            val batch = c.history.delete(kept.filter { it.id > 0 })
            val text = plural(R.plurals.vm_calls_deleted, kept.size, kept.size)
            if (batch != null) events.trySend(UiEvent.UndoCalls(text, batch)) else toast(text)
        }
    }

    fun undo(journalIds: List<Long>) {
        viewModelScope.launch {
            journalIds.forEach { c.journal.restore(it) }
            toast(str(R.string.vm_restored))
        }
    }

    fun blockNumber(number: String) {
        viewModelScope.launch {
            val ok = c.blocks.blockNumber(number)
            toast(if (ok) str(R.string.vm_blocked, Bidi.ltr(number)) else str(R.string.vm_couldnt_block))
        }
    }

    fun unblockNumber(number: String) {
        viewModelScope.launch {
            c.blocks.unblockNumber(number)
            toast(str(R.string.vm_unblocked, Bidi.ltr(number)))
        }
    }

    // Declared last: needs [favorites] initialised.
    init {
        // Folder sync shortly after local contact changes (no-op when nothing changed).
        viewModelScope.launch(Dispatchers.IO) {
            c.contacts.contacts.debounce(20_000).collect {
                val st = c.folderSync.status.value
                if (it != null && st.folderUri != null && st.auto) {
                    runCatching { c.folderSync.syncNow() }
                    FolderSyncNotice.update(getApplication(), c.folderSync.status.value)
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            favorites.debounce(1000).distinctUntilChanged().collect { Shortcuts.updateDynamic(getApplication(), it) }
        }
    }
}
