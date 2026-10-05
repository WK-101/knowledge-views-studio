package app.parley.ui.contact

import android.net.Uri
import app.parley.common.photo.PhotoFrame
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.parley.InsertPrefill
import app.parley.R
import app.parley.common.people.EditorForm
import app.parley.common.people.ExpiryChange
import app.parley.common.people.MeCards
import app.parley.common.people.TemporaryChoice
import app.parley.common.people.ThreeWayMerge
import app.parley.common.people.RelationLinks
import app.parley.common.photo.OriginalPhoto
import app.parley.common.suspendRunCatching
import app.parley.ui.people.RelationMirrorText
import app.parley.common.people.RowKeys
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.ContactDraftJson
import app.parley.data.ContactEditRebase
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.GroupInfo
import app.parley.data.people.ParleyRelationRows
import app.parley.data.vault.VaultCrypto
import app.parley.ui.people.MeCardDetails
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
    /** Edit "My card" (your own details, kept by Parley) with the same form as every contact. */
    val meCard: Boolean = false,
)

/**
 * A save found the contact changed elsewhere. [base] is the contact as the editor loaded it (null when that wasn't
 * kept, after the process was stopped), [mine] the draft, [theirs] the contact now (null: it was removed), and
 * [conflicts] the fields both sides changed.
 */
data class EditConflict(
    val base: ContactDetails?,
    val mine: ContactDetails,
    val theirs: ContactDetails?,
    val conflicts: List<ContactEditRebase.Conflict>,
)

/** What the editor was doing when it needed private contacts unlocked ([EditorEvent.Unlock]). */
enum class UnlockStep {
    /** Opening a private contact (or an edit of one kept while the process was stopped): nothing typed yet. */
    OPEN,

    /** Saving: the draft is kept as it is whatever the answer. */
    SAVE,
}

sealed interface EditorEvent {
    data class Message(val text: String) : EditorEvent

    /** Leave the editor; [savedId] is the saved contact (negative: a private one), or null when nothing was saved. */
    data class Done(val savedId: Long?) : EditorEvent

    /**
     * Private contacts are locked: ask for their unlock, then call [EditorViewModel.unlocked] (or
     * [EditorViewModel.unlockDeclined]) with [step], which says what was waiting for it.
     */
    data class Unlock(val step: UnlockStep) : EditorEvent

    /** Saving changed relations on other contacts too: say so, with Undo when [undo] is set. */
    data class Mirrored(val text: String, val undo: (suspend () -> Unit)?) : EditorEvent
}

