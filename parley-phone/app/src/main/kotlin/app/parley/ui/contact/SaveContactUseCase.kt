package app.parley.ui.contact

import android.net.Uri
import app.parley.R
import app.parley.common.people.ContactRef
import app.parley.common.people.ExpiryChange
import app.parley.common.people.RelationLinks
import app.parley.common.people.TemporaryChoice
import app.parley.common.suspendRunCatching
import app.parley.data.AccountRef
import app.parley.data.ContactChangedElsewhereException
import app.parley.data.ContactDetails
import app.parley.data.ContactPhotoProcessor
import app.parley.data.DataContainer
import app.parley.data.TemporaryContacts
import app.parley.data.db.ContactMetaEntity
import app.parley.data.people.CallBackgrounds
import app.parley.ui.people.BackgroundChange
import app.parley.ui.people.CallBackgroundText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

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
    )

    sealed interface Outcome {
        /**
         * Saved as [id] (negative for a private contact). [keepPromptKey]: a temporary contact that should now be
         * asked about once. [notes]: string resources for parts that didn't make it (photo, background).
         */
        data class Saved(val id: Long, val keepPromptKey: String?, val notes: List<Int>) : Outcome

        /** The provider refused without an error (nothing to show). */
        data object NotSaved : Outcome

        data class Failed(val message: String) : Outcome

        /**
         * Another app or a sync changed the contact since the editor loaded it, so nothing was written. [theirs] is
         * the contact as it is now (null when it is gone).
         */
        data class ChangedElsewhere(val theirs: ContactDetails?) : Outcome
    }

    suspend operator fun invoke(r: Request): Outcome = c.scope.async { run(r) }.await()

    private suspend fun run(r: Request): Outcome {
        val notes = ArrayList<Int>()
        val temporary = r.temporary?.takeIf { r.original == null && (r.vaultId ?: 0L) <= 0L }
        val id = suspendRunCatching {
            when {
                temporary != null -> saveTemporary(r, temporary, notes)
                r.toVault -> saveVault(r, notes)
                else -> saveContact(r, notes)
            }
        }.getOrElse { e ->
            if (e is ContactChangedElsewhereException) return Outcome.ChangedElsewhere(reload(r.original))
            return Outcome.Failed(e.message.orEmpty())
        } ?: return Outcome.NotSaved
        // The expiry picked in the editor; the vault's was written with the contact itself.
        val expiry = r.expiry
        if (expiry != null && !r.toVault && suspendRunCatching { applyExpiry(r, id, expiry) }.isFailure) notes += R.string.editor_expiry_failed
        // A temporary contact the user just edited for real is asked once whether to keep it (unless its time was
        // just chosen here, which answers that already).
        val key = r.original?.lookupKey
        val askKeep = expiry == null && !r.toVault && !key.isNullOrEmpty() && c.temporaries.needsKeepPrompt(key)
        return Outcome.Saved(id, key.takeIf { askKeep }, notes)
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
        // automatically" on the contact page its call history goes too; "Keep permanently" clears it afterwards.
        val after = r.expiry as? ExpiryChange.After
        val id = c.vault.save(
            r.vaultId?.takeIf { it > 0 }, cleaned,
            expiresAt = after?.let { System.currentTimeMillis() + it.days * TemporaryChoice.DAY_MS },
            purgeHistory = if (after != null) true else null,
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

    /** The encrypted caller photo, decoded reduced and upright from the picked file, never read whole. */
    private suspend fun vaultPhoto(id: Long, r: Request, notes: MutableList<Int>) {
        val picked = r.photo
        if (picked != null) {
            val bytes = withContext(Dispatchers.IO) { ContactPhotoProcessor.process(c.appContext.contentResolver, picked) }
            if (bytes == null || !c.vault.setPhoto(id, bytes)) notes += R.string.edit_photo_failed
        } else if (r.removePhoto) {
            c.vault.removePhoto(id)
        }
    }

    /**
     * A new temporary contact, through the same entry point as the keypad's "Save temporary contact": private in the
     * vault (the default) or a phone-only contact that other apps can see; either way it deletes itself in time.
     */
    private suspend fun saveTemporary(r: Request, t: TemporaryChoice, notes: MutableList<Int>): Long? {
        val e = r.draft
        val details = if (t.private) e.copy(handles = e.handles.filter { it.value.isNotBlank() }) else e
        val saved = TemporaryContacts.saveDetails(c, details, t.days, t.private, t.purgeHistory, photo = r.photo.takeUnless { t.private })
            ?: return null
        if (saved.private) {
            vaultPhoto(saved.id, r, notes)
            return -saved.id
        }
        rememberRelations(saved.id, e, r.pickedLinks)
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

    private suspend fun saveContact(r: Request, notes: MutableList<Int>): Long? {
        val saved = c.contacts.save(r.original, r.draft, r.account, r.photo, r.removePhoto)?.contactId
        val before = r.original?.lookupKey?.takeIf { it.isNotEmpty() }
        if (before != null && r.background != BackgroundChange.None) saveBackground(r.background, before, saved, notes)
        if (saved != null) rememberRelations(saved, r.draft, r.pickedLinks)
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

    /** Remembers which contact each relation names, by lookup key, beside the name-only Data row. */
    private suspend fun rememberRelations(contactId: Long, e: ContactDetails, picked: Map<String, RelationLinks.Link>) = withContext(Dispatchers.IO) {
        val key = c.contacts.lookupKeyOf(contactId) ?: return@withContext
        val m = c.meta.meta(key)
        val existing = RelationLinks.decode(m?.relationLinks)
        val names = e.relations.map { it.value }.filter { it.isNotBlank() }
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
