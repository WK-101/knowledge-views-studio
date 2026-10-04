package app.parley.ui.contact

import app.parley.data.vault.VaultCrypto
import android.net.Uri
import app.parley.jobs.UserErrorText
import app.parley.R
import app.parley.common.people.ContactRef
import app.parley.common.people.ExpiryChange
import app.parley.common.people.RelationLinks
import app.parley.common.people.TemporaryChoice
import app.parley.common.photo.OriginalPhoto
import app.parley.common.photo.PhotoFrame
import app.parley.common.suspendRunCatching
import app.parley.data.AccountRef
import app.parley.data.ContactChangedElsewhereException
import app.parley.data.ContactDetails
import app.parley.data.ContactPhotoProcessor
import app.parley.data.DataContainer
import app.parley.data.TemporaryContacts
import app.parley.data.db.ContactMetaEntity
import app.parley.data.people.CallBackgrounds
import app.parley.data.people.OriginalPhotos
import app.parley.data.people.ParleyRelationRows
import app.parley.data.people.RelationMirrors
import app.parley.ui.people.BackgroundChange
import app.parley.ui.people.CallBackgroundText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The editor's save: the contact (or private contact) itself, then its photo, call-screen background and relation
 * links, then whether a temporary contact should ask to be kept. The steps run in the app's scope, so leaving the
 * editor half way never leaves a partial write behind; the caller only stops waiting.
 */
class SaveContactUseCase(private val c: DataContainer) {

    data class Request(
        val original: ContactDetails?,
        val draft: ContactDetails,
        val account: AccountRef?,
        val photo: Uri?,
        val removePhoto: Boolean,
        /**
         * The square the avatar is cut from ("Frame photo"): of [photo] when one was picked, otherwise of the photo
         * Parley keeps for the contact ("Adjust framing"). Null: the whole picture, as before.
         */
        val photoFrame: PhotoFrame? = null,
        /** Save into the private vault; [vaultId] > 0 edits that entry. */
        val toVault: Boolean,
        val vaultId: Long?,
        val background: BackgroundChange,
        /** Relations whose contact was chosen with the picker (name key → that contact). */
        val pickedLinks: Map<String, RelationLinks.Link>,
        /** "Save to: Temporary" for a new contact: it deletes itself after the chosen time (null: an ordinary one). */
        val temporary: TemporaryChoice? = null,
        /** An existing contact made temporary, given a new time, or kept permanently in the editor (null: untouched). */
        val expiry: ExpiryChange? = null,
        /** How to keep [photo] whole, as the editor asked ([OriginalPhoto.nextQuestion]). */
        val originalAnswers: OriginalPhoto.Answers = OriginalPhoto.Answers(),
        /**
         * A private contact as the editor loaded it: the star, labels, ringtone and "send to voicemail" are saved only
         * where the editor changed them, so a change made meanwhile from the list or the page isn't undone.
         */
        val vaultLoaded: ContactDetails? = null,
    )

    sealed interface Outcome {
        /**
         * Saved as [id] (negative for a private contact). [keepPromptKey]: a temporary contact that should now be
         * asked about once. [notes]: string resources for parts that didn't make it (photo, background).
         * [mirrors]: the relations added to (or taken back from) the other contacts, for "Also added to Ana".
         */
        data class Saved(val id: Long, val keepPromptKey: String?, val notes: List<Int>, val mirrors: RelationMirrors.Report? = null) : Outcome

        /** The provider refused without an error (nothing to show). */
        data object NotSaved : Outcome

        data class Failed(val message: String) : Outcome

        /** Private contacts are locked: nothing was written. The editor asks for their unlock and saves again. */
        data object Locked : Outcome

        /**
         * Another app or a sync changed the contact since the editor loaded it, so nothing was written. [theirs] is
         * the contact as it is now (null when it is gone).
         */
        data class ChangedElsewhere(val theirs: ContactDetails?) : Outcome
    }

    /** Held while a save runs: [whenIdle] waits for it. */
    private val running = Mutex()

