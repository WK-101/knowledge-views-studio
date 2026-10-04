package app.parley.ui.home

import app.parley.common.people.BulkAction
import app.parley.common.people.BulkActions
import app.parley.common.people.BulkEdit
import app.parley.common.people.BulkEdits
import app.parley.common.people.TemporaryChoice
import app.parley.common.record.ContactRecord
import app.parley.common.record.Messengers
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

    /**
     * What a move to another account did: how many moved, were there already, couldn't be moved (by name), had no copy
     * Parley may move (only on the SIM or in a read-only account, by name), private ones left out, how many kept a SIM
     * or read-only copy where it was, and what [undoMove] needs to put them back.
     */
    data class MovedAccount(
        val moved: Int,
        val unchanged: Int,
        val failed: List<String>,
        val skippedPrivate: Int,
        val redirectedTo: AccountRef? = null,
        val notMovable: List<String> = emptyList(),
        val keptReadOnly: Int = 0,
        val undo: List<MoveUndo> = emptyList(),
    )

    /**
     * One moved contact, for Undo: the copies that moved as they were ([original]), the copies made in the target
     * ([newRaws]), the copies that stayed ([staying]) and the contact's key after the move ([newKey]).
     */
    class MoveUndo internal constructor(
        internal val original: ContactRecord,
        internal val newRaws: List<Long>,
        internal val staying: List<Long>,
        internal val newKey: String?,
    )

    /**
     * Moves the device contacts among [ids] into [account]. Only copies Parley may write and that aren't there already
     * move: each is copied there (every field, photo, labels by title, star and ringtone, as a restore does), kept
     * linked with the copies that stay (SIM, read-only, messenger copies, or one in [account] already), and only then
     * removed where it was. Nothing is removed unless History & undo kept the contact as it was ("Moved"). Parley's own
     * notes, reminders and a temporary date follow it to the new copy. A private contact stays in Parley.
     */
    suspend fun moveToAccount(ids: Collection<Long>, account: AccountRef, names: Map<Long, String>): MovedAccount = withContext(Dispatchers.IO) {
        val plan = BulkEdits.plan(BulkEdit.MOVE_ACCOUNT, ids)
        var moved = 0
        var unchanged = 0
        var kept = 0
        val failed = ArrayList<String>()
        val notMovable = ArrayList<String>()
        val undo = ArrayList<MoveUndo>()
        var redirected: AccountRef? = null
        for (id in plan.ids) {
            when (val r = moveOne(id, account)) {
                MoveOutcome.AlreadyThere -> unchanged++
                is MoveOutcome.Failed -> failed += names[id] ?: r.name
                is MoveOutcome.NotMovable -> notMovable += names[id] ?: r.name
                is MoveOutcome.Moved -> {
                    moved++
                    if (r.keptReadOnly) kept++
                    undo += r.undo
                    redirected = redirected ?: r.redirectedTo
                }
            }
        }
        c.contacts.refresh()
        MovedAccount(moved, unchanged, failed, plan.skippedPrivate, redirected, notMovable, kept, undo)
    }

    private sealed interface MoveOutcome {
        data object AlreadyThere : MoveOutcome
        data class Failed(val name: String) : MoveOutcome
        data class NotMovable(val name: String) : MoveOutcome
        data class Moved(val redirectedTo: AccountRef?, val keptReadOnly: Boolean, val undo: MoveUndo) : MoveOutcome
    }

    private fun inTarget(a: AccountRef, target: AccountRef) = a == target || (a.isLocal && target.isLocal)

    /** One contact of [moveToAccount]: journaled as "Moved", its movable copies copied into [account], then removed. */
    private suspend fun moveOne(id: Long, account: AccountRef): MoveOutcome {
        val record = suspendRunCatching { c.records.read(id, fullPhoto = true) }.getOrNull()
        if (record == null || record.raws.isEmpty()) return MoveOutcome.Failed("")
        val copies = record.raws.mapNotNull { r ->
            val a = AccountRef(r.accountType, r.accountName)
            r.rawId?.let { BulkEdits.MoveCopy(it, c.contacts.isWritableAccount(a), inTarget(a, account), Messengers.isMessengerAccount(r.accountType)) }
        }
        return when (val split = BulkEdits.moveSplit(copies)) {
            BulkEdits.MoveSplit.NoWritableCopy -> MoveOutcome.NotMovable(record.displayName)
            BulkEdits.MoveSplit.AlreadyThere -> MoveOutcome.AlreadyThere
            is BulkEdits.MoveSplit.Move -> try {
                moveCopies(id, record, split, account) ?: MoveOutcome.Failed(record.displayName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MoveOutcome.Failed(record.displayName)
            }
        }
    }

    /**
     * The copies [split] moves, copied into [account] and only then removed (once History & undo holds the contact as
     * it was); null when nothing was removed because that couldn't be done safely.
     */
    private suspend fun moveCopies(id: Long, record: ContactRecord, split: BulkEdits.MoveSplit.Move, account: AccountRef): MoveOutcome.Moved? {
        val moving = record.copy(raws = record.raws.filter { it.rawId in split.moving })
        val oldKey = c.contacts.lookupKeyOf(id)
        if (c.contacts.recordChange(listOf(id), "MOVE").isEmpty()) return null
        val r = c.records.insertAll(listOf(moving), account, announceRedirect = false).single()
        val newId = r.contactId
        if (newId == null || r.rawIds.isEmpty()) return null
        val placed = suspendRunCatching {
            if (split.staying.isNotEmpty()) c.contacts.keepTogether(r.rawIds + split.staying)
            c.contacts.deleteRawsUnjournaled(split.moving)
        }
        if (placed.isFailure) {
            // The new copy is taken back: the originals are all still there.
            suspendRunCatching { c.contacts.discardInserted(r.rawIds) }
            return null
        }
        // Android may join the new copy with someone's other copies: follow it to where it is now.
        val now = c.contacts.contactsOfRaws(r.rawIds).values.firstOrNull() ?: newId
        val newKey = c.contacts.lookupKeyOf(now)
        if (oldKey != null && newKey != null && oldKey != newKey) suspendRunCatching { c.contactKeys.rekey(oldKey, newKey, now) }
        // A temporary contact stays temporary: its entry now names the new copies, which are what expire.
        (newKey ?: oldKey)?.let { k -> suspendRunCatching { c.temporaries.replaceRaws(k, split.moving, r.rawIds, now) } }
        return MoveOutcome.Moved(r.redirectedTo, split.keptReadOnly > 0, MoveUndo(moving, r.rawIds, split.staying, newKey))
    }

    /**
     * Undo of [moveToAccount]: each contact's moved copies come back into the accounts they were in, linked with the
     * copies that stayed, and the copies the move made go. Parley's own data and a temporary date follow back.
     */
    suspend fun undoMove(moves: List<MoveUndo>) = withContext(Dispatchers.IO) {
        for (m in moves) {
            try {
                val back = c.records.insertAll(listOf(m.original), target = null, announceRedirect = false).single()
                val backId = back.contactId
                if (backId == null || back.rawIds.isEmpty()) continue
                val staying = c.contacts.contactsOfRaws(m.staying).keys
                if (staying.isNotEmpty()) c.contacts.keepTogether(back.rawIds + staying)
                c.contacts.deleteRawsUnjournaled(m.newRaws)
                val now = c.contacts.contactsOfRaws(back.rawIds).values.firstOrNull() ?: backId
                val key = c.contacts.lookupKeyOf(now)
                if (m.newKey != null && key != null && m.newKey != key) suspendRunCatching { c.contactKeys.rekey(m.newKey, key, now) }
                (key ?: m.newKey)?.let { k -> suspendRunCatching { c.temporaries.replaceRaws(k, m.newRaws, back.rawIds, now) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // That one stays moved; History & undo still has it as it was.
            }
        }
        c.contacts.refresh()
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
