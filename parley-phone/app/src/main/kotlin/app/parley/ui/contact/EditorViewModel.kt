package app.parley.ui.contact

import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.parley.R
import app.parley.common.people.EditorForm
import app.parley.common.people.RelationLinks
import app.parley.common.people.RowKeys
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.ContactDraftJson
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.GroupInfo
import app.parley.data.vault.VaultCrypto
import app.parley.ui.people.BackgroundChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** How the editor was opened (from the route; the same for the life of the screen). */
data class EditorArgs(
    val contactId: Long?,
    val prefillName: String = "",
    val prefillPhone: String = "",
    val prefillEmail: String = "",
    val addPhone: String = "",
    val prefill: ContactDetails? = null,
    /** Private vault mode: 0 = new vault contact, > 0 = edit that vault contact. */
    val vaultId: Long? = null,
    /** Edit one specific copy (raw contact) of the contact. */
    val rawId: Long? = null,
)

sealed interface EditorEvent {
    data class Message(val text: String) : EditorEvent

    /** Leave the editor; [savedId] is the saved contact (negative: a private one), or null when nothing was saved. */
    data class Done(val savedId: Long?) : EditorEvent
}

/**
 * The contact editor's state and its save. The draft, the picked photo, the account and the other choices live here,
 * so rotation, a theme, font or language change keep the edit; they are also written to saved state, so the edit
 * survives the process being stopped in the background (for example while the photo picker is open).
 */
class EditorViewModel(private val c: DataContainer, private val saved: SavedStateHandle) : ViewModel() {
    private val saveContact = SaveContactUseCase(c)

    var args: EditorArgs = EditorArgs(null)
        private set

    /** The contact as loaded (null for a new one); the save compares against it. */
    var original by mutableStateOf<ContactDetails?>(null)
        private set
    var draft by mutableStateOf<ContactDetails?>(null)
        private set

    /** What "unchanged" means: the draft as first shown (null: a new contact from another app, always a change). */
    private var start by mutableStateOf<ContactDetails?>(null)
    var accounts by mutableStateOf<List<AccountRef>>(emptyList())
        private set
    var groups by mutableStateOf<List<GroupInfo>>(emptyList())
        private set
    var account by mutableStateOf<AccountRef?>(null)
        private set
    var photo by mutableStateOf<Uri?>(null)
        private set
    var removePhoto by mutableStateOf(false)
        private set

    /** New contacts go to the private vault when "Private by default" is on (the Save-to menu can change it). */
    var privateNew by mutableStateOf(false)
        private set
    var background by mutableStateOf<BackgroundChange>(BackgroundChange.None)
        private set

    /** Relations whose contact was chosen with the picker (name key → that contact). */
    private var pickedLinks = emptyMap<String, RelationLinks.Link>()
    var moreName by mutableStateOf(false)
    var revealed by mutableStateOf(emptySet<EditorForm.Kind>())
    var saving by mutableStateOf(false)
        private set

    /** A temporary contact just saved: (its lookup key, the saved id) until the user answers the keep question. */
    var askKeep by mutableStateOf<Pair<String, Long>?>(null)
        private set

    /** Stable row keys (animations, focus). */
    val keys = RowKeys()

    private val eventChannel = Channel<EditorEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var started = false

    val isVault: Boolean get() = args.vaultId != null || privateNew
    val isNew: Boolean get() = original == null && (args.vaultId ?: 0L) <= 0L

    /** Something to save: the draft differs from the start (blank new rows aside), or the photo or background changed. */
    val changed: Boolean
        get() {
            val d = draft
            return d != null && (start == null || EditorDrafts.meaningful(d) != start?.let(EditorDrafts::meaningful)) ||
                photo != null || removePhoto || background != BackgroundChange.None
        }

    val canSave: Boolean
        get() {
            val d = draft ?: return false
            return EditorForm.canSave(isNew, changed, EditorForm.hasContent(EditorDrafts.texts(d)) || photo != null, saving)
        }

    init {
        saved.setSavedStateProvider(STATE) { toBundle() }
    }

    /** Called by the screen with its route's arguments; loads once (a restored edit is kept as it was). */
    fun start(a: EditorArgs) {
        args = a
        if (started) return
        started = true
        val restored = saved.get<Bundle>(STATE)
        viewModelScope.launch {
            accounts = withContext(Dispatchers.IO) { c.contacts.accounts() }
            groups = withContext(Dispatchers.IO) { c.contacts.groups() }
            if (restored != null && restore(restored)) return@launch
            load(a)
        }
    }