    suspend operator fun invoke(r: Request): Outcome = c.scope.async { running.withLock { run(r) } }.await()

    /** Runs [block] in the app's scope once no save is running (one may still be reading the picked photo). */
    fun whenIdle(block: suspend () -> Unit) {
        c.scope.launch { running.withLock { block() } }
    }

    private suspend fun run(r: Request): Outcome {
        val notes = ArrayList<Int>()
        val mirrors = ArrayList<RelationMirrors.Report>()
        val temporary = r.temporary?.takeIf { r.original == null && (r.vaultId ?: 0L) <= 0L }
        val id = suspendRunCatching {
            when {
                temporary != null -> saveTemporary(r, temporary, notes, mirrors)
                r.toVault -> saveVault(r, notes)
                else -> saveContact(r, notes, mirrors)
            }
        }.getOrElse { e ->
            if (e is ContactChangedElsewhereException) return Outcome.ChangedElsewhere(reload(r.original))
            if (e is VaultCrypto.LockedException) return Outcome.Locked
            return Outcome.Failed(UserErrorText.of(c.appContext, e))
        } ?: return Outcome.NotSaved
        // A photo the camera app took for this contact has been copied where it belongs.
        ContactCamera.forget(c.appContext, r.photo)
        // The expiry picked in the editor; the vault's was written with the contact itself.
        val expiry = r.expiry
        if (expiry != null && !r.toVault && suspendRunCatching { applyExpiry(r, id, expiry) }.isFailure) notes += R.string.editor_expiry_failed
        // A temporary contact the user just edited for real is asked once whether to keep it (unless its time was
        // just chosen here, which answers that already).
        val key = r.original?.lookupKey
        val askKeep = expiry == null && !r.toVault && !key.isNullOrEmpty() && c.temporaries.needsKeepPrompt(key)
        return Outcome.Saved(id, key.takeIf { askKeep }, notes, mirrors.firstOrNull()?.takeUnless { it.isEmpty })
    }

    /** The copy the editor was editing, as it is now. */
    private suspend fun reload(original: ContactDetails?): ContactDetails? {
        val o = original ?: return null
        val raw = o.editRawId
        val id = withContext(Dispatchers.IO) {
            raw?.let { c.contacts.contactsOfRaws(listOf(it))[it] } ?: c.contacts.currentOf(o.lookupKey, o.id)?.first
        } ?: return null
        val now = suspendRunCatching { if (raw != null) c.contacts.editableRaw(id, raw) else c.contacts.editable(id) }.getOrNull()
        // The edited copy itself is gone (only another copy is left): treat it as removed.
        return now?.takeIf { raw == null || it.editRawId == raw }
    }

    private suspend fun saveVault(r: Request, notes: MutableList<Int>): Long {
        val e = r.draft
        val cleaned = e.copy(handles = e.handles.filter { it.value.isNotBlank() })
        // Made temporary (or given a new time) in the editor: the expiry is saved with it, and like "Delete
        // automatically" on the contact page its call history goes too, unless it was temporary already with its own
        // choice; "Keep permanently" clears it afterwards.
        val after = r.expiry as? ExpiryChange.After
        val existing = r.vaultId?.takeIf { it > 0 }
        val already = existing?.let { c.vault.summary(it)?.expiresAt } != null
        val id = c.vault.save(
            existing, cleaned,
            expiresAt = after?.let { System.currentTimeMillis() + it.days * TemporaryChoice.DAY_MS },
            purgeHistory = if (after != null) TemporaryChoice.purgeOnNewDate(already) else null,
            loaded = r.vaultLoaded.takeIf { existing != null },
        )
        if (r.expiry == ExpiryChange.Keep) c.vault.setExpiry(id, null)
        vaultPhoto(id, r, notes)
        privateExtras(id, r, cleaned, notes)
        return -id // negative ids mark vault contacts for the caller
    }

