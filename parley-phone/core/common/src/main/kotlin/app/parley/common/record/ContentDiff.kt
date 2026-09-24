package app.parley.common.record

import app.parley.common.vcard.VCardMapper

/**
 * Plans an in-place update of a contact's writable data rows to match a remote version (folder sync), touching as
 * little as possible:
 *
 * - Both sides are compared in [VCardMapper.canonicalRow] form, so a row the remote copy didn't really change keeps
 *   its id, version and sync state (the provider's normalised number, name styles and label-less types no longer make
 *   every row look different).
 * - Group memberships are compared only for **user labels** ([userGroupIds]: groups with a title and no SYSTEM_ID,
 *   AUTO_ADD, GROUP_IS_READ_ONLY or FAVORITES). A vCard carries labels by title only, so memberships of "My
 *   Contacts", favourites, Family/Friends/Coworkers or any group we can't name are never removed.
 * - Read-only rows ([Existing.readOnly], Data.IS_READ_ONLY) are never deleted.
 * - Rows that hold nothing a vCard keeps (blank or unknown junk rows) are left alone rather than deleted.
 */
object ContentDiff {
    /** A data row currently on one of the contact's writable raw contacts. */
    data class Existing(val id: Long, val row: DataRow, val readOnly: Boolean = false)

    data class Plan(val inserts: List<DataRow>, val deletes: List<Long>, val unchanged: Int) {
        val isEmpty: Boolean get() = inserts.isEmpty() && deletes.isEmpty()
    }

    /**
     * [current]: rows of the writable raws (photos and messenger rows are ignored whatever is passed).
     * [desired]: rows of the remote record; group rows must already carry the resolved group row id in DATA1.
     * [userGroupIds]: group row ids that are user labels (see the class documentation). Group rows on either side
     * whose id is not in it are ignored.
     */
    fun plan(current: List<Existing>, desired: List<DataRow>, userGroupIds: Set<Long>): Plan {
        val have = HashMap<String, ArrayDeque<Existing>>()
        for (e in current) {
            val key = keyOf(e.row, userGroupIds) ?: continue
            have.getOrPut(key) { ArrayDeque() }.add(e)
        }
        val inserts = ArrayList<DataRow>()
        val seen = HashSet<String>()
        var name = false
        var unchanged = 0
        for (row in desired) {
            if (row.mimeType == Mime.NAME) {
                if (name) continue
                name = true
            }
            val key = keyOf(row, userGroupIds) ?: continue
            if (!seen.add(key)) continue
            val match = have[key]
            if (match != null && match.isNotEmpty()) {
                // Prefer consuming a writable row, so a read-only twin is never the one "kept" while its copy goes.
                val i = match.indexOfFirst { !it.readOnly }.takeIf { it >= 0 } ?: 0
                match.removeAt(i)
                unchanged++
                continue
            }
            inserts += row
        }
        val deletes = have.values.flatten().filter { !it.readOnly }.map { it.id }
        return Plan(inserts, deletes, unchanged)
    }

    /** Comparison key, or null for rows this diff must never touch. */
    fun keyOf(row: DataRow, userGroupIds: Set<Long>): String? {
        if (row.mimeType == Mime.PHOTO || Messengers.isMessengerMime(row.mimeType)) return null
        if (row.mimeType == Mime.GROUP) {
            val id = row[Col.D1]?.trim()?.toLongOrNull() ?: return null
            return if (id in userGroupIds) "g|$id" else null
        }
        val n = VCardMapper.canonicalRow(row) ?: return null
        return VCardMapper.rowIdentity(n)
    }

    /**
     * Whether a group is one of the user's labels (and so may be diffed, renamed, merged or deleted), from its
     * Groups columns.
     */
    fun isUserGroup(title: String?, systemId: String?, autoAdd: Boolean, readOnly: Boolean, favorites: Boolean): Boolean =
        !title.isNullOrBlank() && systemId == null && !autoAdd && !readOnly && !favorites
}