/**
 * The contact editor's state and its save. The draft, the picked photo, the account and the other choices live here,
 * so rotation, a theme, font or language change keep the edit; they are also written to saved state, so the edit
 * survives the process being stopped in the background (for example while the photo picker is open).
 *
 * Saved state holds only the draft and the choices: the contact is loaded again on restore (so a private contact goes
 * through the vault's unlock again), and a private draft is sealed with the vault's detail key, never kept in plain
 * text outside Parley.
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

    /** Android 16's cloud default while it takes new contacts instead of the phone (the Save-to menu says so). */
    var systemDefault by mutableStateOf<AccountRef?>(null)
        private set
    var groups by mutableStateOf<List<GroupInfo>>(emptyList())
        private set
    var account by mutableStateOf<AccountRef?>(null)
        private set
    var photo by mutableStateOf<Uri?>(null)
        private set
    var removePhoto by mutableStateOf(false)
        private set

    /**
     * The square the avatar is cut from ("Frame photo"): of [photo] when one was picked, otherwise a new framing of
     * the photo Parley keeps ("Adjust framing"). Null: the whole picture.
     */
    var photoFrame by mutableStateOf<PhotoFrame?>(null)
        private set

    /**
     * Keeping the picked photo whole ([OriginalPhoto.nextQuestion]): the question to ask now (a file over the size
     * Parley keeps without asking, or a HEIC with a location it can't remove), and the answers given so far.
     */
    var photoQuestion by mutableStateOf<OriginalPhoto.Question?>(null)
        private set
    var photoAnswers by mutableStateOf(OriginalPhoto.Answers())
        private set
    private var photoProbe: OriginalPhoto.Probe? = null

    /** New contacts go to the private vault when "Private by default" is on (the Save-to menu can change it). */
    var privateNew by mutableStateOf(false)
        private set

    /** "Save to: Temporary" for a new contact, and its time, privacy and call-history choices. */
    var temporaryNew by mutableStateOf(false)
        private set
    var temporary by mutableStateOf(TemporaryChoice())
        private set

    /** When an existing contact deletes itself now (null: it's kept), and the editor's change to that. */
    var expiresAt by mutableStateOf<Long?>(null)
        private set
    var expiryPick by mutableStateOf<ExpiryChange?>(null)
        private set
    var background by mutableStateOf<BackgroundChange>(BackgroundChange.None)
        private set

    /** My card: what its QR code and vCard include (saved with the card). */
    var meParts by mutableStateOf(MeCards.defaultParts)
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

    /** The contact was changed elsewhere since it was loaded: the choices to offer. */
    var conflict by mutableStateOf<EditConflict?>(null)
        private set

    /** The loaded contact the draft started from, for merging; null when a restored draft's base wasn't kept. */
    private var base: ContactDetails? = null

    /**
     * A restored draft was made of another copy of the contact than the one loaded now (or of a contact that is gone):
     * saving first shows the changed-elsewhere choices instead of writing.
     */
    private var mustReconcile = false

    /** Stable row keys (animations, focus). */
    val keys = RowKeys()

    private val eventChannel = Channel<EditorEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var started = false

    /** Saved into the private vault: a private contact, or a new temporary one kept private (the default). */
    val isVault: Boolean get() = args.vaultId != null || if (temporaryNew) temporary.private else privateNew

    /** The expiry change the save will apply (a "Keep permanently" on a contact that isn't temporary is none). */
    private val expiryChange: ExpiryChange? get() = ExpiryChange.resolve(expiresAt != null, expiryPick)
    val isNew: Boolean get() = !args.meCard && original == null && (args.vaultId ?: 0L) <= 0L

    /** Something to save: the draft differs from the start (blank new rows aside), or the photo or background changed. */
    val changed: Boolean
        get() {
            val d = draft
            return d != null && (start == null || EditorDrafts.meaningful(d) != start?.let(EditorDrafts::meaningful)) ||
                photo != null || removePhoto || photoFrame != null || background != BackgroundChange.None || expiryChange != null ||
                args.meCard && meParts != c.people.me.shareParts.value
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
            systemDefault = withContext(Dispatchers.IO) { c.contacts.systemDefaultAccount() }
            groups = withContext(Dispatchers.IO) { c.contacts.groups() }
            if (!load(a)) return@launch
            if (restored != null && restored.containsKey(K_HAS_ACCOUNT)) restore(restored)
        }
    }

    /** Loads the contact (or the new one's prefill); false when the editor is leaving (the vault is locked). */
    private suspend fun load(a: EditorArgs): Boolean {
        if (a.meCard) {
            // My card: Parley's own copy (never the phone's profile, which Parley doesn't write).
            val d = MeCardDetails.toDetails(c.people.me.card.value)
            draft = if (d.phones.isEmpty()) d.copy(phones = listOf(DataItem(type = Phone.TYPE_MOBILE))) else d
            start = draft
            meParts = c.people.me.shareParts.value
            return true
        }
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
                    leaveLocked()
                    return false
                } catch (_: VaultCrypto.KeyUnavailableException) {
                    // Editing from an empty form would save over details that are only unreadable for now.
                    eventChannel.send(EditorEvent.Message(c.appContext.getString(R.string.vault_details_unavailable)))
                    eventChannel.send(EditorEvent.Done(null))
                    return false
                } ?: ContactDetails()
            } else {
                a.prefill ?: ContactDetails()
            }
            // Details added to an existing private contact ("Add to …" from pasted text) count as a change.
            draft = withPhoneRow(if (vaultId > 0 && a.prefill != null) InsertPrefill.appendTo(e, a.prefill) else e)
            start = withPhoneRow(e)
            if (vaultId > 0) expiresAt = c.vault.summariesNow().firstOrNull { it.id == vaultId }?.expiresAt
            return true
        }
        if (a.contactId != null) {
            val d = (if (a.rawId != null) c.contacts.editableRaw(a.contactId, a.rawId) else c.contacts.editable(a.contactId))?.withParleyRelations()
            original = d
            base = d
            val loaded = withPhoneRow(d ?: ContactDetails())
            var e = d ?: ContactDetails()
            if (a.addPhone.isNotBlank()) e = e.copy(phones = e.phones + DataItem(value = a.addPhone, type = Phone.TYPE_MOBILE))
            if (a.prefill != null) e = InsertPrefill.appendTo(e, a.prefill)
            draft = withPhoneRow(e)
            // An added number or appended details count as a change, so Save is ready for them.
            start = loaded
            account = d?.rawContacts?.firstOrNull { it.id == d.editRawId }?.account ?: AccountRef(null, null)
            expiresAt = d?.lookupKey?.takeIf { it.isNotEmpty() }?.let { withContext(Dispatchers.IO) { c.temporaries.forKey(it) } }?.expiresAt
        } else {
            if (a.prefill != null) {
                draft = withPhoneRow(a.prefill)
                start = null
                return true
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
        return true
    }

    /**
     * The contact with its relations kept in Parley only. Not in discreet mode: they name private contacts, and the
     * editor then neither shows nor saves them (the save writes them only when they changed).
     */
    private suspend fun ContactDetails.withParleyRelations(): ContactDetails {
        if (lookupKey.isEmpty() || c.settings.current().hideVault) return this
        val stored = withContext(Dispatchers.IO) { c.meta.meta(lookupKey) }?.parleyRelations
        return copy(parleyRelations = ParleyRelationRows.decode(stored))
    }

    /**
     * The private contact (or its kept edit) can't be opened while private contacts are locked: their unlock is asked
     * for, and the editor opens once it succeeds ([unlocked]) or leaves quietly when it is cancelled.
     */
    private suspend fun leaveLocked() {
        eventChannel.send(EditorEvent.Unlock(UnlockStep.OPEN))
    }

    /** Private contacts were unlocked for [step]: the contact opens, or the save runs again with the draft as it is. */
    fun unlocked(step: UnlockStep) {
        when (step) {
            UnlockStep.OPEN -> {
                started = false
                start(args)
            }
            UnlockStep.SAVE -> save()
        }
    }

    /** The unlock was cancelled: an editor that couldn't open leaves; one that was saving stays open with every edit. */
    fun unlockDeclined(step: UnlockStep) {
        if (step == UnlockStep.OPEN) eventChannel.trySend(EditorEvent.Done(null))
    }

    fun update(f: (ContactDetails) -> ContactDetails) {
        draft?.let { draft = f(it) }
    }

    /**
     * Photos the camera app took in this editor. Parley's cache isn't sealed, so they are deleted when the editor
     * closes ([onCleared]); the one saved has been copied where it belongs by then.
     */
    private val shots = LinkedHashSet<Uri>()

    /** "Take photo" gave [uri] (framed next, then perhaps picked). */
    fun tookPhoto(uri: Uri) {
        shots += uri
    }

    /** A photo picked or taken; [frame]: its square for the avatar, when framed already. */
    fun pickPhoto(uri: Uri, frame: PhotoFrame? = null) {
        if (photo != uri) {
            ContactCamera.forget(c.appContext, photo)
            photoAnswers = OriginalPhoto.Answers()
            askAboutPhoto(uri)
        }
        photo = uri
        photoFrame = frame
        removePhoto = false
    }

    /** Looks at the picked photo [uri] and asks what keeping it whole needs (nothing, for most photos). */
    private fun askAboutPhoto(uri: Uri) {
        photoProbe = null
        photoQuestion = null
        viewModelScope.launch {
            val p = suspendRunCatching { c.people.originals.probe(uri) }.getOrNull() ?: return@launch
            if (photo != uri) return@launch
            photoProbe = p
            photoQuestion = OriginalPhoto.nextQuestion(p, photoAnswers)
        }
    }

    /** The answer to [photoQuestion]: keep the whole file / its location ([keep]), or a JPEG (also when dismissed). */
    fun answerPhoto(keep: Boolean) {
        val q = photoQuestion ?: return
        photoAnswers = when (q) {
            OriginalPhoto.Question.LARGE -> photoAnswers.copy(keepWhole = keep)
            OriginalPhoto.Question.LOCATION -> photoAnswers.copy(keepLocation = keep)
        }
        photoQuestion = photoProbe?.let { OriginalPhoto.nextQuestion(it, photoAnswers) }
    }

    /** The size of the picked photo, for the question about a large file. */
    val photoBytes: Long get() = photoProbe?.bytes ?: 0L

    /** The square for the avatar of the picked photo, or of the kept one when none was picked (null: whole). */
    fun frame(frame: PhotoFrame?) {
        photoFrame = frame
    }

    fun clearPhoto() {
        ContactCamera.forget(c.appContext, photo)
        photoQuestion = null
        photoProbe = null
        photo = null
        photoFrame = null
        removePhoto = true
    }

    /** The Save-to menu: null = the private vault. */
    fun chooseAccount(a: AccountRef?) {
        temporaryNew = false
        if (a == null) {
            privateNew = true
        } else {
            privateNew = false
            account = a
        }
    }

    /** The Save-to menu's "Temporary". */
    fun chooseTemporary() {
        temporaryNew = true
    }

    /** A new temporary contact's time, privacy or call-history choice. */
    fun changeTemporary(choice: TemporaryChoice) {
        temporary = choice
    }

    /** An existing contact: make it temporary, give it a new time, or keep it (applied when saving). */
    fun pickExpiry(change: ExpiryChange?) {
        expiryPick = change
    }

    fun changeBackground(change: BackgroundChange) {
        background = change
    }

    /** My card: include or leave out one part of the QR code and vCard. */
    fun toggleMePart(p: MeCards.Part) {
        meParts = if (p in meParts) meParts - p else meParts + p
    }

    fun linkRelation(name: String, link: RelationLinks.Link) {
        pickedLinks = pickedLinks + (RelationLinks.nameKey(name) to link)
    }

    fun save() {
        val e = draft ?: return
        if (saving) return
        if (args.meCard) return saveMeCard(e)
        // A contact holding only an address, a note or a website is fine; a completely empty one is not.
        val empty = !EditorForm.hasContent(EditorDrafts.texts(e))
        // Clearing one copy of a linked contact is allowed: that empty copy is removed and the others stay.
        val orig = original
        if (empty && photo == null && (orig == null || orig.rawContacts.size < 2 || orig.editRawId == null)) {
            message(if (orig == null) R.string.edit_add_name_first else R.string.edit_nothing_left)
            return
        }
        if (mustReconcile) {
            conflict = EditConflict(null, e, orig, orig?.let { ContactEditRebase.conflicts(null, e, it) }.orEmpty())
            return
        }
        saving = true
        val request = SaveContactUseCase.Request(
            original = orig, draft = e, account = account, photo = photo, removePhoto = removePhoto, photoFrame = photoFrame,
            toVault = isVault, vaultId = args.vaultId, background = background, pickedLinks = pickedLinks,
            temporary = temporary.takeIf { temporaryNew && isNew }, expiry = expiryChange, originalAnswers = photoAnswers,
            vaultLoaded = start.takeIf { (args.vaultId ?: 0L) > 0L },
        )
        viewModelScope.launch {
            val outcome = try {
                saveContact(request)
            } finally {
                saving = false
            }
            when (outcome) {
                is SaveContactUseCase.Outcome.Failed -> eventChannel.send(EditorEvent.Message(c.appContext.getString(R.string.edit_save_failed, outcome.message)))
                // Not an error: the unlock is asked for, and the save runs again with the same draft.
                SaveContactUseCase.Outcome.Locked -> eventChannel.send(EditorEvent.Unlock(UnlockStep.SAVE))
                is SaveContactUseCase.Outcome.ChangedElsewhere -> {
                    val theirs = outcome.theirs
                    conflict = EditConflict(base, e, theirs, theirs?.let { ContactEditRebase.conflicts(base, e, it) }.orEmpty())
                }
                SaveContactUseCase.Outcome.NotSaved -> Unit
                is SaveContactUseCase.Outcome.Saved -> {
                    outcome.notes.forEach { message(it) }
                    outcome.mirrors?.let { report ->
                        RelationMirrorText.summary(c.appContext.resources, report)?.let { text ->
                            val undo: (suspend () -> Unit)? =
                                if (report.done.isEmpty()) null else { { c.people.relationMirrors.undo(outcome.id, report.done) } }
                            eventChannel.send(EditorEvent.Mirrored(text, undo))
                        }
                    }
                    val key = outcome.keepPromptKey
                    if (key != null) askKeep = key to outcome.id else eventChannel.send(EditorEvent.Done(outcome.id))
                    if (key == null && request.temporary != null) {
                        val t = request.temporary
                        eventChannel.send(EditorEvent.Message(c.appContext.resources.getQuantityString(R.plurals.temp_deletes_in_days, t.days, t.days)))
                    }
                }
            }
        }
    }

    /** My card: saved to Parley's own copy (clearing it is allowed); "Send my details" reads its name and number. */
    private fun saveMeCard(e: ContactDetails) {
        val card = MeCardDetails.toCard(e)
        c.people.me.save(card)
        c.people.me.setShareParts(meParts)
        message(R.string.me_saved)
        eventChannel.trySend(EditorEvent.Done(null))
    }

    /** "Show their version": the edit is set aside and the editor shows the contact as it is now. */
    fun useTheirs() {
        val k = conflict ?: return
        conflict = null
        val theirs = k.theirs ?: return
        mustReconcile = false
        original = theirs
        base = theirs
        draft = if (theirs.phones.isEmpty()) theirs.copy(phones = listOf(DataItem(type = Phone.TYPE_MOBILE))) else theirs
        start = draft
        message(R.string.edit_changed_reloaded)
    }

    /** "Keep mine": everything the editor shows is saved over their version (or as a new contact when it's gone). */
    fun keepMine() {
        val k = conflict ?: return
        conflict = null
        mustReconcile = false
        val theirs = k.theirs
        if (theirs == null) {
            original = null
            base = null
            draft = k.mine.asNewContact()
        } else {
            rebaseOnto(theirs, ContactEditRebase.rebase(null, k.mine, theirs, emptyMap(), ThreeWayMerge.Side.MINE))
        }
        save()
    }

    /** "Merge field by field": fields only one side changed merge by themselves; [picks] settle the others. */
    fun merge(picks: Map<ContactEditRebase.Field, ThreeWayMerge.Side>) {
        val k = conflict ?: return
        val theirs = k.theirs ?: return
        conflict = null
        mustReconcile = false
        rebaseOnto(theirs, ContactEditRebase.rebase(k.base, k.mine, theirs, picks, ThreeWayMerge.Side.MINE))
        message(R.string.edit_changed_merged)
    }

    /** Closes the choices without deciding (the draft stays; a later save asks again). */
    fun dismissConflict() {
        conflict = null
    }

    private fun rebaseOnto(theirs: ContactDetails, merged: ContactDetails) {
        original = theirs
        base = theirs
        draft = merged
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

    /** Leaving the editor: camera shots it no longer needs go, after a save still reading one has finished. */
    override fun onCleared() {
        val left = shots + listOfNotNull(photo)
        if (left.isNotEmpty()) saveContact.whenIdle { left.forEach { ContactCamera.forget(c.appContext, it) } }
        super.onCleared()
    }

    // ---------------------------------------------------------------- saved state

    @VisibleForTesting
    internal fun toBundle(): Bundle {
        val d = draft ?: return Bundle()
        val b = bundleOf(
            K_ACCOUNT_TYPE to account?.type,
            K_ACCOUNT_NAME to account?.name,
            K_HAS_ACCOUNT to (account != null),
            K_PHOTO to photo,
            K_REMOVE_PHOTO to removePhoto,
            K_PHOTO_FRAME to photoFrame?.encode(),
            K_KEEP_WHOLE to photoAnswers.keepWhole?.toString(),
            K_KEEP_LOCATION to photoAnswers.keepLocation?.toString(),
            K_PRIVATE_NEW to privateNew,
            K_TEMPORARY to temporaryNew,
            K_TEMP_DAYS to temporary.days,
            K_TEMP_PRIVATE to temporary.private,
            K_TEMP_PURGE to temporary.purgeHistory,
            K_EXPIRY to when (val p = expiryPick) {
                null -> EXPIRY_NONE
                ExpiryChange.Keep -> EXPIRY_KEEP
                is ExpiryChange.After -> p.days
            },
            K_BACKGROUND to when (val bg = background) {
                BackgroundChange.None -> null
                BackgroundChange.Remove -> BG_REMOVE
                is BackgroundChange.Set -> bg.uri.toString()
            },
            K_LINKS to encodeLinks(pickedLinks),
            K_MORE_NAME to moreName,
            K_REVEALED to revealed.map { it.name }.toTypedArray(),
            K_ME_PARTS to MeCards.encodeParts(meParts),
            K_ASK_KEEP_KEY to askKeep?.first,
            K_ASK_KEEP_ID to (askKeep?.second ?: 0L),
            // The version the draft was based on, so a change made elsewhere while Parley was stopped is still caught.
            K_BASE_RAW to (original?.editRawId ?: 0L),
            K_BASE_VERSION to (original?.editRawVersion ?: -1L),
            K_RECONCILE to mustReconcile,
        )
        // A draft too large for the saved-state transaction is left out: the contact is loaded again instead.
        val json = ContactDraftJson.encode(d).takeIf { it.length <= MAX_DRAFT_CHARS } ?: return b
        if (isVault) {
            // Sealed, or not kept at all when the vault's key is locked right now.
            runCatching { VaultCrypto.sealDetail(json.toByteArray()) }.getOrNull()?.let { b.putByteArray(K_SEALED_DRAFT, it) }
        } else {
            b.putString(K_DRAFT, json)
        }
        return b
    }

    /** Puts back an edit saved before the process was stopped, over the contact [load] just read. */
    private suspend fun restore(b: Bundle) {
        val sealed = b.getByteArray(K_SEALED_DRAFT)
        val json = if (sealed != null) {
            try {
                withContext(Dispatchers.IO) { VaultCrypto.openDetail(sealed).decodeToString() }
            } catch (_: VaultCrypto.LockedException) {
                leaveLocked()
                return
            } catch (_: Exception) {
                null
            }
        } else {
            b.getString(K_DRAFT)
        }
        mustReconcile = b.getBoolean(K_RECONCILE)
        json?.let { runCatching { ContactDraftJson.decode(it) }.getOrNull() }?.let { restored ->
            if (args.vaultId == null && args.contactId != null) reconcile(restored, b.getLong(K_BASE_RAW), b.getLong(K_BASE_VERSION, -1L)) else draft = restored
        }
        account = if (b.getBoolean(K_HAS_ACCOUNT)) AccountRef(b.getString(K_ACCOUNT_TYPE), b.getString(K_ACCOUNT_NAME)) else null
        // A picture chosen in the photo picker while the process was stopped arrives as soon as the screen is drawn
        // again, before this restore finishes loading: the saved state is older than it and must not undo it.
        if (photo == null && !removePhoto) {
            @Suppress("DEPRECATION")
            photo = b.getParcelable(K_PHOTO)
            removePhoto = b.getBoolean(K_REMOVE_PHOTO)
            photoFrame = PhotoFrame.decode(b.getString(K_PHOTO_FRAME))
            photoAnswers = OriginalPhoto.Answers(b.getString(K_KEEP_WHOLE)?.toBooleanStrictOrNull(), b.getString(K_KEEP_LOCATION)?.toBooleanStrictOrNull())
            // A question not answered yet is asked again.
            photo?.let { uri -> askAboutPhoto(uri) }
        }
        privateNew = b.getBoolean(K_PRIVATE_NEW)
        temporaryNew = b.getBoolean(K_TEMPORARY)
        temporary = TemporaryChoice(
            b.getInt(K_TEMP_DAYS, TemporaryChoice.DEFAULT_DAYS), b.getBoolean(K_TEMP_PRIVATE, true), b.getBoolean(K_TEMP_PURGE, true),
        )
        expiryPick = when (val e = b.getInt(K_EXPIRY, EXPIRY_NONE)) {
            EXPIRY_NONE -> null
            EXPIRY_KEEP -> ExpiryChange.Keep
            else -> ExpiryChange.After(e)
        }
        if (background == BackgroundChange.None) {
            background = when (val bg = b.getString(K_BACKGROUND)) {
                null -> BackgroundChange.None
                BG_REMOVE -> BackgroundChange.Remove
                else -> BackgroundChange.Set(Uri.parse(bg))
            }
        }
        pickedLinks = decodeLinks(b.getString(K_LINKS))
        moreName = b.getBoolean(K_MORE_NAME)
        if (args.meCard) meParts = MeCards.decodeParts(b.getString(K_ME_PARTS))
        revealed = b.getStringArray(K_REVEALED).orEmpty().mapNotNull { n -> EditorForm.Kind.entries.firstOrNull { it.name == n } }.toSet()
        askKeep = b.getString(K_ASK_KEEP_KEY)?.let { it to b.getLong(K_ASK_KEEP_ID) }
    }

    /**
     * Puts a restored draft over the contact as loaded now. The draft's row ids belong to the copy (raw contact) it
     * was made of, [savedRaw]. Meanwhile the contact may have been linked, unlinked or joined by an account's copy,
     * so it now opens on another copy or under another id; saving those ids would delete and overwrite rows of a
     * different raw contact. So: the same copy is edited again when it is still there; otherwise the draft keeps only
     * ids of the copy loaded now ([ContactEditRebase.adopt]), and the save first asks what to keep.
     */
    @Suppress("CyclomaticComplexMethod")
    private suspend fun reconcile(restored: ContactDetails, savedRaw: Long, savedVersion: Long) {
        var o = original
        if (o == null && restored.lookupKey.isNotEmpty()) {
            // Re-aggregated under another id: found again by its lookup key.
            o = c.contacts.resolve(restored.lookupKey, restored.id)?.let { id ->
                ((if (savedRaw > 0) c.contacts.editableRaw(id, savedRaw) else null) ?: c.contacts.editable(id))?.withParleyRelations()
            }
        }
        // The contact now opens on another copy first: edit the copy the draft was made of while it is still writable.
        val movedOn = o != null && o.editRawId != savedRaw
        if (o != null && movedOn && savedRaw in o.writableRawIds) {
            o = c.contacts.editableRaw(o.id, savedRaw)?.withParleyRelations() ?: o
        }
        if (o !== original) {
            original = o
            base = o
            o?.let { account = it.rawContacts.firstOrNull { r -> r.id == it.editRawId }?.account ?: AccountRef(null, null) }
        }
        val now = o
        draft = when {
            now == null -> if (restored.id != 0L || ContactEditRebase.hasRowIds(restored)) {
                mustReconcile = true
                restored.asNewContact()
            } else {
                restored
            }
            (now.editRawId ?: 0L) == savedRaw && restored.editRawId == now.editRawId -> {
                if (savedVersion >= 0 && savedVersion != now.editRawVersion) {
                    // The contact changed while Parley was stopped: keep asserting the old version, and merge without a base.
                    original = now.copy(editRawVersion = savedVersion)
                    base = null
                }
                restored
            }
            else -> {
                mustReconcile = true
                base = null
                ContactEditRebase.adopt(restored, now)
            }
        }
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
        const val K_SEALED_DRAFT = "sealedDraft"
        const val K_ACCOUNT_TYPE = "accountType"
        const val K_ACCOUNT_NAME = "accountName"
        const val K_HAS_ACCOUNT = "hasAccount"
        const val K_PHOTO = "photo"
        const val K_REMOVE_PHOTO = "removePhoto"
        const val K_PHOTO_FRAME = "photoFrame"
        const val K_KEEP_WHOLE = "keepWhole"
        const val K_KEEP_LOCATION = "keepLocation"
        const val K_PRIVATE_NEW = "privateNew"
        const val K_BACKGROUND = "background"
        const val K_LINKS = "links"
        const val K_MORE_NAME = "moreName"
        const val K_REVEALED = "revealed"
        const val K_ME_PARTS = "meParts"
        const val K_ASK_KEEP_KEY = "askKeepKey"
        const val K_ASK_KEEP_ID = "askKeepId"
        const val K_BASE_RAW = "baseRaw"
        const val K_BASE_VERSION = "baseVersion"
        const val K_RECONCILE = "reconcile"
        const val BG_REMOVE = "remove"
        const val K_TEMPORARY = "temporary"
        const val K_TEMP_DAYS = "tempDays"
        const val K_TEMP_PRIVATE = "tempPrivate"
        const val K_TEMP_PURGE = "tempPurge"
        const val K_EXPIRY = "expiry"
        const val EXPIRY_NONE = -1
        const val EXPIRY_KEEP = 0

        /** About 200 KB in the parcel (UTF-16), well under the binder transaction limit with the rest of the state. */
        const val MAX_DRAFT_CHARS = 100_000
    }
}

/** The draft as a new contact: no contact, name, note or row ids of the one it was made from. */
private fun ContactDetails.asNewContact(): ContactDetails =
    copy(id = 0, lookupKey = "", nameId = null, nicknameId = null, pronounsId = null, orgId = null, noteId = null, namePartsId = null, languageId = null)
        .withoutRowIds()

/** The draft as new rows only (for saving it as a new contact). */
private fun ContactDetails.withoutRowIds(): ContactDetails = copy(
    phones = phones.map { it.copy(id = null) }, emails = emails.map { it.copy(id = null) }, websites = websites.map { it.copy(id = null) },
    relations = relations.map { it.copy(id = null) }, addresses = addresses.map { it.copy(id = null) }, events = events.map { it.copy(id = null) },
    handles = handles.map { it.copy(id = null) }, customFields = customFields.map { it.copy(id = null, mime = null) }, editRawId = null, editRawVersion = null,
    rawContacts = emptyList(), writableRawIds = emptyList(), readOnlyDataIds = emptySet(),
)

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
            customFields = EditorForm.meaningful(d.customFields, { it.id == null }, { it.isBlank }),
        )
    }

    /** Every text of the draft (a contact holding only an address, a note or a website is fine, F24). */
    fun texts(d: ContactDetails): List<String> = with(d) {
        listOf(prefix, given, middle, family, suffix, nickname, pronouns, company, title, department, note) +
            listOf(phoneticGiven, phoneticFamily, phoneticMiddle, secondSurname, generation, language, context, pinnedNote) +
            customFields.flatMap { listOf(it.label, it.value) } +
            (phones + emails + websites + relations).map { it.value } + events.map { it.date } + handles.map { it.value } +
            addresses.flatMap { listOf(it.street, it.poBox, it.neighborhood, it.city, it.region, it.postcode, it.country) }
    }
}
