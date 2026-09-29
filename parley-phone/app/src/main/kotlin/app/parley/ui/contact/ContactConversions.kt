package app.parley.ui.contact

import app.parley.common.backup.CallLogRecord
import app.parley.common.calltime.LimitScope
import app.parley.common.people.ContactRef
import app.parley.common.people.PrivateLabels
import app.parley.common.suspendRunCatching
import app.parley.data.vault.PrivateCall
import app.parley.data.vault.VaultMoves
import app.parley.data.vault.VaultSummary
import app.parley.data.AccountRef
import app.parley.data.CallLogRepository
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Converting one contact between its variants without losing anything (docs/CONTACT_MODEL.md, "Conversions"):
 *
 * - [makePrivate]: out of the address book into the vault. Every field and the photo go with it (the lossless record
 *   of [app.parley.data.vault.VaultMoves]), and what Parley keeps about the person (notes, the Circle, logged
 *   moments, the call-screen picture, relation links, a temporary date) is re-keyed to the private contact.
 * - [makeVisible]: back into the address book, the same the other way; calls kept in the private call history go
 *   back to the phone's call history.
 *
 * Both run in the app's scope, so leaving the page half way never leaves a half-converted contact.
 */
class ContactConversions(private val c: DataContainer) {
    data class MadePrivate(val vaultId: Long, val removedAfterSync: Boolean, val messengerCopies: Boolean)

    /**
     * Device contact [contactId] ([d] as shown) becomes private. Throws [app.parley.data.vault.VaultCrypto.LockedException]
     * when the vault must be unlocked first, and [IllegalStateException] when the contact can't be read whole
     * (nothing changes then).
     */
    suspend fun makePrivate(contactId: Long, d: ContactDetails): MadePrivate = inApp {
        val key = d.lookupKey.takeIf { it.isNotEmpty() }
        val meta = key?.let { c.meta.meta(it) }
        val temporary = key?.let { c.temporaries.forKey(it) }
        // The note for calls and the usual app live in the sealed entry (the call screen reads the note from there,
        // even while the phone is locked); everything else Parley keeps is re-keyed below.
        val shown = d.copy(
            pinnedNote = d.pinnedNote.ifBlank { meta?.pinnedNote.orEmpty() },
            messengerPrefs = d.messengerPrefs.ifBlank { meta?.preferredMessenger.orEmpty() },
        )
        val moved = c.vaultMoves.moveIn(contactId, shown, carryInteractions = false)
        val id = moved.vaultId
        // A temporary contact stays temporary, with the same date and call-history choice.
        if (temporary != null) c.vault.save(id, shown, expiresAt = temporary.expiresAt, purgeHistory = temporary.purgeHistory)
        if (key != null) {
            // The relations Parley wrote on other contacts for this one ("Child: Sam" on Ana) would keep its name in
            // the address book: taken back where still as Parley left them, and forgotten from Parley's record.
            suspendRunCatching { c.people.relationMirrors.takeBack(contactId, key) }
            // No readable copy stays outside the vault: no undo journal entry, no address-book snapshots.
            c.journal.forget(key)
            c.timeMachine.purge(key)
            val privateKey = ContactRef.privateKey(id)
            c.contactKeys.rekey(key, privateKey, -id)
            // The expiry is the vault entry's own now, and the note and usual app are in its sealed details.
            c.meta.clearTemporary(privateKey)
            c.meta.meta(privateKey)?.let {
                if (it.pinnedNote != null) c.meta.setPinnedNote(privateKey, -id, null)
                if (it.preferredMessenger != null) c.meta.setPreferredMessenger(privateKey, -id, null)
            }
        }
        // With "Private call history" on, their calls and ring facts leave the phone's call history too.
        if (c.settings.current().privateVaultHistory) {
            d.phones.forEach { p -> runCatching { c.ringFacts.forget(p.value); c.callQuality.forget(p.value) } }
            runCatching { c.vault.sweepCallLog(0) }
        }
        MadePrivate(id, moved.removedAfterSync, moved.messengerCopies)
    }

