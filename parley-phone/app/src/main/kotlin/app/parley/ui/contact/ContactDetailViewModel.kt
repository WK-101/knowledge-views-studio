package app.parley.ui.contact

import app.parley.data.security.Concealment
import app.parley.calls.ExpectedCallHints
import app.parley.calls.NumberSignals
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.parley.R
import app.parley.common.CallEntry
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.history.CallLogIndex
import app.parley.common.people.ContactRef
import app.parley.common.people.ContactStorage
import app.parley.common.people.ContactVariants
import app.parley.common.people.storage
import app.parley.common.circle.InteractionType
import app.parley.common.circle.Interactions
import app.parley.common.people.MessengerPrefs
import app.parley.common.people.OtherFields
import app.parley.common.people.RelationLinks
import app.parley.common.suspendRunCatching
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.MessengerAction
import app.parley.data.Messengers
import app.parley.data.circle.Interaction
import app.parley.data.circle.InteractionStore
import app.parley.data.db.CallNoteEntity
import app.parley.data.db.ContactMetaEntity
import app.parley.data.db.NumberSimEntity
import app.parley.data.db.TemporaryContactEntity
import app.parley.data.vault.VaultCrypto
import app.parley.data.people.ParleyRelationRows
import app.parley.data.people.RelationFromOther
import app.parley.data.people.RelationsFromOthers
import java.time.ZoneId
import app.parley.ui.circle.PersonMemory
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything a contact's page shows, read for this one person. */
data class ContactDetailUiState(
    /** False until the first read; then a null [details] means the contact is gone. */
    val loaded: Boolean = false,
    val details: ContactDetails? = null,
    /** Parley's own row for them (pinned note, messaging choice, Circle rhythm, relation links). */
    val meta: ContactMetaEntity? = null,
    val interactions: List<Interaction> = emptyList(),
    /** Their calls, newest first (every number of theirs, by line). */
    val history: List<CallEntry> = emptyList(),
    /** Call notes on their numbers. */
    val notes: List<CallNoteEntity> = emptyList(),
    val messengers: List<MessengerAction> = emptyList(),
    /** Kinds Parley doesn't edit, read-only. */
    val otherFields: List<OtherFields.Field> = emptyList(),
    /** Set when this is a temporary contact (its deletion date). */
    val temporary: TemporaryContactEntity? = null,
    val simPrefs: List<NumberSimEntity> = emptyList(),
    /** Every note about them (call notes, interaction notes, pinned note). */
    val memory: PersonMemory = PersonMemory(),
    /** Which contact this is and where it is kept (docs/CONTACT_MODEL.md). */
    val ref: ContactRef? = null,
    /** A private contact whose details need the vault unlocked: only its name, photo and numbers are shown. */
    val access: PrivateAccess = PrivateAccess.OPEN,
    /** A private contact's call insights, over its private call history (device contacts use the shared index). */
    val privateIndex: CallLogIndex? = null,
) {
    val prefs: MessengerPrefs get() = MessengerPrefs.decode(meta?.preferredMessenger)
    val storage: ContactStorage get() = ref?.storage ?: ContactStorage.DEVICE
    val isPrivate: Boolean get() = storage == ContactStorage.PRIVATE
    val variants: ContactVariants get() = ContactVariants(storage, temporary?.expiresAt)
}

/** [ContactDetailViewModel.numberAdvice]: numbers that seem out of service, and a SIM to suggest. */
data class NumberAdviceUi(val dead: Set<String> = emptySet(), val simTip: NumberSignals.SimTip? = null)

/** Whether a private contact's details can be read now; a device contact is always [OPEN]. */
enum class PrivateAccess {
    OPEN,

    /**
     * The page shows what the caller-ID copy holds (name, numbers, photo, job, the note for calls) while the sealed
     * details are being opened; nothing about it looks locked.
     */
    OPENING,

    /** The vault must be unlocked first (VaultCrypto.LockedException): the page offers to unlock. */
    LOCKED,

    /** The Keystore can't open them right now; nothing is overwritten, try again. */
    UNAVAILABLE,

