package app.parley.data.people

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import app.parley.common.people.Batches
import app.parley.data.AccountRef
import app.parley.data.ContactsRepository
import app.parley.data.GroupInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A label as the user sees it: one title, possibly backed by a group in several accounts. */
data class Label(val title: String, val groups: List<GroupInfo>) {
    val accounts: List<AccountRef> get() = groups.map { it.account }.distinct()
}

/**
 * Label management by title: list, create, rename, delete and merge. Labels with the same title in different
 * accounts are handled together, the way the Contacts tab shows them. Contacts are never deleted here.
 */
class LabelsRepository(
    context: android.content.Context,
    private val contacts: ContactsRepository,
    /** Rules, off hours, limits and ringtones that name labels follow renames, merges and deletes. */
    private val refs: LabelReferences? = null,
) {
    private val cr = context.contentResolver

    /**
     * Only user labels may be renamed, merged, deleted or emptied: system groups ("My Contacts"), read-only groups
     * and the favourites group ("Starred in Android", whose members are the starred contacts) are refused (F11).
     */
    private fun safe(groups: List<GroupInfo>): List<GroupInfo> {
        val ok = contacts.userGroupIds(groups.map { it.id })
        return groups.filter { it.id in ok }
    }

    suspend fun labels(): List<Label> = withContext(Dispatchers.IO) {
        contacts.groups().groupBy { it.title.trim() }.map { (t, g) -> Label(t, g) }.sortedBy { it.title.lowercase() }
    }

    suspend fun label(title: String): Label? = labels().firstOrNull { it.title == title }

    /** Contact ids with this label, in any account. */
    suspend fun members(title: String): Set<Long> = withContext(Dispatchers.IO) {
        label(title)?.groups?.flatMap { contacts.contactIdsInGroup(it.id) }?.toSet().orEmpty()
    }

    suspend fun create(title: String, account: AccountRef): Long? = contacts.createGroup(title.trim(), account)

    suspend fun rename(title: String, newTitle: String): Int = withContext(Dispatchers.IO) {
        val t = newTitle.trim()
        if (t.isEmpty() || t == title) return@withContext 0
        val existing = label(t)
        val source = label(title) ?: return@withContext 0
        if (existing != null) return@withContext merge(setOf(title), t)
        val ops = safe(source.groups).map {
            ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Groups.CONTENT_URI, it.id)).withValue(Groups.TITLE, t).build()
        }
        val n = cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops)).size
        refs?.renamed(mapOf(title to t))
        n
    }

    /**
     * Deletes the label everywhere. Its contacts stay; only the membership goes. Rules, limits and the ringtone
     * for the label go too. Returns a sentence to show the user when a setting had to change, else null.
     */
    suspend fun delete(title: String): String? = withContext(Dispatchers.IO) {
        val l = label(title) ?: return@withContext null
        val ops = safe(l.groups).map { ContentProviderOperation.newDelete(ContentUris.withAppendedId(Groups.CONTENT_URI, it.id)).build() }
        cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
        refs?.deleted(setOf(l.title))
    }

    /**
     * Merges [sources] into [target]: in every account, the members of a source group join the target group of
     * that account (created when missing), then the source group is deleted. Returns how many memberships were
     * added.
     */
    suspend fun merge(sources: Set<String>, target: String): Int = withContext(Dispatchers.IO) {
        val all = labels()
        val targetLabel = all.firstOrNull { it.title == target }
        var added = 0
        val deletes = ArrayList<ContentProviderOperation>()
        for (src in all.filter { it.title in sources && it.title != target }) {
            for (g in safe(src.groups)) {
                val targetGroupId = targetLabel?.groups?.firstOrNull { it.account == g.account }?.id ?: contacts.createGroup(target, g.account) ?: continue
                val rawMembers = rawMembers(g.id)
                val already = rawMembers(targetGroupId)
                val ops = (rawMembers - already).map { raw ->
                    ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValue(Data.RAW_CONTACT_ID, raw)
                        .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                        .withValue(GroupMembership.GROUP_ROW_ID, targetGroupId)
                        .build()
                }
                Batches.chunks(ops).forEach { cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
                added += ops.size
                deletes += ContentProviderOperation.newDelete(ContentUris.withAppendedId(Groups.CONTENT_URI, g.id)).build()
            }
        }
        Batches.chunks(deletes).forEach { cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
        refs?.renamed(sources.filter { it != target }.associateWith { target })
        added
    }

    /** Removes contacts from a label (all accounts). */
    suspend fun removeMembers(title: String, contactIds: Collection<Long>) = withContext(Dispatchers.IO) {
        val l = label(title) ?: return@withContext
        val ids = contactIds.joinToString(",")
        for (g in safe(l.groups)) {
            cr.delete(
                Data.CONTENT_URI,
                "${Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=? AND ${Data.CONTACT_ID} IN ($ids)",
                arrayOf(GroupMembership.CONTENT_ITEM_TYPE, g.id.toString()),
            )
        }
    }

    private fun rawMembers(groupId: Long): Set<Long> {
        val out = HashSet<Long>()
        try {
            cr.query(
                Data.CONTENT_URI, arrayOf(Data.RAW_CONTACT_ID),
                "${Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=?",
                arrayOf(GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()), null,
            )?.use { c -> while (c.moveToNext()) out += c.getLong(0) }
        } catch (_: Exception) {
        }
        return out
    }
}