    /** What [makeVisible] did. */
    sealed interface MadeVisible {
        /** In the address book as contact [contactId]. */
        data class Done(val contactId: Long) : MadeVisible

        /** The contact couldn't be written: the private contact stays as it was. */
        data object NotWritten : MadeVisible

        /**
         * Its private calls couldn't go back to the phone's call history (no permission, or some can't be opened right
         * now): nothing changed, so they aren't lost with the private entry.
         */
        data object CallsKept : MadeVisible
    }

    /**
     * Private contact [vaultId] ([d]: its details, unlocked) goes back to the address book under [account] (used when
     * the entry wasn't moved in from there). The private calls are put back into the phone's call history before the
     * entry (their only copy) is deleted; if that fails, nothing changes ([MadeVisible.CallsKept]).
     */
    suspend fun makeVisible(vaultId: Long, d: ContactDetails, account: AccountRef): MadeVisible = inApp {
        val privateKey = ContactRef.privateKey(vaultId)
        // An entry from before the caller-ID copy kept them gets its star, labels, ringtone and voicemail first.
        suspendRunCatching { c.vault.seedCallerChoices(vaultId) }
        val summary = c.vault.summary(vaultId)
        // Read before the entry goes: the private call history is deleted with it. All of it, or the move waits.
        val calls = c.vault.privateCallsOf(vaultId)
        if (calls.size < c.vault.privateCallCount(vaultId)) return@inApp MadeVisible.CallsKept
        val clean = d.copy(id = 0, lookupKey = "", photoUri = null)
        // The photo as picked, opened now: the entry (and its sealed original) is deleted by the move.
        val original = withContext(Dispatchers.IO) { c.people.originals.take(privateKey) }
        val moved = when (val m = c.vaultMoves.moveOut(vaultId, clean, account) { restoreCalls(calls) }) {
            is VaultMoves.MovedOut.Done -> m
            VaultMoves.MovedOut.NotWritten -> return@inApp MadeVisible.NotWritten
            VaultMoves.MovedOut.Undone -> return@inApp MadeVisible.CallsKept
        }
        val newId = moved.contactId
        // What Parley kept for it while private becomes the address book's again: star, ringtone, "send to
        // voicemail" and its labels as they are now (the stored record may hold older ones). An entry whose caller-ID
        // copy doesn't hold them (not seeded: its details couldn't be read) keeps what the record put back.
        if (summary != null && summary.choicesKnown) toAddressBook(newId, summary)
        val key = keyOf(newId)
        if (key != null) {
            c.contactKeys.rekey(privateKey, key, newId)
            original?.let { o -> withContext(Dispatchers.IO) { c.people.originals.put(key, o) } }
            parleyRow(key, newId, clean)
            // A limit kept no name while private: the address book's contact has one again.
            c.calling.update { cfg -> cfg.rule(LimitScope.CONTACT, key)?.let { r -> cfg.withRule(r.copy(title = summary?.name ?: d.displayName)) } ?: cfg }
        } else {
            // Not readable back yet (the provider is still joining it): what Parley keeps waits under the private key,
            // with the original photo and the note, and the next key sweep moves it all to the contact. Never forgotten.
            original?.let { o -> withContext(Dispatchers.IO) { c.people.originals.put(privateKey, o) } }
            parleyRow(privateKey, newId, clean)
            c.contactKeys.rekeyLater(privateKey, moved.rawIds.ifEmpty { withContext(Dispatchers.IO) { c.contacts.rawIds(newId) } }, newId)
        }
        // Only the raw contacts this move inserted: Android may have joined them with someone else's copies.
        summary?.expiresAt?.let { at -> c.temporaries.markAt(newId, at, summary.purgeHistory, summary.name, rawIds = moved.rawIds.ifEmpty { null }) }
        MadeVisible.Done(newId)
    }

