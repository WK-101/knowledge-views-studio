package app.parley.ui.home

import app.parley.common.people.BulkAction
import app.parley.common.people.BulkActions
import app.parley.common.people.BulkEdit
import app.parley.common.people.BulkEdits
import app.parley.common.people.TemporaryChoice
import app.parley.common.suspendRunCatching
import app.parley.data.AccountRef
import app.parley.data.DataContainer
import app.parley.data.GroupInfo
import app.parley.data.vault.VaultCrypto
import app.parley.ui.contact.ContactConversions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Contacts tab's bulk actions over a mixed selection of device and private contacts (list ids: negative for a
 * private one). Each works the way the contact page does for one contact of either kind; what would copy a private
 * contact out of Parley acts on the device contacts only ([BulkActions.targets]).
 */
class BulkContactActions(private val c: DataContainer) {
    /** Stars (or unstars) all of them: the address book's star, or Parley's own for a private contact. */
    suspend fun star(ids: Collection<Long>, on: Boolean) {
        ids.forEach { id ->
            if (BulkActions.isPrivate(id)) c.vault.updateCallerChoices(-id) { it.copy(starred = on) } else suspendRunCatching { c.contacts.setStarred(id, on) }
        }
        c.contacts.refresh()
    }

    /** Adds them all to [group]'s label; returns how many device contacts had no copy in its account. */
    suspend fun addToLabel(ids: Collection<Long>, group: GroupInfo): Int {
        val skipped = c.people.labels.addMembers(ids, group)
        c.contacts.refresh()
        return skipped
    }

    /** Device contacts among [ids] get a copy in History & undo before a bulk edit changes them (private ones keep none). */
    private suspend fun journal(ids: Collection<Long>) {
        val device = ids.filterNot { BulkActions.isPrivate(it) }
        if (device.isNotEmpty()) suspendRunCatching { c.contacts.recordChange(device, "EDIT") }
    }

    /** Adds them to [group]'s label: how many device contacts had no copy in its account, and who joined (for Undo). */
    suspend fun joinLabel(ids: Collection<Long>, group: GroupInfo): Pair<Int, List<Long>> {
        val joining = BulkEdits.labelChange(BulkEdit.ADD_LABEL, ids, c.people.labels.members(group.title))
        journal(joining)
        return addToLabel(joining, group) to joining
    }

    /** Takes them out of label [title] (every account, and Parley's own memberships); returns who left, for Undo. */
    suspend fun leaveLabel(ids: Collection<Long>, title: String): List<Long> {
        val leaving = BulkEdits.labelChange(BulkEdit.REMOVE_LABEL, ids, c.people.labels.members(title))
        journal(leaving)
        c.people.labels.removeMembers(title, leaving)
        c.contacts.refresh()
        return leaving
    }

    /** Undo of [leaveLabel]: back into [title], in each account the label has. */
    suspend fun rejoinLabel(ids: Collection<Long>, title: String) {
        val groups = withContext(Dispatchers.IO) { c.contacts.groups().filter { it.title == title } }
        val (private, device) = ids.partition { BulkActions.isPrivate(it) }
        groups.firstOrNull()?.let { g -> if (private.isNotEmpty()) c.people.labels.addMembers(private, g) }
        if (device.isNotEmpty()) groups.forEach { g -> c.people.labels.addMembers(device, g) }
        c.contacts.refresh()
    }

    /** Undo of [joinLabel]. */
    suspend fun unjoinLabel(ids: Collection<Long>, title: String) {
        c.people.labels.removeMembers(title, ids)
        c.contacts.refresh()
    }

    /**
     * Gives them all [ringtone] (null: the phone's own): the address book's, or Parley's own for a private contact, as
     * the contact page does. Returns each changed contact's ringtone before, for Undo ([restoreRingtones]). Tunes made
     * from a name that nothing uses any more go at the next start, so Undo can still bring one back.
     */
    suspend fun setRingtone(ids: Collection<Long>, ringtone: String?): Map<Long, String?> {
        val (private, device) = ids.distinct().partition { BulkActions.isPrivate(it) }
        val before = HashMap<Long, String?>(c.contacts.ringtonesOf(device))
        private.forEach { id -> c.vault.summary(-id)?.let { before[id] = it.ringtone } }
        val changed = BulkEdits.previous(before, ringtone)
        journal(changed.keys)
        changed.keys.forEach { applyRingtone(it, ringtone) }
        c.contacts.refresh()
        return changed
    }