    /** Their key is gone for good: what the caller-ID copy holds is shown, and "Keep what's left" is offered. */
    LOST,
}

/** What a tap on a relation leads to. */
sealed interface RelationTarget {
    data class Contact(val id: Long) : RelationTarget

    /** Several namesakes: the user picks one. */
    data class Choose(val people: List<ContactSummary>) : RelationTarget
    data class None(val name: String) : RelationTarget
}

/**
 * A contact's page: the contact, Parley's metadata for them, their calls, notes, interactions and messenger rows,
 * each read for this person only (not whole tables), and the page's actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactDetailViewModel(private val c: DataContainer) : ViewModel() {
    val countryIso: String = c.directory.countryIso

    /** The page's contact: [ContactRef.navId] is what the route carries (negative for a private contact). */
    private val ref = MutableStateFlow<ContactRef?>(null)

    /** Bumped after a write the provider doesn't announce (default number, send to voicemail…). */
    private val reloads = MutableStateFlow(0)

    private val messages = Channel<String>(Channel.BUFFERED)

    /** Short confirmations and errors for the snackbar. */
    val events: Flow<String> = messages.receiveAsFlow()

    fun start(id: Long) {
        ref.value = ContactRef.ofNavId(id)
    }

    fun reload() {
        reloads.update { it + 1 }
    }

    /** The contact (re-read whenever the contacts change), its messenger rows and its other fields. */
    private data class Loaded(
        val details: ContactDetails?,
        val messengers: List<MessengerAction>,
        val otherFields: List<OtherFields.Field>,
        val ref: ContactRef,
        val access: PrivateAccess = PrivateAccess.OPEN,
        /** A private contact's expiry and call-history choice (a device contact's is in [TemporaryContactEntity]). */
        val privateExpiry: Pair<Long, Boolean>? = null,
    )

    private val loaded: StateFlow<Loaded?> = ref.filterNotNull().flatMapLatest { r ->
        if (r is ContactRef.Private) privateLoads(r) else deviceLoads(r as ContactRef.Device)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    private fun deviceLoads(r: ContactRef.Device): Flow<Loaded> = combine(c.contacts.contacts, c.vault.contacts, reloads) { _, _, _ -> r }
        .mapLatest {
            val id = r.contactId
            val details = c.contacts.details(id)
            val messengers = withContext(Dispatchers.IO) { runCatching { Messengers.actions(c.appContext, id) }.getOrDefault(emptyList()) }
            val other = withContext(Dispatchers.IO) {
                runCatching {
                    c.records.read(id, fullPhoto = false)?.raws.orEmpty().flatMap { raw ->
                        val messenger = app.parley.common.record.Messengers.isMessengerAccount(raw.accountType)
                        OtherFields.describe(raw.rows) { messenger }
                    }.distinctBy { it.label to it.value }
                }.getOrDefault(emptyList())
            }
            Loaded(details, messengers, other, r)
        }

    /**
     * A private contact from the vault, shaped like a device contact for the page: its Parley key
     * ([ContactRef.privateKey]) as lookup key, so notes, the Circle, logged moments and the call-screen picture are
     * found the same way, and its in-app photo.
     *
     * It shows at once from the caller-ID copy (name, numbers, photo, job, the note for calls: [PrivateAccess.OPENING]),
     * then the sealed details fill in, opened once ([app.parley.data.vault.VaultRepository.open]). Locked: what the
     * caller-ID copy holds, which calls show anyway; everything else waits for the unlock, with VaultCrypto's locking
     * unchanged. Read again only when this entry changes (or [reload]), never for another contact's change.
     */
    private fun privateLoads(r: ContactRef.Private): Flow<Loaded> {
        // The listing starts empty: the entry's own row tells "not listed yet" from "gone", so the listing arriving
        // later isn't a change (it would open the details a second time).
        val entry = c.vault.contacts.map { list -> list.firstOrNull { it.id == r.vaultId } ?: c.vault.summary(r.vaultId) }.distinctUntilChanged()
        var shown: Loaded? = null
        // I21: after a duress unlock a private contact doesn't exist, whichever link, widget or notification opens it.
        val hiding = Concealment.state.map { it.hiding }.distinctUntilChanged()
        return combine(entry, reloads, hiding) { s, _, hidden -> s.takeUnless { hidden } }.transformLatest { summary ->
            if (summary == null) {
                emit(Loaded(null, emptyList(), emptyList(), r))
                return@transformLatest
            }
            val expiry = summary.expiresAt?.let { it to summary.purgeHistory }
            // The photo's file is looked at off the main thread (this runs in viewModelScope; StrictMode flags it there).
            val photo = withContext(Dispatchers.IO) { c.vault.photoUri(r.vaultId) }
            fun forPage(d: ContactDetails) = d.copy(id = r.navId, lookupKey = ContactRef.privateKey(r.vaultId), photoUri = photo)
            val quick = forPage(
                c.vault.callerCopy(r.vaultId) ?: ContactDetails(
                    displayName = summary.name, given = summary.name, starred = summary.starred,
                    phones = summary.numbers.map { DataItem(null, it, android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, null) },
                ),
            )
            // First time only: once the details were shown, a refresh keeps them until the new ones are open.
            if (shown?.access != PrivateAccess.OPEN) emit(Loaded(quick, emptyList(), emptyList(), r, PrivateAccess.OPENING, expiry))
            val full = try {
                val o = c.vault.open(r.vaultId)
                if (o == null) {
                    Loaded(null, emptyList(), emptyList(), r)
                } else {
                    // Office and job description, read-only as for a device contact (kept in the sealed details).
                    val work = OtherFields.workExtras(o.details.officeLocation, o.details.jobDescription)
                    Loaded(forPage(o.details), emptyList(), work, r, if (o.lost) PrivateAccess.LOST else PrivateAccess.OPEN, expiry)
                }
            } catch (_: VaultCrypto.LockedException) {
                Loaded(quick, emptyList(), emptyList(), r, PrivateAccess.LOCKED, expiry)
            } catch (_: VaultCrypto.KeyUnavailableException) {
                Loaded(quick, emptyList(), emptyList(), r, PrivateAccess.UNAVAILABLE, expiry)
            }
            shown = full
            emit(full)
        }
    }

    private val lookupKey = loaded.map { it?.details?.lookupKey.orEmpty() }.distinctUntilChanged()
    private val phones = loaded.map { it?.details?.phones.orEmpty().map { p -> p.value } }.distinctUntilChanged()

    /** Call notes are stored under each line's key, or under the old last-digits key until migrated. */
    private val numberKeys = phones.map { list -> list.flatMap { PhoneIdentity.lookupKeys(it, countryIso) }.toSet() }.distinctUntilChanged()

    /**
     * Parley's row for them. A private contact's note for calls and usual app live in its sealed vault entry (the call
     * screen reads the note from there while the phone is locked), so they are read from its details instead.
     */
    private val meta = combine(lookupKey.flatMapLatest { k -> if (k.isEmpty()) flowOf(null) else c.meta.metaFlow(k) }, loaded) { m, l ->
        val d = l?.details
        if (l?.ref !is ContactRef.Private || d == null) return@combine m
        // While opening, the note for calls comes from the caller-ID copy (it is kept there for the call screen).
        if (l.access != PrivateAccess.OPEN && l.access != PrivateAccess.LOST && l.access != PrivateAccess.OPENING) return@combine m
        (m ?: ContactMetaEntity(d.lookupKey, contactId = d.id)).copy(
            pinnedNote = d.pinnedNote.ifBlank { null }, preferredMessenger = d.messengerPrefs.ifBlank { null },
        )
    }

    /** When they delete themselves: the temporary-contacts table for a device contact, the vault entry for a private one. */
    private val temporary = combine(lookupKey.flatMapLatest { k -> if (k.isEmpty()) flowOf(null) else c.meta.temporaryFlow(k) }, loaded) { t, l ->
        val (at, purge) = l?.privateExpiry ?: return@combine t.takeIf { l?.ref !is ContactRef.Private }
        TemporaryContactEntity(l.details?.lookupKey.orEmpty(), l.ref.navId, at, purge)
    }

    // Logged interactions for the timeline and the Stay in touch card.
    private val interactions = lookupKey.flatMapLatest { k -> if (k.isEmpty()) flowOf(emptyList()) else c.circle.interactions.interactions(k) }
    private val notes = numberKeys.flatMapLatest { keys -> if (keys.isEmpty()) flowOf(emptyList()) else c.meta.callNotesAny(keys.toList()) }

    // Same line by E.164 (read with this phone's country), not by the last 9 digits.
    // A private contact's calls are in its private call history (or still in the phone's, before they're moved).
    private val history = combine(phones, c.history.calls, c.history.callsWithPrivate, ref) { mine, calls, withPrivate, r ->
        val all = if (r is ContactRef.Private) withPrivate else calls
        if (mine.isEmpty()) emptyList() else PhoneIdentity.LineSet(mine, countryIso).let { set -> all.orEmpty().filter { e -> e.number in set } }
    }.flowOn(Dispatchers.Default)

    private data class Personal(val meta: ContactMetaEntity?, val interactions: List<Interaction>, val notes: List<CallNoteEntity>, val temporary: TemporaryContactEntity?)

    private val personal = combine(meta, interactions, notes, temporary) { m, i, n, t -> Personal(m, i, n, t) }

    // The person's notes, re-read when any of their sources changes.
    private val memory = combine(lookupKey, numberKeys, personal) { k, keys, _ -> k to keys }
        .mapLatest { (k, keys) -> PersonMemory(suspendRunCatching { c.circle.notesFor(k, keys) }.getOrDefault(emptyList())) }

    val state: StateFlow<ContactDetailUiState> = combine(loaded, personal, history, c.prefs.numberSims, memory) { l, p, h, sims, mem ->
        if (l == null) {
            ContactDetailUiState()
        } else {
            val privateIndex = if (l.ref is ContactRef.Private && h.isNotEmpty()) CallLogIndex.build(h, null, countryIso, ZoneId.systemDefault()) else null
            ContactDetailUiState(
                loaded = true, details = l.details, meta = p.meta, interactions = p.interactions, history = h, notes = p.notes,
                messengers = l.messengers, otherFields = l.otherFields, temporary = p.temporary, simPrefs = sims, memory = mem,
                ref = l.ref, access = l.access, privateIndex = privateIndex,
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), ContactDetailUiState())

    /**
     * What Parley noticed about this person's numbers from their calls: the ones that seem out of service (a quiet
     * hint beside each) and, on a dual-SIM phone, a SIM their calls go better on. Read again when calls, answers or
     * remembered SIMs change.
     */
    val numberAdvice: StateFlow<NumberAdviceUi> =
        combine(phones, history, c.callQuality.version, c.numberAdvice.version, c.prefs.numberSims) { mine, calls, _, _, _ -> mine to calls }
            .mapLatest { (mine, calls) ->
                if (mine.isEmpty()) return@mapLatest NumberAdviceUi()
                val dead = suspendRunCatching { NumberSignals.deadAmong(c, mine, calls) }.getOrDefault(emptySet())
                val sims = withContext(Dispatchers.IO) { c.sims.accounts() }
                // A number that seems out of service isn't a reason to pick a SIM.
                val tip = suspendRunCatching { NumberSignals.simTip(c, mine - dead, sims) }.getOrNull()
                NumberAdviceUi(dead, tip)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), NumberAdviceUi())

    /** "Use SIM 2 for Ana" ([accept]) or its dismissal; either way it isn't suggested again. */
    fun answerSimTip(tip: NumberSignals.SimTip, accept: Boolean) = launch {
        NumberSignals.answerSim(c, current?.phones.orEmpty().map { it.value }, tip, accept)
    }

    /** Relations other contacts give this one where one of the two is private ([RelationsFromOthers]). */
    val relationsFromOthers: StateFlow<List<RelationFromOther>> =
        combine(loaded, c.meta.allMeta(), c.settings.settings, c.vault.contacts) { l, metas, s, _ -> Triple(l, metas, s) }
            .mapLatest { (l, metas, s) ->
                val d = l?.details
                if (d == null || l.access != PrivateAccess.OPEN) {
                    emptyList()
                } else {
                    suspendRunCatching { RelationsFromOthers.load(c, d, l.ref is ContactRef.Private, metas, s) }.getOrDefault(emptyList())
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyList())

    /**
     * This contact's relations kept in Parley only ([ParleyRelationRows]): shown with its other relations, not in
     * discreet mode (they name private contacts).
     */
    val parleyRelations: StateFlow<List<DataItem>> =
        combine(state.map { it.meta?.parleyRelations }.distinctUntilChanged(), c.settings.settings.map { it.hideVault }.distinctUntilChanged()) { s, hide ->
            if (hide) emptyList() else ParleyRelationRows.decode(s)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyList())

    val circleConfig = c.circle.config

    private val current: ContactDetails? get() = state.value.details

    /** The route's id: the contact id, or the negated vault id of a private contact. */
    private val id: Long get() = ref.value?.navId ?: 0L
    private val vaultId: Long? get() = (ref.value as? ContactRef.Private)?.vaultId

    private fun say(res: Int, vararg args: Any) {
        messages.trySend(c.appContext.getString(res, *args))
    }

    // ---------------------------------------------------------------- actions

    /**
     * Changes a private contact's sealed details: read fresh from the vault (never the page's copy, which carries its
     * Parley key and in-app photo), changed, saved back with its expiry kept. Needs the vault unlocked.
     */
    private suspend fun updatePrivate(change: (ContactDetails) -> ContactDetails): Boolean {
        val v = vaultId ?: return false
        return try {
            // Details whose key is gone come back rebuilt from the caller-ID copy: saving them would replace the sealed
            // record without the user's "Keep what's left" choice on the page.
            if (c.vault.detailsLost(v)) {
                say(R.string.vault_details_lost_change)
                return false
            }
            val d = c.vault.details(v) ?: return false
            c.vault.save(v, change(d))
            reload()
            true
        } catch (_: VaultCrypto.LockedException) {
            say(R.string.contact_unlock_to_change)
            false
        } catch (_: VaultCrypto.KeyUnavailableException) {
            say(R.string.vault_details_unavailable)
            false
        }
    }

    /**
     * Changes what a private contact's caller-ID copy keeps (star, ringtone, "send to voicemail"): no unlock needed,
     * the sealed details aren't rewritten, and the call path applies it while the phone is locked.
     */
    private suspend fun updatePrivateChoices(change: (app.parley.data.vault.VaultSummary) -> app.parley.data.vault.VaultSummary) {
        val v = vaultId ?: return
        if (!c.vault.updateCallerChoices(v, change)) say(R.string.vault_details_unavailable)
        reload()
    }

    /** Favourites: the address book's star, or Parley's own for a private contact (other apps never see it). */
    fun setStarred(on: Boolean) = launch {
        if (vaultId != null) updatePrivateChoices { it.copy(starred = on) } else c.contacts.setStarred(id, on)
    }

    /** The contact's ringtone: the address book's, or for a private contact Parley's own (its ringer plays it). */
    fun setRingtone(uri: Uri?) = launch {
        if (vaultId != null) updatePrivateChoices { it.copy(ringtone = uri?.toString()) } else c.contacts.setRingtone(id, uri?.toString())
        // A tune made from a name that this one replaced goes once nothing else uses it (L8).
        CallerTunes.sweep(c)
    }

    /** "Send to voicemail": Android applies a device contact's; Parley's call screening declines a private contact's calls. */
    fun setSendToVoicemail(on: Boolean) = launch {
        if (vaultId != null) {
            updatePrivateChoices { it.copy(sendToVoicemail = on) }
            return@launch
        }
        c.contacts.setSendToVoicemail(id, on)
        reload()
    }

    /** A birthday or anniversary from the page's date chips: into the address book, or a private contact's sealed details. */
    suspend fun addDate(d: ContactDetails, event: app.parley.data.EventItem): Boolean {
        if (vaultId != null) return updatePrivate { it.copy(events = it.events + event) }
        val saved = runCatching { c.contacts.save(d, d.copy(events = d.events + event), account = null, photo = null, removePhoto = false) }.getOrNull()
        if (saved != null) reload()
        return saved != null
    }

    /** Unlinks the contact's copies; [then] runs once done (the page closes). */
    fun separate(then: () -> Unit) = launch {
        c.contacts.separate(id)
        then()
    }

    fun vcardUri(lookupKey: String): Uri = c.contacts.vcardUri(lookupKey)

    /** Makes [item] the default of its kind, or clears the default. */
    fun setDefault(item: DataItem, mime: String, on: Boolean) = launch {
        if (vaultId != null) {
            // Kept in the sealed details: the chosen row is marked primary among its kind.
            fun mark(list: List<DataItem>) = list.map { it.copy(isPrimary = on && it.value == item.value) }
            val email = mime == android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
            val ok = updatePrivate { d -> if (email) d.copy(emails = mark(d.emails)) else d.copy(phones = mark(d.phones)) }
            if (ok) say(if (on) R.string.detail_default_set else R.string.detail_default_removed)
            return@launch
        }
        val dataId = item.id ?: return@launch
        val ok = if (on) c.contacts.setDefault(dataId) else c.contacts.clearDefault(id, mime)
        say(
            when {
                !ok -> R.string.detail_default_failed
                on -> R.string.detail_default_set
                else -> R.string.detail_default_removed
            },
        )
        reload()
    }

    /** The note shown when they call; blank removes it. */
    fun setPinnedNote(text: String) = launch {
        // A private contact's note is sealed with it and copied for the call screen (VaultRepository.save).
        if (vaultId != null) {
            updatePrivate { it.copy(pinnedNote = text.trim()) }
            return@launch
        }
        val key = current?.lookupKey?.takeIf { it.isNotEmpty() } ?: return@launch
        // The contact id is kept beside the key so the row can follow a key change. Targeted writes, so the
        // Circle's dialog (which writes the same row) never loses an update.
        c.meta.ensureMeta(key, id)
        c.meta.setPinnedNote(key, id, text.trim().ifEmpty { null })
    }

    fun setMessengerPrefs(p: MessengerPrefs) = launch {
        if (vaultId != null) {
            updatePrivate { it.copy(messengerPrefs = p.encode().orEmpty()) }
            return@launch
        }
        val key = current?.lookupKey?.takeIf { it.isNotEmpty() } ?: return@launch
        c.meta.ensureMeta(key, id)
        c.meta.setPreferredMessenger(key, id, p.encode())
    }

    /** Remembers a life event yearly in the digest, or stops. */
    fun setYearly(key: String, on: Boolean) {
        val lookup = current?.lookupKey ?: return
        launch { c.circle.setYearly(lookup, id, key, on) }
        say(if (on) R.string.circle_yearly_on else R.string.circle_yearly_off)
    }

    fun setSimFor(number: String, simId: String?) = launch { c.prefs.setSimFor(number, simId) }

    /** "Delete after…" (null: keep them). */
    fun setExpiry(days: Int?) = launch {
        val d = current ?: return@launch
        val v = vaultId
        when {
            // A private contact's expiry is its vault entry's own; its call history goes with it, as for others.
            v != null && days == null -> c.vault.setExpiry(v, null)
            v != null -> {
                val at = System.currentTimeMillis() + days!! * app.parley.common.people.TemporaryChoice.DAY_MS
                // A new date keeps a temporary contact's call-history choice; only one made temporary now takes the
                // default. The sealed details aren't re-sealed for this (nor over a lost detail key).
                val already = c.vault.summary(v)?.expiresAt != null
                c.vault.setExpiry(v, at, app.parley.common.people.TemporaryChoice.purgeOnNewDate(already))
            }
            days == null -> c.temporaries.clear(d.lookupKey)
            else -> c.temporaries.mark(id, days, purgeHistory = c.temporaries.forKey(d.lookupKey)?.purgeHistory ?: true)
        }
        reload()
        messages.trySend(
            if (days == null) c.appContext.getString(R.string.detail_kept)
            else c.appContext.resources.getQuantityString(R.plurals.detail_deletes_in_days, days, days),
        )
    }

    /** Logs a new interaction (or edits [initial]); a note that can't be encrypted isn't saved. */
    fun saveInteraction(initial: Interaction?, type: InteractionType, note: String?, time: Long) = launch {
        val d = current ?: return@launch
        try {
            val entry = if (initial == null) {
                c.circle.interactions.log(d.lookupKey, id, type, null, time, note, Interactions.manualKey(UUID.randomUUID().toString()))
                    .also { say(R.string.circle_logged, d.given.ifBlank { d.displayName }) }
            } else {
                c.circle.interactions.edit(initial.id, type, note, time.takeIf { it != initial.time })
                initial.id
            }
            // I7: "will call Tue" in the note can expect that call; an edit that drops the promise withdraws it.
            if (entry != null) {
                val key = ExpectedCallHints.loggedKey(entry)
                runCatching { ExpectedCallHints.noteSaved(c, d.displayName, note, key, privateName = ContactRef.isPrivateKey(d.lookupKey)) }
            }
        } catch (_: InteractionStore.SealException) {
            say(R.string.circle_note_failed)
        }
    }

    /** A private contact whose detail key is gone for good: keep the name, numbers and caller card under the new key. */
    fun keepWhatIsLeft() = launch {
        val v = vaultId ?: return@launch
        if (c.vault.keepWhatIsLeft(v)) say(R.string.vault_details_kept)
        reload()
    }

    /**
     * Deletes this private contact with everything Parley kept about it; [then] runs once done. [keepCopy] false
     * deletes it without a copy in History & undo. When the copy couldn't be kept nothing is deleted and [noCopy] runs,
     * so the page can ask whether to delete without one.
     */
    fun deletePrivate(keepCopy: Boolean = true, noCopy: () -> Unit = {}, then: () -> Unit) = launch {
        val v = vaultId ?: return@launch
        if (ContactConversions(c).deletePrivate(v, keepCopy)) then() else noCopy()
    }

    /**
     * "Make visible to other apps": back to the address book (lossless, re-keyed). Throws
     * [VaultCrypto.LockedException] when the vault must be unlocked first. Returns what happened.
     */
    suspend fun makeVisible(account: app.parley.data.AccountRef): ContactConversions.MadeVisible {
        val v = vaultId ?: return ContactConversions.MadeVisible.NotWritten
        val d = c.vault.details(v) ?: return ContactConversions.MadeVisible.NotWritten
        return ContactConversions(c).makeVisible(v, d, account)
    }

    /** A relation's contact: by the remembered lookup key first, then by name; several namesakes: ask. */
    fun openRelation(name: String, onResult: (RelationTarget) -> Unit) = launch {
        val link = RelationLinks.decode(state.value.meta?.relationLinks)[RelationLinks.nameKey(name)]
        // A link to a private contact follows its private key, never the address book.
        ContactRef.vaultIdOf(link?.lookupKey)?.let { v ->
            // Discreet mode hides private contacts everywhere, a relation's link included.
            val shown = !c.settings.current().hideVault && c.vault.summariesNow().any { it.id == v }
            if (shown) return@launch onResult(RelationTarget.Contact(ContactRef.Private(v).navId))
        }
        val all = c.directory.contacts.value ?: c.contacts.snapshot()
        // A private contact that is gone (or hidden) is never looked for in the address book by its key.
        val deviceLink = link?.takeUnless { ContactRef.isPrivateKey(it.lookupKey) }
        val target = withContext(Dispatchers.IO) {
            val people = all.map { it.id to it.displayName }
            RelationLinks.resolve(name, deviceLink, { l -> c.contacts.currentOf(l.lookupKey, l.contactId)?.first }, people, self = id)
        }
        onResult(
            when (target) {
                is RelationLinks.Target.Contact -> RelationTarget.Contact(target.id)
                is RelationLinks.Target.Choose -> RelationTarget.Choose(target.ids.mapNotNull { i -> all.firstOrNull { it.id == i } })
                RelationLinks.Target.None -> RelationTarget.None(name)
            },
        )
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}
