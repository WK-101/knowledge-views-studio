package app.parley.data.people

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import app.parley.common.people.Batches
import app.parley.common.people.ContactRef
import app.parley.data.AccountRef
import app.parley.data.ContactsRepository
import app.parley.data.GroupInfo
import app.parley.data.vault.PrivateLabelStore
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
    context: Context,
    private val contacts: ContactsRepository,
    /** Rules, off hours, limits and ringtones that name labels follow renames, merges and deletes. */
    private val refs: LabelReferences? = null,
    /** Private contacts' membership (kept by Parley, never in the address book): follows renames, merges and deletes too. */
    private val privateLabels: () -> PrivateLabelStore? = { null },
) {
    private val cr = context.contentResolver

    /**
     * Only user labels may be renamed, merged, deleted or emptied: system groups ("My Contacts"), read-only groups
     * and the favourites group ("Starred in Android", whose members are the starred contacts) are refused.
     */
    private fun safe(groups: List<GroupInfo>): List<GroupInfo> {
        val ok = contacts.userGroupIds(groups.map { it.id })
        return groups.filter { it.id in ok }
    }

    suspend fun labels(): List<Label> = withContext(Dispatchers.IO) {
        contacts.groups().groupBy { it.title.trim() }.map { (t, g) -> Label(t, g) }.sortedBy { it.title.lowercase() }
    }

    suspend fun label(title: String): Label? = labels().firstOrNull { it.title == title }

    /** Contact ids with this label, in any account; private members by their list id ([ContactRef.navId]). */
    suspend fun members(title: String): Set<Long> = withContext(Dispatchers.IO) {
        val device = label(title)?.groups?.flatMap { contacts.contactIdsInGroup(it.id) }?.toSet().orEmpty()
        val private = runCatching { privateLabels()?.membersOf(title) }.getOrNull().orEmpty().map { ContactRef.Private(it).navId }
        device + private
    }

    /**
     * Adds contacts (list ids, device and private mixed) to [group]'s label: device contacts get a group row in the
     * label's account, private ones a membership Parley keeps. Returns how many device contacts couldn't be added (no
     * copy in that account).
     */
    suspend fun addMembers(ids: Collection<Long>, group: GroupInfo): Int {
        val (private, device) = ids.partition { ContactRef.ofNavId(it) is ContactRef.Private }
        if (private.isNotEmpty()) privateLabels()?.add(private.map { -it }, group)
        return if (device.isEmpty()) 0 else contacts.addToGroup(device, group)
    }

    suspend fun create(title: String, account: AccountRef): Long? = contacts.createGroup(title.trim(), account)

    suspend fun rename(title: String, newTitle: String): Int = withContext(Dispatchers.IO) {
        val t = newTitle.trim()
        if (t.isEmpty() || t == title) return@withContext 0
        val existing = label(t)
        val source = label(title) ?: return@withContext 0
        if (existing != null) return@withContext merge(setOf(title), t)
        runCatching { privateLabels()?.renamed(mapOf(title to t)) }
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
        runCatching { privateLabels()?.deleted(l.title) }
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
        runCatching { privateLabels()?.renamed(sources.filter { it != target }.associateWith { target }) }
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
        val (private, device) = contactIds.partition { ContactRef.ofNavId(it) is ContactRef.Private }
        if (private.isNotEmpty()) privateLabels()?.remove(title, private.map { -it })
        val l = label(title) ?: return@withContext
        if (device.isEmpty()) return@withContext
        // By raw contact: a group row belongs to one copy, and every copy of the contact leaves the label.
        val raws = device.flatMap { contacts.rawIds(it) }
        if (raws.isEmpty()) return@withContext
        for (g in safe(l.groups)) {
            raws.chunked(IN_CHUNK).forEach { chunk ->
                // The group id as a number, like the raw ids: it matches however the provider typed the column.
                cr.delete(
                    Data.CONTENT_URI,
                    "${Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=${g.id} AND ${Data.RAW_CONTACT_ID} IN (${chunk.joinToString(",")})",
                    arrayOf(GroupMembership.CONTENT_ITEM_TYPE),
                )
            }
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

/** Ids per `IN (…)` selection. */
private const val IN_CHUNK = 500
