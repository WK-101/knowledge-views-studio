package app.parley.data.vault

import app.parley.common.circle.CarriedInteraction
import app.parley.common.circle.InteractionChannel
import app.parley.common.circle.InteractionType
import app.parley.common.circle.Interactions
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import app.parley.common.record.withoutMessengers
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.circle.InteractionStore
import app.parley.data.records.ContactRecordStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moving contacts into and out of the private vault without losing anything.
 *
 * In: the vault keeps the lossless [ContactRecord] (IM, SIP, department, PO box, custom rows, the photo…) beside the
 * editable details, sealed with the strong key. Phone-only and never-synced copies are purged at once; synced copies
 * are deleted and disappear from other apps after the account's next sync.
 *
 * Out: the record is inserted back as it was (accounts kept when still writable here); edits made in the vault since
 * are applied on top.
 *
 * The contact's logged interactions (with their notes) travel sealed inside the vault entry, so they are never
 * shown while the contact is private and are logged again under the restored contact on "Move out".
 */
class VaultMoves(
    private val vault: VaultRepository,
    private val contacts: ContactsRepository,
    private val records: ContactRecordStore,
    private val interactions: () -> InteractionStore? = { null },
) {
    /** [messengerCopies]: WhatsApp, Signal… copies stay until that app resyncs its contacts. */
    data class MovedIn(val vaultId: Long, val removedAfterSync: Boolean, val messengerCopies: Boolean = false)

    /**
     * Throws [VaultCrypto.LockedException] when the vault must be unlocked first, and [IllegalStateException] when
     * the whole contact can't be read; nothing is deleted then (the phone copy is only removed once the vault holds
     * everything it had: there is no journal copy of a contact moved into the vault).
     *
     * [carryInteractions] false: the caller re-keys the logged interactions to the private contact instead
     * (`ContactKeys.rekey`), so they stay on its page rather than being sealed away until "Make visible".
     */
    suspend fun moveIn(contactId: Long, shown: ContactDetails, carryInteractions: Boolean = true): MovedIn = withContext(Dispatchers.IO) {
        val record = records.read(contactId, fullPhoto = true)?.let { capPhoto(contactId, it) }?.withoutMessengers()
            ?: error("Couldn't read the whole contact, so it wasn't moved")
        // Read before the caller forgets the key (ContactKeys.forget deletes them outside the vault).
        val carried = shown.lookupKey.takeIf { it.isNotEmpty() && carryInteractions }?.let { key ->
            runCatching { interactions()?.interactionsFor(key) }.getOrNull().orEmpty()
                .map { CarriedInteraction(it.type.name, it.channel?.name, it.time, it.note, it.dedupeKey) }
        }.orEmpty()
        val id = vault.save(null, shown, record = record, interactions = Interactions.encodeCarried(carried))
        // The contact's photo becomes the private contact's (encrypted) caller photo.
        record.raws.asSequence().flatMap { it.rows }.firstOrNull { it.mimeType == Mime.PHOTO && (it.blob?.size ?: 0) > 0 }?.blob
            ?.let { runCatching { vault.setPhoto(id, it) } }
        val purged = contacts.purgeForVault(contactId)
        contacts.refresh()
        MovedIn(id, purged.synced, purged.messengerCopies)
    }

    /** What [moveOut] did. */
    sealed interface MovedOut {
        /**
         * Back in the address book as contact [contactId]. [rawIds]: the raw contacts this move inserted, and only
         * those: Android may join them with other raw contacts of the same person (a copy in another account, the
         * messenger copies "Make private" left behind), which were never Parley's to delete. [redirectedTo]: Android 16
         * refused the phone and put the contact in this cloud account instead (the caller says so).
         */
        data class Done(val contactId: Long, val rawIds: List<Long>, val redirectedTo: AccountRef? = null) : MovedOut

        /** Nothing could be written: the entry stays as it was. */
        data object NotWritten : MovedOut

        /** [moveOut]'s `beforeDelete` failed: what was inserted was taken back, and the entry stays as it was. */
        data object Undone : MovedOut
    }

    /**
     * Moves vault entry [vaultId] back to the phone contacts; [d] are its current details and [account] the fallback
     * for entries without a stored record.
     *
     * [beforeDelete] runs once the contact is in the address book and before the entry (with its private calls) is
     * deleted: putting the private calls back into the phone's call history. When it fails the inserted raw contacts are
     * taken back and nothing else changes ([MovedOut.Undone]), so the private call history is never lost.
     */
    // Any failure of beforeDelete takes the move back; insert, hand-back and clean-up are one sequence.
    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
    suspend fun moveOut(
        vaultId: Long,
        d: ContactDetails,
        account: AccountRef,
        beforeDelete: suspend (contactId: Long) -> Boolean = { true },
    ): MovedOut = withContext(Dispatchers.IO) {
        val stored = vault.storedRecord(vaultId)
        // The raw contact the (encrypted) vault photo goes to: the vault photo is the current one; the record's
        // may be older (changed in the vault since) and an entry made in the vault has none at all.
        var photoRaw: Long? = null
        val inserted = ArrayList<Long>()
        var redirectedTo: AccountRef? = null
        val newId = if (stored == null) {
            contacts.save(null, d, account, null, false, announceRedirect = false)
                ?.also { photoRaw = it.rawId; it.rawId?.let(inserted::add); redirectedTo = it.redirectedTo }?.contactId
        } else {
            val result = records.insertAll(listOf(stored.record), target = null, announceRedirect = false).single()
            inserted += result.rawIds
            redirectedTo = result.redirectedTo
            val id = result.contactId
            photoRaw = result.rawIds.firstOrNull()
            when {
                id == null -> null
                stored.editedSince -> contacts.editable(id)?.let { original ->
                    contacts.save(original, overlay(original, d), null, null, false)?.contactId
                } ?: id
                else -> id
            }
        }
        if (newId == null) {
            // A partly written record goes again rather than staying beside the private contact.
            if (inserted.isNotEmpty()) runCatching { contacts.discardInserted(inserted) }
            contacts.refresh()
            return@withContext MovedOut.NotWritten
        }
        // A sweep of the call log must not take the calls just put back into the private history deleted below.
        vault.leaving += vaultId
        try {
            val ok = try {
                beforeDelete(newId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                runCatching { contacts.discardInserted(inserted) }
                contacts.refresh()
                return@withContext MovedOut.Undone
            }
            // The interactions carried in the entry come back under the restored contact.
            val carried = runCatching { Interactions.decodeCarried(vault.storedInteractions(vaultId)) }.getOrDefault(emptyList())
            val store = interactions()
            val key = if (carried.isNotEmpty() && store != null) contacts.lookupKeyOf(newId) else null
            if (store != null && key != null) carried.forEach { i ->
                val type = InteractionType.entries.firstOrNull { it.name == i.t } ?: InteractionType.OTHER
                // A note that can't be sealed right now: keep the entry itself rather than nothing.
                runCatching { store.log(key, newId, type, InteractionChannel.decode(i.c), i.at, i.note, i.u) }
                    .onFailure { runCatching { store.log(key, newId, type, InteractionChannel.decode(i.c), i.at, null, i.u) } }
            }
            val photo = vault.photoBytes(vaultId)
            val raw = photoRaw ?: contacts.rawIds(newId).firstOrNull()
            if (photo != null && raw != null) runCatching { records.setPhoto(raw, photo) }
            vault.delete(vaultId)
        } finally {
            vault.leaving -= vaultId
        }
        contacts.refresh()
        MovedOut.Done(newId, inserted.distinct(), redirectedTo)
    }

    /** A full-resolution photo can be several MB; keep the vault row small by using the thumbnail then. */
    private fun capPhoto(contactId: Long, r: ContactRecord): ContactRecord {
        val big = r.raws.any { raw -> raw.rows.any { it.mimeType == Mime.PHOTO && (it.blob?.size ?: 0) > MAX_PHOTO } }
        if (!big) return r
        val thumbs = records.read(contactId, fullPhoto = false)?.raws.orEmpty().associateBy { it.rawId }
        return r.copy(
            raws = r.raws.map { raw ->
                val thumb = thumbs[raw.rawId]?.rows?.firstOrNull { it.mimeType == Mime.PHOTO }
                raw.copy(rows = raw.rows.mapNotNull { row -> if (row.mimeType == Mime.PHOTO && (row.blob?.size ?: 0) > MAX_PHOTO) thumb else row })
            },
        )
    }

    companion object {
        private const val MAX_PHOTO = 512 * 1024

        /**
         * [original] (the restored contact, with row ids) changed to the edited vault [d]: single fields are taken
         * from [d]; list rows keep their id when [d] still has the same value, others are added or removed.
         */
        fun overlay(original: ContactDetails, d: ContactDetails): ContactDetails {
            fun items(old: List<DataItem>, new: List<DataItem>): List<DataItem> {
                val pool = old.toMutableList()
                return new.map { n ->
                    val i = pool.indexOfFirst { it.value.trim() == n.value.trim() }
                    if (i >= 0) n.copy(id = pool.removeAt(i).id) else n.copy(id = null)
                }
            }
            val addrPool = original.addresses.toMutableList()
            val events = original.events.toMutableList()
            val handlePool = original.handles.toMutableList()
            val customPool = original.customFields.toMutableList()
            return original.copy(
                prefix = d.prefix, given = d.given, middle = d.middle, family = d.family, suffix = d.suffix,
                phoneticGiven = d.phoneticGiven, phoneticFamily = d.phoneticFamily, nickname = d.nickname, pronouns = d.pronouns,
                phoneticMiddle = d.phoneticMiddle, secondSurname = d.secondSurname, generation = d.generation, language = d.language,
                customFields = d.customFields.map { f ->
                    val i = customPool.indexOfFirst { it.label.trim() == f.label.trim() && it.value.trim() == f.value.trim() }
                    if (i >= 0) customPool.removeAt(i).let { o -> f.copy(id = o.id, mime = o.mime) } else f.copy(id = null, mime = null)
                },
                company = d.company, title = d.title, department = d.department, note = d.note,
                phones = items(original.phones, d.phones), emails = items(original.emails, d.emails),
                websites = items(original.websites, d.websites), relations = items(original.relations, d.relations),
                addresses = d.addresses.map { a ->
                    val i = addrPool.indexOfFirst { it.formatted == a.formatted }
                    if (i >= 0) a.copy(id = addrPool.removeAt(i).id) else a.copy(id = null)
                },
                events = d.events.map { e ->
                    val i = events.indexOfFirst { it.date == e.date && it.type == e.type }
                    if (i >= 0) e.copy(id = events.removeAt(i).id) else e.copy(id = null)
                },
                handles = d.handles.map { h ->
                    val i = handlePool.indexOfFirst { it.service == h.service && it.value.trim() == h.value.trim() }
                    if (i >= 0) h.copy(id = handlePool.removeAt(i).id) else h.copy(id = null)
                },
            )
        }
    }
}
