package app.parley.common.sync

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
        (deletions > 3 && deletions * 4 > entries) || recentlyEditedDeletions > 0

    /**
     * What identifies a file's version without reading it: last-modified time and size. Null when the folder's
     * provider doesn't report both (the file is then read and hashed every run).
     */
    fun stamp(lastModified: Long?, size: Long?): String? {
        val t = lastModified?.takeIf { it > 0L } ?: return null
        val n = size?.takeIf { it >= 0L } ?: return null
        return "$t:$n"
    }
}
