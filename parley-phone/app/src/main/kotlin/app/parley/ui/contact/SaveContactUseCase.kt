package app.parley.ui.contact

import android.net.Uri
import app.parley.R
import app.parley.common.people.RelationLinks
import app.parley.common.suspendRunCatching
import app.parley.data.AccountRef
import app.parley.data.ContactChangedElsewhereException
import app.parley.data.ContactDetails
import app.parley.data.ContactPhotoProcessor
import app.parley.data.DataContainer
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
        val id = suspendRunCatching {
            if (r.toVault) saveVault(r, notes) else saveContact(r, notes)
        }.getOrElse { e ->
            if (e is ContactChangedElsewhereException) return Outcome.ChangedElsewhere(reload(r.original))
            return Outcome.Failed(e.message.orEmpty())
        } ?: return Outcome.NotSaved
        // A temporary contact the user just edited for real is asked once whether to keep it.
        val key = r.original?.lookupKey
        val askKeep = !r.toVault && !key.isNullOrEmpty() && c.temporaries.needsKeepPrompt(key)
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
        val id = c.vault.save(r.vaultId?.takeIf { it > 0 }, cleaned)
        // The encrypted caller photo, decoded reduced and upright from the picked file, never read whole.
        val picked = r.photo
        if (picked != null) {
            val bytes = withContext(Dispatchers.IO) { ContactPhotoProcessor.process(c.appContext.contentResolver, picked) }
            if (bytes == null || !c.vault.setPhoto(id, bytes)) notes += R.string.edit_photo_failed
        } else if (r.removePhoto) {
            c.vault.removePhoto(id)
        }
        return -id // negative ids mark vault contacts for the caller
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