    /**
     * Deletes private contact [vaultId] with everything Parley kept about it. A sealed copy is kept first in "Recently
     * deleted" for 30 days ([app.parley.data.vault.PrivateTrash]) unless [keepCopy] is false; History & undo never
     * holds a private contact. False (nothing deleted) when the promised copy couldn't be kept: the caller offers to
     * delete without one.
     */
    suspend fun deletePrivate(vaultId: Long, keepCopy: Boolean = true): Boolean = inApp {
        if (keepCopy && !suspendRunCatching { c.privateTrash.keep(vaultId) }.getOrDefault(false)) return@inApp false
        c.vault.delete(vaultId)
        c.contactKeys.forget(ContactRef.privateKey(vaultId))
        true
    }

    /** The note for calls and the usual app go back to Parley's row for them (only fields the vault carried). */
    private suspend fun parleyRow(key: String, contactId: Long, clean: ContactDetails) {
        if (clean.pinnedNote.isBlank() && clean.messengerPrefs.isBlank()) return
        c.meta.ensureMeta(key, contactId)
        if (clean.pinnedNote.isNotBlank()) c.meta.setPinnedNote(key, contactId, clean.pinnedNote)
        if (clean.messengerPrefs.isNotBlank()) c.meta.setPreferredMessenger(key, contactId, clean.messengerPrefs)
    }

    /**
     * The new contact's lookup key. It can briefly be unreadable right after the insert (aggregation runs
     * asynchronously), so it is read a few times, like [app.parley.data.people.TemporaryContactStore.createPhone].
     */
    private suspend fun keyOf(contactId: Long): String? {
        repeat(KEY_TRIES) { attempt ->
            withContext(Dispatchers.IO) { c.contacts.lookupKeyOf(contactId) }?.takeIf { it.isNotEmpty() }?.let { return it }
            if (attempt < KEY_TRIES - 1) delay(KEY_RETRY_MS)
        }
        return null
    }

    /** A contact made visible gets what the private one had: star, ringtone, "send to voicemail" and labels. */
    private suspend fun toAddressBook(contactId: Long, s: VaultSummary) = withContext(Dispatchers.IO) {
        runCatching { c.contacts.setStarred(contactId, s.starred) }
        runCatching { c.contacts.setRingtone(contactId, s.ringtone) }
        runCatching { c.contacts.setSendToVoicemail(contactId, s.sendToVoicemail) }
        val groups = c.contacts.groups()
        val wanted = PrivateLabels.titles(s.labels, groups.map { PrivateLabels.Group(it.id, it.title) })
        val have = c.contacts.labelTitlesOrNull(contactId) ?: return@withContext
        // Into a group of that label the contact's account can hold (the label may exist in several accounts).
        for (title in wanted - have) {
            for (g in groups.filter { it.title.trim() == title }) if (runCatching { c.contacts.addToGroup(listOf(contactId), g) }.getOrDefault(1) == 0) break
        }
        (have - wanted).forEach { t -> runCatching { c.people.labels.removeMembers(t, listOf(contactId)) } }
    }

    /** Runs [block] in the app's scope: the caller may stop waiting, the conversion still finishes. */
    private suspend fun <T> inApp(block: suspend () -> T): T = c.scope.async { block() }.await()

    /**
     * Private calls back into the phone's call history, skipping any it already has. True when every one is there now
     * (the move only goes ahead then: the private history is their only copy).
     */
    private suspend fun restoreCalls(calls: List<PrivateCall>): Boolean = withContext(Dispatchers.IO) {
        if (calls.isEmpty()) return@withContext true
        val have = c.callLog.rowSignatures()
        val records = calls.filter { CallLogRepository.signature(it.number, it.date, it.durationSec, it.type) !in have }
            .map { CallLogRecord(it.number, it.date, it.durationSec, it.type, name = it.name) }
        val written = if (records.isEmpty()) 0 else c.callLog.insert(records)
        c.callLog.refresh()
        written == records.size
    }

    private companion object {
        const val KEY_TRIES = 5
        const val KEY_RETRY_MS = 200L
    }
}