    suspend fun restoreRingtones(previous: Map<Long, String?>) {
        previous.forEach { (id, tone) -> applyRingtone(id, tone) }
        c.contacts.refresh()
    }

    private suspend fun applyRingtone(id: Long, ringtone: String?) {
        if (BulkActions.isPrivate(id)) c.vault.updateCallerChoices(-id) { it.copy(ringtone = ringtone) }
        else suspendRunCatching { c.contacts.setRingtone(id, ringtone) }
    }

    /**
     * Calls to [numbers] go out on [sim] (null: ask each time), like the contact page's choice per number. Returns each
     * changed number's SIM before, for Undo ([restoreSims]).
     */
    suspend fun setSim(numbers: Collection<String>, sim: String?): Map<String, String?> {
        val before = numbers.filter { it.isNotBlank() }.distinct().associateWith { c.prefs.simFor(it) }
        val changed = BulkEdits.previous(before, sim)
        changed.keys.forEach { c.prefs.setSimFor(it, sim) }
        return changed
    }

    suspend fun restoreSims(previous: Map<String, String?>) = previous.forEach { (n, sim) -> c.prefs.setSimFor(n, sim) }

    /** What a move to another account did: moved, already there, who couldn't be moved (by name), private ones left out. */
    data class MovedAccount(val moved: Int, val unchanged: Int, val failed: List<String>, val skippedPrivate: Int, val redirectedTo: AccountRef? = null)

    /**
     * Moves the device contacts among [ids] into [account]: each is copied there whole (every field, photo, labels by
     * title, star and ringtone, as a restore does), then its old copies go. History & undo keeps the contact as it was
     * ("Moved"), and Parley's own notes and reminders follow it to the new copy. A private contact stays in Parley.
     */
    suspend fun moveToAccount(ids: Collection<Long>, account: AccountRef, names: Map<Long, String>): MovedAccount = withContext(Dispatchers.IO) {
        val plan = BulkEdits.plan(BulkEdit.MOVE_ACCOUNT, ids)
        var moved = 0
        var unchanged = 0
        val failed = ArrayList<String>()
        var redirected: AccountRef? = null
        for (id in plan.ids) {
            val record = suspendRunCatching { c.records.read(id, fullPhoto = true) }.getOrNull()
            if (record == null || record.raws.isEmpty()) {
                failed += names[id].orEmpty()
                continue
            }
            if (record.raws.all { it.accountType == account.type && it.accountName == account.name }) {
                unchanged++
                continue
            }
            // Only copies in accounts Parley may write go: a messenger's own copy (WhatsApp…) stays with that app.
            val old = record.raws.filter { r -> r.rawId != null && c.contacts.isWritableAccount(AccountRef(r.accountType, r.accountName)) }.mapNotNull { it.rawId }
            val oldKey = c.contacts.lookupKeyOf(id)
            try {
                c.contacts.recordChange(listOf(id), "MOVE")
                val r = c.records.insertAll(listOf(record), account, announceRedirect = false).single()
                val newId = r.contactId
                if (newId == null) {
                    failed += names[id] ?: record.displayName
                    continue
                }
                c.contacts.deleteRawsUnjournaled(old)
                // Android may join the new copy with someone's other copies: follow it to where it is now.
                val now = c.contacts.contactsOfRaws(r.rawIds).values.firstOrNull() ?: newId
                val newKey = c.contacts.lookupKeyOf(now)
                if (oldKey != null && newKey != null && oldKey != newKey) suspendRunCatching { c.contactKeys.rekey(oldKey, newKey, now) }
                redirected = redirected ?: r.redirectedTo
                moved++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed += names[id] ?: record.displayName
            }
        }
        c.contacts.refresh()
        MovedAccount(moved, unchanged, failed, plan.skippedPrivate, redirected)
    }

