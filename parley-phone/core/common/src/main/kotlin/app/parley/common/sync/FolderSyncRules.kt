package app.parley.common.sync

import app.parley.common.Duplicates
import app.parley.common.PhoneIdentity
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import java.util.Locale

/**
 * The decisions of the folder sync's three-way merge, one entry (a contact and its file) at a time, and the guard that
 * pauses a run which would delete a lot, or delete something recently edited, until the user confirms.
 */
object FolderSyncRules {
    enum class Action {
        /** Neither side changed. */
        NONE,

        /** Only the contact changed: write the file. */
        WRITE_FILE,

        /** The contact was deleted here and the file is unchanged: delete the file. */
        DELETE_FILE,

        /** Only the file changed: apply it to the contact. */
        APPLY_FILE,

        /** The file was deleted elsewhere and the contact is unchanged: delete the contact (journaled). */
        DELETE_LOCAL,

        /** Both changed: keep this phone's version and set the file's aside as a conflict copy. */
        CONFLICT,

        /** Deleted on one side and edited on the other: keep the surviving version, forget the pairing. */
        FORGET,
    }

    fun action(fileChanged: Boolean, fileGone: Boolean, localChanged: Boolean, localGone: Boolean): Action {
        val f = fileChanged || fileGone
        val l = localChanged || localGone
        return when {
            !f && !l -> Action.NONE
            !f -> if (localGone) Action.DELETE_FILE else Action.WRITE_FILE
            !l -> if (fileGone) Action.DELETE_LOCAL else Action.APPLY_FILE
            fileGone || localGone -> Action.FORGET
            else -> Action.CONFLICT
        }
    }

    /** Contacts edited this recently are only deleted by a sync after the user confirms. */
    const val RECENT_EDIT_MS: Long = 3L * 24 * 60 * 60 * 1000

    /**
     * Whether a run must pause for confirmation: more than 3 deletions making up over a quarter of the synced
     * entries (a folder emptied or swapped, a sync app half way), or any deletion of a contact edited in the last
     * [RECENT_EDIT_MS].
     */
    fun mustConfirm(deletions: Int, entries: Int, recentlyEditedDeletions: Int): Boolean =
        isMassDeletion(deletions, entries) || recentlyEditedDeletions > 0

    /** More than 3 deletions making up over a quarter of the synced entries: the whole run waits for the user. */
    fun isMassDeletion(deletions: Int, entries: Int): Boolean = deletions > 3 && deletions * 4 > entries

    /**
     * Whether a contact counts as recently edited on this phone: changed in the last [RECENT_EDIT_MS] ([updatedAt]),
     * and later than Parley's own last write to it ([ownWriteAt], null when the sync never wrote it). A contact that
     * a sync just imported or updated was not edited by the user, so it doesn't hold up a deletion.
     */
    fun recentlyEdited(updatedAt: Long, ownWriteAt: Long?, now: Long): Boolean =
        updatedAt > 0 && now - updatedAt < RECENT_EDIT_MS && (ownWriteAt == null || updatedAt > ownWriteAt)

    /**
     * The version to seal a file with: above every version seen for it ([lastSeen]), and at least the clock ([now]),
     * so a file that is deleted and later written again under the same name still outranks the deleted one. Saturates
     * at [Long.MAX_VALUE]: a file claiming the largest version must not make every later one wrap negative.
     */
    fun nextVersion(lastSeen: Long, now: Long): Long = maxOf(if (lastSeen == Long.MAX_VALUE) lastSeen else lastSeen + 1, now)

    /** A file older than the last version this phone saw of it: an old copy put back, ignored. */
    fun isRollback(version: Long, lastSeen: Long): Boolean = version < lastSeen

    /** A file no newer than the version it had when it was deleted ([deletedAt]): a deleted contact put back. */
    fun isResurrection(version: Long, deletedAt: Long?): Boolean = deletedAt != null && version <= deletedAt

    /**
     * What identifies a file's version without reading it: last-modified time and size. Null when the folder's
     * provider doesn't report both (the file is then read and hashed every run).
     */
    fun stamp(lastModified: Long?, size: Long?): String? {
        val t = lastModified?.takeIf { it > 0L } ?: return null
        val n = size?.takeIf { it >= 0L } ?: return null
        return "$t:$n"
    }

    /** A contact's phones and emails as match keys, for pairing a new file with a contact that has none yet. */
    fun matchKeys(r: ContactRecord): Set<String> = r.raws.flatMap { it.rows }.mapNotNull { row ->
        when (row.mimeType) {
            Mime.PHONE -> row["data1"]?.let { PhoneIdentity.portableKey(it) }?.let { "p:$it" }
            Mime.EMAIL -> row["data1"]?.let { Duplicates.emailKey(it) }?.let { "e:$it" }
            else -> null
        }
    }.toSet()

    /** The name a contact without phones and emails is paired by, when exactly one such contact has it. */
    fun nameKey(r: ContactRecord): String? = r.displayName.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ").takeIf { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")
}