    private suspend fun load(a: EditorArgs) {
        if (a.contactId == null && a.vaultId == null) privateNew = c.people.prefs.current().privateByDefault
        val s = c.settings.settings.value
        account = accounts.firstOrNull { it.type == s.defaultAccountType && it.name == s.defaultAccountName }
            ?: accounts.firstOrNull { it.type == "com.google" } ?: accounts.firstOrNull()
        fun withPhoneRow(e: ContactDetails) = if (e.phones.isEmpty()) e.copy(phones = listOf(DataItem(type = Phone.TYPE_MOBILE))) else e
        val vaultId = a.vaultId
        if (vaultId != null) {
            val e = if (vaultId > 0) {
                try {
                    c.vault.details(vaultId)
                } catch (_: VaultCrypto.LockedException) {
                    eventChannel.send(EditorEvent.Message(c.appContext.getString(R.string.edit_unlock_first)))
                    eventChannel.send(EditorEvent.Done(null))
                    return
                } ?: ContactDetails()
            } else {
                a.prefill ?: ContactDetails()
            }
            draft = withPhoneRow(e)
            start = draft
            return
        }
        if (a.contactId != null) {
            val d = if (a.rawId != null) c.contacts.editableRaw(a.contactId, a.rawId) else c.contacts.editable(a.contactId)
            original = d
            val loaded = withPhoneRow(d ?: ContactDetails())
            var e = d ?: ContactDetails()
            if (a.addPhone.isNotBlank()) e = e.copy(phones = e.phones + DataItem(value = a.addPhone, type = Phone.TYPE_MOBILE))
            if (a.prefill != null) e = app.parley.InsertPrefill.appendTo(e, a.prefill)
            draft = withPhoneRow(e)
            // An added number or appended details count as a change, so Save is ready for them.
            start = loaded
            account = d?.rawContacts?.firstOrNull { it.id == d.editRawId }?.account ?: AccountRef(null, null)
        } else {
            if (a.prefill != null) {
                draft = withPhoneRow(a.prefill)
                start = null
                return
            }
            val parts = a.prefillName.trim().split(Regex("\\s+"), limit = 2)
            draft = ContactDetails(
                given = parts.getOrElse(0) { "" },
                family = parts.getOrElse(1) { "" },
                phones = listOf(DataItem(value = a.prefillPhone, type = Phone.TYPE_MOBILE)),
                emails = if (a.prefillEmail.isNotBlank()) listOf(DataItem(value = a.prefillEmail, type = Email.TYPE_HOME)) else emptyList(),
            )
            start = draft
        }
    }

    fun update(f: (ContactDetails) -> ContactDetails) {
        draft?.let { draft = f(it) }
    }

    fun pickPhoto(uri: Uri) {
        photo = uri
        removePhoto = false
    }

    fun clearPhoto() {
        photo = null
        removePhoto = true
    }

    /** The Save-to menu: null = the private vault. */
    fun chooseAccount(a: AccountRef?) {
        if (a == null) {
            privateNew = true
        } else {
            privateNew = false
            account = a
        }
    }

    fun changeBackground(change: BackgroundChange) {
        background = change
    }

    fun linkRelation(name: String, link: RelationLinks.Link) {
        pickedLinks = pickedLinks + (RelationLinks.nameKey(name) to link)
    }

    fun save() {
        val e = draft ?: return
        if (saving) return
        // A contact holding only an address, a note or a website is fine; a completely empty one is not.
        val empty = !EditorForm.hasContent(EditorDrafts.texts(e))
        // Clearing one copy of a linked contact is allowed: that empty copy is removed and the others stay.
        val orig = original
        if (empty && photo == null && (orig == null || orig.rawContacts.size < 2 || orig.editRawId == null)) {
            message(if (orig == null) R.string.edit_add_name_first else R.string.edit_nothing_left)
            return
        }
        saving = true
        val request = SaveContactUseCase.Request(
            original = orig, draft = e, account = account, photo = photo, removePhoto = removePhoto,
            toVault = isVault, vaultId = args.vaultId, background = background, pickedLinks = pickedLinks,
        )
        viewModelScope.launch {
            val outcome = try {
                saveContact(request)
            } finally {
                saving = false
            }
            when (outcome) {
                is SaveContactUseCase.Outcome.Failed -> eventChannel.send(EditorEvent.Message(c.appContext.getString(R.string.edit_save_failed, outcome.message)))
                SaveContactUseCase.Outcome.NotSaved -> Unit
                is SaveContactUseCase.Outcome.Saved -> {
                    outcome.notes.forEach { message(it) }
                    val key = outcome.keepPromptKey
                    if (key != null) askKeep = key to outcome.id else eventChannel.send(EditorEvent.Done(outcome.id))
                }
            }
        }
    }

    /** The answer to "Keep this contact?" after saving a temporary contact. */
    fun answerKeep(keep: Boolean) {
        val (key, id) = askKeep ?: return
        askKeep = null
        viewModelScope.launch {
            c.temporaries.answerKeep(key, keep)
            eventChannel.send(EditorEvent.Done(id))
        }
    }

    private fun message(res: Int) {
        eventChannel.trySend(EditorEvent.Message(c.appContext.getString(res)))
    }

    // ---------------------------------------------------------------- saved state