    /**
     * "Delete automatically after [days]" for all of them (null: keep them), with their call history, like the page's
     * choice. A private contact's date is its vault entry's own.
     */
    suspend fun setExpiry(ids: Collection<Long>, days: Int?) = withContext(Dispatchers.IO) {
        val at = days?.let { System.currentTimeMillis() + it * TemporaryChoice.DAY_MS }
        for (id in ids) {
            if (BulkActions.isPrivate(id)) {
                val v = -id
                val summary = c.vault.summary(v) ?: continue
                // Made temporary now: its call history goes with it, like the page's choice. Already temporary: only the
                // date changes, and the choice it has (maybe "keep the call history") stays.
                val purge = if (at != null) TemporaryChoice.purgeOnNewDate(summary.expiresAt != null) else null
                // Only the date and that choice change: the sealed details are never re-sealed from here (so details whose
                // key is lost keep waiting for the page's "Keep what's left").
                c.vault.setExpiry(v, at, purge)
            } else if (days == null) {
                c.contacts.lookupKeyOf(id)?.let { c.temporaries.clear(it) }
            } else {
                // A contact that is temporary already keeps its call-history choice.
                val purge = c.contacts.lookupKeyOf(id)?.let { c.temporaries.forKey(it) }?.purgeHistory ?: true
                c.temporaries.mark(id, days, purgeHistory = purge)
            }
        }
        c.contacts.refresh()
    }

    /** What a bulk "Move to private" did: how many moved, who couldn't be (by list id and name), and sync notes. */
    data class MovedPrivate(
        val moved: Int,
        val failed: List<Pair<Long, String>>,
        val removedAfterSync: Boolean = false,
        val messengerCopies: Boolean = false,
    )

    /**
     * "Move to private" for the device contacts among [ids], one at a time, the same conversion as the page's
     * ([ContactConversions.makePrivate]); [names] names them in the result, [onProgress] gets (done, total).
     *
     * A contact that can't be moved (gone, unreadable, a failed write) is listed in the result and the others still
     * move. A locked vault stops the batch with [BulkLocked] before the contact it was about to move (nothing of it
     * changed), so the caller can unlock and go on with [BulkLocked.remaining], passing [BulkLocked.soFar] as [already].
     */
    suspend fun makePrivate(
        ids: Collection<Long>,
        names: Map<Long, String>,
        already: MovedPrivate = MovedPrivate(0, emptyList()),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MovedPrivate {
        val device = BulkActions.targets(BulkAction.MAKE_PRIVATE, ids).ids
        val conversions = ContactConversions(c)
        var moved = already.moved
        val failed = already.failed.toMutableList()
        var afterSync = already.removedAfterSync
        var messenger = already.messengerCopies
        // Progress counts the whole batch, also across an unlock.
        val base = already.moved + already.failed.size
        val total = base + device.size
        for ((i, id) in device.withIndex()) {
            onProgress(base + i, total)
            val d = withContext(Dispatchers.IO) { suspendRunCatching { c.contacts.details(id) }.getOrNull() }
            if (d == null) {
                failed += id to names[id].orEmpty()
                continue
            }
            try {
                val r = conversions.makePrivate(id, d)
                moved++
                afterSync = afterSync || r.removedAfterSync
                messenger = messenger || r.messengerCopies
            } catch (e: VaultCrypto.LockedException) {
                throw BulkLocked(MovedPrivate(moved, failed.toList(), afterSync, messenger), device.drop(i), e)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed += id to (names[id] ?: d.displayName)
            }
        }
        onProgress(total, total)
        c.contacts.refresh()
        return MovedPrivate(moved, failed, afterSync, messenger)
    }

    /** The vault must be unlocked before the batch can go on with [remaining]; [soFar] is what happened until then. */
    class BulkLocked(val soFar: MovedPrivate, val remaining: List<Long>, cause: VaultCrypto.LockedException) : Exception(cause.message, cause)

    /** What a bulk "Make visible" did: how many [made] it, and the cloud accounts Android 16 put some of them in. */
    data class MadeVisible(val made: Int, val redirectedTo: Set<AccountRef> = emptySet())

    /**
     * Makes the private ones among [ids] visible to other apps (lossless, like the page's "Make visible"), into
     * [account] when one wasn't kept. Throws [VaultCrypto.LockedException] before changing anything when the vault must
     * be unlocked first.
     */
    suspend fun makeVisible(ids: Collection<Long>, account: AccountRef): MadeVisible {
        val vaultIds = BulkActions.targets(BulkAction.MAKE_VISIBLE, ids).ids.map { -it }
        if (vaultIds.isEmpty()) return MadeVisible(0)
        // Read them all first: a locked vault stops the whole batch rather than half of it.
        val details = vaultIds.associateWith { c.vault.details(it) }
        val conversions = ContactConversions(c)
        val done = details.mapNotNull { (v, d) ->
            d?.let { suspendRunCatching { conversions.makeVisible(v, it, account) }.getOrNull() as? ContactConversions.MadeVisible.Done }
        }
        return MadeVisible(done.size, done.mapNotNull { it.redirectedTo }.toSet())
    }
}
