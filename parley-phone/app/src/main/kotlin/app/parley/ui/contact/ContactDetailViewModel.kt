package app.parley.ui.contact

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.parley.R
import app.parley.common.CallEntry
import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
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
) {
    val prefs: MessengerPrefs get() = MessengerPrefs.decode(meta?.preferredMessenger)
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

    private val contactId = MutableStateFlow<Long?>(null)

    /** Bumped after a write the provider doesn't announce (default number, send to voicemail…). */
    private val reloads = MutableStateFlow(0)

    private val messages = Channel<String>(Channel.BUFFERED)

    /** Short confirmations and errors for the snackbar. */
    val events: Flow<String> = messages.receiveAsFlow()

    fun start(id: Long) {
        contactId.value = id
    }

    fun reload() {
        reloads.update { it + 1 }
    }

    /** The contact (re-read whenever the contacts change), its messenger rows and its other fields. */
    private data class Loaded(val details: ContactDetails?, val messengers: List<MessengerAction>, val otherFields: List<OtherFields.Field>)

    private val loaded: StateFlow<Loaded?> = combine(contactId.filterNotNull(), c.contacts.contacts, reloads) { id, _, _ -> id }
        .mapLatest { id ->
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
            Loaded(details, messengers, other)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    private val lookupKey = loaded.map { it?.details?.lookupKey.orEmpty() }.distinctUntilChanged()
    private val phones = loaded.map { it?.details?.phones.orEmpty().map { p -> p.value } }.distinctUntilChanged()

    /** Call notes are stored under each line's key, or under the old last-digits key until migrated. */
    private val numberKeys = phones.map { list -> list.flatMap { PhoneIdentity.lookupKeys(it, countryIso) }.toSet() }.distinctUntilChanged()

    private val meta = lookupKey.flatMapLatest { k -> if (k.isEmpty()) flowOf(null) else c.meta.metaFlow(k) }
    private val temporary = lookupKey.flatMapLatest { k -> if (k.isEmpty()) flowOf(null) else c.meta.temporaryFlow(k) }

    // Logged interactions for the timeline and the Stay in touch card.
    private val interactions = lookupKey.flatMapLatest { k -> if (k.isEmpty()) flowOf(emptyList()) else c.circle.interactions.interactions(k) }
    private val notes = numberKeys.flatMapLatest { keys -> if (keys.isEmpty()) flowOf(emptyList()) else c.meta.callNotesAny(keys.toList()) }

    // Same line by E.164 (read with this phone's country), not by the last 9 digits.
    private val history = combine(phones, c.history.calls) { mine, calls ->
        if (mine.isEmpty()) emptyList() else PhoneIdentity.LineSet(mine, countryIso).let { set -> calls.orEmpty().filter { e -> e.number in set } }
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
            ContactDetailUiState(
                loaded = true, details = l.details, meta = p.meta, interactions = p.interactions, history = h, notes = p.notes,
                messengers = l.messengers, otherFields = l.otherFields, temporary = p.temporary, simPrefs = sims, memory = mem,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), ContactDetailUiState())

    val circleConfig = c.circle.config

    private val current: ContactDetails? get() = state.value.details
    private val id: Long get() = contactId.value ?: 0L

    private fun say(res: Int, vararg args: Any) {
        messages.trySend(c.appContext.getString(res, *args))
    }

    // ---------------------------------------------------------------- actions

    fun setStarred(on: Boolean) = launch { c.contacts.setStarred(id, on) }

    fun setRingtone(uri: Uri?) = launch { c.contacts.setRingtone(id, uri?.toString()) }

    fun setSendToVoicemail(on: Boolean) = launch {
        c.contacts.setSendToVoicemail(id, on)
        reload()
    }

    /** Unlinks the contact's copies; [then] runs once done (the page closes). */
    fun separate(then: () -> Unit) = launch {
        c.contacts.separate(id)
        then()
    }

    fun vcardUri(lookupKey: String): Uri = c.contacts.vcardUri(lookupKey)

    /** Makes [item] the default of its kind, or clears the default. */
    fun setDefault(item: DataItem, mime: String, on: Boolean) = launch {
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
        val key = current?.lookupKey?.takeIf { it.isNotEmpty() } ?: return@launch
        // The contact id is kept beside the key so the row can follow a key change. Targeted writes, so the
        // Circle's dialog (which writes the same row) never loses an update.
        c.meta.ensureMeta(key, id)
        c.meta.setPinnedNote(key, id, text.trim().ifEmpty { null })
    }

    fun setMessengerPrefs(p: MessengerPrefs) = launch {
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
        if (days == null) c.temporaries.clear(d.lookupKey) else c.temporaries.mark(id, days, purgeHistory = true)
        messages.trySend(
            if (days == null) c.appContext.getString(R.string.detail_kept)
            else c.appContext.resources.getQuantityString(R.plurals.detail_deletes_in_days, days, days),
        )
    }

    /** Logs a new interaction (or edits [initial]); a note that can't be encrypted isn't saved. */
    fun saveInteraction(initial: Interaction?, type: InteractionType, note: String?, time: Long) = launch {
        val d = current ?: return@launch
        try {
            if (initial == null) {
                c.circle.interactions.log(d.lookupKey, id, type, null, time, note, Interactions.manualKey(UUID.randomUUID().toString()))
                say(R.string.circle_logged, d.given.ifBlank { d.displayName })
            } else {
                c.circle.interactions.edit(initial.id, type, note, time.takeIf { it != initial.time })
            }
        } catch (_: InteractionStore.SealException) {
            say(R.string.circle_note_failed)
        }
    }

    /** After a move into the vault: the metadata is kept encrypted with them, so no plaintext copy stays. */
    suspend fun forgetMeta(lookupKey: String) {
        if (state.value.meta != null) c.meta.deleteMeta(lookupKey)
    }

    /** A relation's contact: by the remembered lookup key first, then by name; several namesakes: ask. */
    fun openRelation(name: String, onResult: (RelationTarget) -> Unit) = launch {
        val link = RelationLinks.decode(state.value.meta?.relationLinks)[RelationLinks.nameKey(name)]
        val all = c.directory.contacts.value ?: c.contacts.snapshot()
        val target = withContext(Dispatchers.IO) {
            RelationLinks.resolve(name, link, { l -> c.contacts.currentOf(l.lookupKey, l.contactId)?.first }, all.map { it.id to it.displayName }, self = id)
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