    /**
     * A private contact's call-screen picture and relation links, kept under its Parley key
     * ([ContactRef.privateKey]) the same way a device contact's are kept under its lookup key.
     */
    private suspend fun privateExtras(id: Long, r: Request, d: ContactDetails, notes: MutableList<Int>) {
        val key = ContactRef.privateKey(id)
        if (r.background != BackgroundChange.None) saveBackground(r.background, key, null, notes)
        val names = d.relations.map { it.value }.filter { it.isNotBlank() }
        val m = c.meta.meta(key)
        val existing = RelationLinks.decode(m?.relationLinks)
        if (names.isEmpty() && existing.isEmpty()) return
        val people = withContext(Dispatchers.IO) { c.contacts.snapshot() }.map { Triple(it.id, it.displayName, it.lookupKey) }
        val links = RelationLinks.update(names, existing, people, self = -id, picked = r.pickedLinks)
        if (links == existing) return
        c.meta.ensureMeta(key, -id)
        c.meta.setRelationLinks(key, RelationLinks.encode(links).ifEmpty { null })
    }

    /**
     * The encrypted caller photo, decoded reduced and upright from the picked file, never read whole; and the picture
     * as picked, sealed too, for the contact page and the photo viewer.
     */
    private suspend fun vaultPhoto(id: Long, r: Request, notes: MutableList<Int>) {
        val picked = r.photo
        val originals = c.people.originals
        if (picked != null) {
            // The framed square for the avatar (the call screen and lists show it); the whole picture otherwise.
            val framed = framedAvatar(r) { null }
            val bytes = framed ?: withContext(Dispatchers.IO) { ContactPhotoProcessor.process(c.appContext.contentResolver, picked) }
            if (bytes == null || !c.vault.setPhoto(id, bytes)) {
                notes += R.string.edit_photo_failed
                originals.clearPrivate(id)
            } else if (!originals.keepPrivate(id, picked, r.originalAnswers)) {
                originals.clearPrivate(id)
            } else {
                originals.setPrivateFrame(id, r.photoFrame.takeIf { framed != null })
            }
        } else if (r.removePhoto) {
            c.vault.removePhoto(id)
            originals.clearPrivate(id)
        } else if (r.photoFrame != null) {
            // "Adjust framing": a new avatar cut from the kept picture.
            val bytes = framedAvatar(r) { originals.forPrivate(id) }
            if (bytes == null || !c.vault.setPhoto(id, bytes)) notes += R.string.edit_photo_failed else originals.setPrivateFrame(id, r.photoFrame)
        }
    }

    /**
     * The avatar cut to [Request.photoFrame], as a square JPEG: from the picked photo, or from the kept original
     * ([kept]) when only the framing changed. Null without a frame, or when the picture can't be read.
     */
    private suspend fun framedAvatar(r: Request, kept: suspend () -> OriginalPhotos.Original?): ByteArray? {
        val frame = r.photoFrame ?: return null
        val picked = r.photo
        if (picked != null) return withContext(Dispatchers.IO) { ContactPhotoProcessor.processFramed(c.appContext.contentResolver, picked, frame) }
        val o = kept() ?: return null
        return try {
            c.people.originals.framed(o, frame)
        } finally {
            c.people.originals.release(o)
        }
    }

