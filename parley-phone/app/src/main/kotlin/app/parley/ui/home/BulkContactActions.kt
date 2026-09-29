package app.parley.ui.home

import app.parley.common.people.BulkAction
import app.parley.common.people.BulkActions
import app.parley.common.people.TemporaryChoice
import app.parley.common.suspendRunCatching
import app.parley.data.AccountRef
import app.parley.data.DataContainer
import app.parley.data.GroupInfo
import app.parley.data.vault.VaultCrypto
import app.parley.ui.contact.ContactConversions
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

    /**
     * "Delete automatically after [days]" for all of them (null: keep them), with their call history, like the page's
     * choice. A private contact's date is its vault entry's own.
     */
    suspend fun setExpiry(ids: Collection<Long>, days: Int?) = withContext(Dispatchers.IO) {
        val at = days?.let { System.currentTimeMillis() + it * TemporaryChoice.DAY_MS }
        for (id in ids) {
            if (BulkActions.isPrivate(id)) {
                val v = -id
                // The call-history choice is sealed with the details: set it too when they can be opened now.
                val sealed = if (at != null) suspendRunCatching { c.vault.details(v) }.getOrNull() else null
                if (sealed != null) c.vault.save(v, sealed, expiresAt = at, purgeHistory = true) else c.vault.setExpiry(v, at)
            } else if (days == null) {
                c.contacts.lookupKeyOf(id)?.let { c.temporaries.clear(it) }
            } else {
                c.temporaries.mark(id, days, purgeHistory = true)
            }
        }
        c.contacts.refresh()
    }

    /**
     * Makes the private ones among [ids] visible to other apps (lossless, like the page's "Make visible"), into
     * [account] when one wasn't kept. Throws [VaultCrypto.LockedException] before changing anything when the vault must
     * be unlocked first. Returns how many were made visible.
     */
    suspend fun makeVisible(ids: Collection<Long>, account: AccountRef): Int {
        val vaultIds = BulkActions.targets(BulkAction.MAKE_VISIBLE, ids).ids.map { -it }
        if (vaultIds.isEmpty()) return 0
        // Read them all first: a locked vault stops the whole batch rather than half of it.
        val details = vaultIds.associateWith { c.vault.details(it) }
        val conversions = ContactConversions(c)
        return details.count { (v, d) -> d != null && suspendRunCatching { conversions.makeVisible(v, d, account) }.getOrNull() != null }
    }
}
