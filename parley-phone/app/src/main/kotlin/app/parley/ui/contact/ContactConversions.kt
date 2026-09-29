package app.parley.ui.contact

import app.parley.common.backup.CallLogRecord
import app.parley.common.people.ContactRef
import app.parley.data.AccountRef
import app.parley.data.CallLogRepository
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
            d.phones.forEach { p -> runCatching { c.ringFacts.forget(p.value) } }
            runCatching { c.vault.sweepCallLog(0) }
        }
        MadePrivate(id, moved.removedAfterSync, moved.messengerCopies)
    }

    /**
     * Private contact [vaultId] ([d]: its details, unlocked) goes back to the address book under [account] (used when
     * the entry wasn't moved in from there). Returns the new contact id, or null when it couldn't be written (then the
     * private contact stays as it was).
     */
    suspend fun makeVisible(vaultId: Long, d: ContactDetails, account: AccountRef): Long? = inApp {
        val summary = c.vault.summariesNow().firstOrNull { it.id == vaultId }
        // Read before the entry goes: the private call history is deleted with it.
        val calls = runCatching { c.vault.privateCallsOf(vaultId) }.getOrDefault(emptyList())
        val clean = d.copy(id = 0, lookupKey = "", photoUri = null)
        val newId = c.vaultMoves.moveOut(vaultId, clean, account) ?: return@inApp null
        val key = withContext(Dispatchers.IO) { c.contacts.lookupKeyOf(newId) }?.takeIf { it.isNotEmpty() }
        if (key != null) {
            c.contactKeys.rekey(ContactRef.privateKey(vaultId), key, newId)
            // The note for calls and the usual app go back to Parley's row for them (only fields the vault carried).
            if (clean.pinnedNote.isNotBlank() || clean.messengerPrefs.isNotBlank()) {
                c.meta.ensureMeta(key, newId)
                if (clean.pinnedNote.isNotBlank()) c.meta.setPinnedNote(key, newId, clean.pinnedNote)
                if (clean.messengerPrefs.isNotBlank()) c.meta.setPreferredMessenger(key, newId, clean.messengerPrefs)
            }
            summary?.expiresAt?.let { at -> c.temporaries.markAt(newId, at, summary.purgeHistory, summary.name) }
        } else {
            // Not readable back yet: the next key sweep can't find a private key, so forget rather than orphan it.
            c.contactKeys.forget(ContactRef.privateKey(vaultId))
        }
        restoreCalls(calls)
        newId
    }

    /** Deletes private contact [vaultId] with everything Parley kept about it. */
    suspend fun deletePrivate(vaultId: Long) = inApp {
        c.vault.delete(vaultId)
        c.contactKeys.forget(ContactRef.privateKey(vaultId))
    }

    /** Runs [block] in the app's scope: the caller may stop waiting, the conversion still finishes. */
    private suspend fun <T> inApp(block: suspend () -> T): T = c.scope.async { block() }.await()

    /** Private calls back into the phone's call history, skipping any it already has. */
    private suspend fun restoreCalls(calls: List<app.parley.data.vault.PrivateCall>) = withContext(Dispatchers.IO) {
        if (calls.isEmpty()) return@withContext
        val have = runCatching { c.callLog.rowSignatures() }.getOrDefault(emptySet())
        val records = calls.filter { CallLogRepository.signature(it.number, it.date, it.durationSec, it.type) !in have }
            .map { CallLogRecord(it.number, it.date, it.durationSec, it.type, name = it.name) }
        runCatching { c.callLog.insert(records) }
        c.callLog.refresh()
    }
}