    /**
     * The framed avatar as a file for Android's contacts provider (which takes a picture to read, not bytes), or
     * null without a frame. Deleted by the caller once written.
     */
    private suspend fun avatarFile(r: Request, kept: suspend () -> OriginalPhotos.Original?): File? {
        val bytes = framedAvatar(r, kept) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(c.appContext.cacheDir, ContactCamera.FRAMED_DIR).apply { mkdirs() }
                File(dir, "avatar-${System.nanoTime()}.jpg").apply { writeBytes(bytes) }
            }.getOrNull()
        }
    }

    /**
     * Keeps the picked picture whole for Parley's own contact page (Android keeps a reduced copy for other apps), or
     * forgets it when the photo was removed. [before]: Android's photo URI before this save.
     */
    private suspend fun originalPhoto(r: Request, id: Long, before: String?) {
        val key = withContext(Dispatchers.IO) { c.contacts.lookupKeyOf(id) }?.takeIf { it.isNotEmpty() } ?: return
        val old = r.original?.lookupKey?.takeIf { it.isNotEmpty() && it != key }
        val picked = r.photo
        val originals = c.people.originals
        when {
            picked != null -> if (!originals.keep(key, picked, before, r.originalAnswers)) originals.clear(key) else originals.setFrame(key, r.photoFrame)
            r.removePhoto -> originals.clear(key)
            r.photoFrame != null -> {
                // Only the framing changed: the same original, now matched to the avatar just written.
                old?.let { originals.move(it, key) }
                originals.setFrame(key, r.photoFrame, rewritten = before.orEmpty())
                return
            }
            else -> return
        }
        old?.let { originals.clear(it) }
    }

    /**
     * Adds the opposite relation to the saved contacts this one's relations point to, and takes back what Parley added
     * there for relations removed or changed here ("Add relations to both contacts" in Settings › Contacts).
     */
    private suspend fun mirrorRelations(r: Request, id: Long, out: MutableList<RelationMirrors.Report>) {
        if (r.draft.relations.isEmpty() && r.original?.relations.isNullOrEmpty()) return
        if (!c.settings.current().mirrorRelations) return
        val links = withContext(Dispatchers.IO) { c.contacts.lookupKeyOf(id)?.let { c.meta.meta(it) }?.relationLinks }
        suspendRunCatching { c.people.relationMirrors.mirror(id, r.draft.relations, RelationLinks.decode(links), r.original?.relations.orEmpty()) }
            .onSuccess { out += it }
    }

    /**
     * A new temporary contact, through the same entry point as the keypad's "Save temporary contact": private in the
     * vault (the default) or a phone-only contact that other apps can see; either way it deletes itself in time.
     */
    private suspend fun saveTemporary(r: Request, t: TemporaryChoice, notes: MutableList<Int>, mirrors: MutableList<RelationMirrors.Report>): Long? {
        val e = r.draft
        val details = if (t.private) e.copy(handles = e.handles.filter { it.value.isNotBlank() }) else e
        val avatar = if (t.private) null else avatarFile(r) { null }
        val saved = try {
            TemporaryContacts.saveDetails(c, details, t.days, t.private, t.purgeHistory, photo = avatar?.let(Uri::fromFile) ?: r.photo.takeUnless { t.private })
        } finally {
            avatar?.delete()
        } ?: return null
        if (saved.private) {
            vaultPhoto(saved.id, r, notes)
            // Relation links picked in the editor and the call-screen picture, as for any private contact.
            privateExtras(saved.id, r, details, notes)
            return -saved.id
        }
        rememberRelations(saved.id, e, r.pickedLinks, r.original)
        originalPhoto(if (avatar == null && r.photoFrame != null) r.copy(photoFrame = null) else r, saved.id, null)
        mirrorRelations(r, saved.id, mirrors)
        return saved.id
    }

    /**
     * An existing phone contact made temporary, given a new time, or kept: the same store the contact page's
     * "Delete automatically" writes (its raw contacts are recorded; the call history setting it had is kept).
     */
    private suspend fun applyExpiry(r: Request, id: Long, e: ExpiryChange) {
        val before = r.original?.lookupKey?.takeIf { it.isNotEmpty() }
        when (e) {
            ExpiryChange.Keep -> {
                // The save can give the contact a new lookup key; the entry may sit under either.
                val now = withContext(Dispatchers.IO) { c.contacts.lookupKeyOf(id) }?.takeIf { it.isNotEmpty() }
                listOfNotNull(before, now).distinct().forEach { c.temporaries.clear(it) }
            }
            is ExpiryChange.After -> {
                val purge = before?.let { c.temporaries.forKey(it) }?.purgeHistory ?: true
                c.temporaries.mark(id, e.days, purge)
            }
        }
    }

    private suspend fun saveContact(r: Request, notes: MutableList<Int>, mirrors: MutableList<RelationMirrors.Report>): Long? {
        // Android's photo before this save, so the kept original can tell Android's new copy from the old one.
        val photoBefore = r.original?.photoUri
        // A framed photo goes to Android as its square; the picture as picked is kept whole below (originalPhoto).
        val avatar = avatarFile(r) { c.people.originals.forContact(r.original?.lookupKey, photoBefore) }
        // A frame that couldn't be cut isn't recorded (a new photo is then written whole, as before framing).
        val frameFailed = avatar == null && r.photoFrame != null
        if (frameFailed && r.photo == null) notes += R.string.edit_photo_failed
        val photo = avatar?.let(Uri::fromFile) ?: r.photo
        val saved = try {
            c.contacts.save(r.original, r.draft, r.account, photo, r.removePhoto)?.contactId
        } finally {
            avatar?.delete()
        }
        val before = r.original?.lookupKey?.takeIf { it.isNotEmpty() }
        if (before != null && r.background != BackgroundChange.None) saveBackground(r.background, before, saved, notes)
        if (saved != null) {
            rememberRelations(saved, r.draft, r.pickedLinks, r.original)
            suspendRunCatching { originalPhoto(if (frameFailed) r.copy(photoFrame = null) else r, saved, photoBefore) }
            mirrorRelations(r, saved, mirrors)
        }
        return saved
    }

    /**
     * The call-screen picture. The save itself can give the contact a new lookup key (a phone-only contact's key holds
     * its name; a contact with no writable copy gets a linked device copy), so the picture goes under the key it has now.
     */
    private suspend fun saveBackground(change: BackgroundChange, before: String, saved: Long?, notes: MutableList<Int>) {
        val now = saved?.let { id -> withContext(Dispatchers.IO) { c.contacts.lookupKeyOf(id) } }?.takeIf { it.isNotEmpty() } ?: before
        val bg = c.people.backgrounds
        when (change) {
            BackgroundChange.None -> Unit
            BackgroundChange.Remove -> {
                bg.clear(now)
                if (now != before) bg.clear(before)
            }
            is BackgroundChange.Set -> {
                val result = bg.set(now, change.uri)
                if (result != CallBackgrounds.SetResult.OK) notes += CallBackgroundText.failure(result) else if (now != before) bg.clear(before)
            }
        }
    }

    /**
     * Remembers which contact each relation names, by lookup key, beside the name-only Data row; and the relations kept
     * in Parley only ([ParleyRelationRows]) when the editor changed them ([original]: the contact as it loaded).
     */
    private suspend fun rememberRelations(
        contactId: Long,
        e: ContactDetails,
        picked: Map<String, RelationLinks.Link>,
        original: ContactDetails?,
    ) = withContext(Dispatchers.IO) {
        val key = c.contacts.lookupKeyOf(contactId) ?: return@withContext
        if (e.parleyRelations != original?.parleyRelations.orEmpty()) {
            val stored = ParleyRelationRows.encode(e.parleyRelations)
            c.meta.ensureMeta(key, contactId)
            c.meta.setParleyRelations(key, stored)
            // The save may have given the contact a new key; the row under the old one would bring removed ones back
            // when the two rows are merged.
            original?.lookupKey?.takeIf { it.isNotEmpty() && it != key }?.let { old -> c.meta.setParleyRelations(old, stored) }
        }
        val m = c.meta.meta(key)
        val existing = RelationLinks.decode(m?.relationLinks)
        val names = (e.relations + e.parleyRelations).map { it.value }.filter { it.isNotBlank() }
        if (names.isEmpty() && existing.isEmpty()) return@withContext
        val people = c.contacts.snapshot().map { Triple(it.id, it.displayName, it.lookupKey) }
        val links = RelationLinks.update(names, existing, people, self = contactId, picked = picked)
        if (links == existing) return@withContext
        val encoded = RelationLinks.encode(links).ifEmpty { null }
        // Targeted writes, so the rest of the row (the Circle's fields, the pinned note) is never overwritten.
        if (m == null) {
            c.meta.setMeta(ContactMetaEntity(key).copy(contactId = contactId, relationLinks = encoded))
        } else {
            c.meta.setRelationLinks(key, encoded)
            c.meta.setMetaContactId(key, contactId)
        }
    }
}