    private fun toBundle(): Bundle {
        val d = draft ?: return Bundle()
        return bundleOf(
            K_DRAFT to ContactDraftJson.encode(d),
            K_ORIGINAL to original?.let(ContactDraftJson::encode),
            K_START to start?.let(ContactDraftJson::encode),
            K_ACCOUNT_TYPE to account?.type,
            K_ACCOUNT_NAME to account?.name,
            K_HAS_ACCOUNT to (account != null),
            K_PHOTO to photo,
            K_REMOVE_PHOTO to removePhoto,
            K_PRIVATE_NEW to privateNew,
            K_BACKGROUND to when (val b = background) {
                BackgroundChange.None -> null
                BackgroundChange.Remove -> BG_REMOVE
                is BackgroundChange.Set -> b.uri.toString()
            },
            K_LINKS to encodeLinks(pickedLinks),
            K_MORE_NAME to moreName,
            K_REVEALED to revealed.map { it.name }.toTypedArray(),
            K_ASK_KEEP_KEY to askKeep?.first,
            K_ASK_KEEP_ID to (askKeep?.second ?: 0L),
        )
    }

    /** Puts back an edit saved before the process was stopped; false when there was none. */
    private fun restore(b: Bundle): Boolean {
        val d = b.getString(K_DRAFT) ?: return false
        val decoded = runCatching { ContactDraftJson.decode(d) }.getOrNull() ?: return false
        original = b.getString(K_ORIGINAL)?.let { runCatching { ContactDraftJson.decode(it) }.getOrNull() }
        start = b.getString(K_START)?.let { runCatching { ContactDraftJson.decode(it) }.getOrNull() }
        draft = decoded
        account = if (b.getBoolean(K_HAS_ACCOUNT)) AccountRef(b.getString(K_ACCOUNT_TYPE), b.getString(K_ACCOUNT_NAME)) else null
        @Suppress("DEPRECATION")
        photo = b.getParcelable(K_PHOTO)
        removePhoto = b.getBoolean(K_REMOVE_PHOTO)
        privateNew = b.getBoolean(K_PRIVATE_NEW)
        background = when (val bg = b.getString(K_BACKGROUND)) {
            null -> BackgroundChange.None
            BG_REMOVE -> BackgroundChange.Remove
            else -> BackgroundChange.Set(Uri.parse(bg))
        }
        pickedLinks = decodeLinks(b.getString(K_LINKS))
        moreName = b.getBoolean(K_MORE_NAME)
        revealed = b.getStringArray(K_REVEALED).orEmpty().mapNotNull { n -> EditorForm.Kind.entries.firstOrNull { it.name == n } }.toSet()
        askKeep = b.getString(K_ASK_KEEP_KEY)?.let { it to b.getLong(K_ASK_KEEP_ID) }
        return true
    }

    private fun encodeLinks(links: Map<String, RelationLinks.Link>): String = JSONObject().apply {
        links.forEach { (k, l) -> put(k, JSONArray().put(l.lookupKey).put(l.contactId)) }
    }.toString()

    private fun decodeLinks(s: String?): Map<String, RelationLinks.Link> = runCatching {
        val o = JSONObject(s ?: return emptyMap())
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { RelationLinks.Link(it.getString(0), it.getLong(1)) } }
    }.getOrDefault(emptyMap())

    private companion object {
        const val STATE = "editor"
        const val K_DRAFT = "draft"
        const val K_ORIGINAL = "original"
        const val K_START = "start"
        const val K_ACCOUNT_TYPE = "accountType"
        const val K_ACCOUNT_NAME = "accountName"
        const val K_HAS_ACCOUNT = "hasAccount"
        const val K_PHOTO = "photo"
        const val K_REMOVE_PHOTO = "removePhoto"
        const val K_PRIVATE_NEW = "privateNew"
        const val K_BACKGROUND = "background"
        const val K_LINKS = "links"
        const val K_MORE_NAME = "moreName"
        const val K_REVEALED = "revealed"
        const val K_ASK_KEEP_KEY = "askKeepKey"
        const val K_ASK_KEEP_ID = "askKeepId"
        const val BG_REMOVE = "remove"
    }
}

/** What counts as content and as a change in an editor draft. */
internal object EditorDrafts {
    /** The draft without never-saved blank rows, so an added-and-left-empty row isn't a change. */
    fun meaningful(d: ContactDetails): ContactDetails {
        fun m(l: List<DataItem>) = EditorForm.meaningful(l, { it.id == null }, { it.value.isBlank() })
        return d.copy(
            phones = m(d.phones), emails = m(d.emails), websites = m(d.websites), relations = m(d.relations),
            addresses = EditorForm.meaningful(d.addresses, { it.id == null }, { it.isBlank }),
            events = EditorForm.meaningful(d.events, { it.id == null }, { it.date.isBlank() }),
            handles = EditorForm.meaningful(d.handles, { it.id == null }, { it.value.isBlank() }),
        )
    }

    /** Every text of the draft (a contact holding only an address, a note or a website is fine, F24). */
    fun texts(d: ContactDetails): List<String> = with(d) {
        listOf(prefix, given, middle, family, suffix, nickname, company, title, note, phoneticGiven, phoneticFamily, context, pinnedNote) +
            (phones + emails + websites + relations).map { it.value } + events.map { it.date } + handles.map { it.value } +
            addresses.flatMap { listOf(it.street, it.poBox, it.neighborhood, it.city, it.region, it.postcode, it.country) }
    }
}
