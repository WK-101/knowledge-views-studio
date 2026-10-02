package app.parley.common.sync.shared

import app.parley.common.sync.FolderSyncRules

/**
 * The decisions of a shared label's sync, one contact at a time (docs/SHARED_LABELS.md, "Syncing"). A file that
 * vanished is never a deletion: only a signed tombstone deletes, and an edit always wins over a deletion.
 */
object SharedLabelRules {
    /** This phone's copy since the last sync. */
    enum class Local { UNCHANGED, CHANGED, GONE }

    /** The folder's file since the last sync. */
    enum class Remote {
        UNCHANGED,
        CHANGED,

        /** A newer signed deletion. */
        TOMBSTONE,

        /** No file, or an older copy than this phone saw (put back): written again from this phone. */
        MISSING,

        /** Can't be opened, isn't signed by a member: left alone this run. */
        UNREADABLE,
    }

    enum class Action {
        NONE,

        /** Write this phone's version to the folder. */
        PUBLISH,

        /** Apply the folder's version here. */
        APPLY,

        /** Both changed: merge field by field; conflicts wait for the user. */
        MERGE,

        /** Gone here: write a signed deletion. */
        PUBLISH_TOMBSTONE,

        /** Deleted elsewhere and unchanged here: delete here (or take out of the label), journaled. */
        DELETE_LOCAL,

        /** Gone here but edited elsewhere: the edit wins, it comes back. */
        IMPORT_AGAIN,

        /** Gone on both sides: nothing left to do. */
        FORGET,
    }

    fun decide(local: Local, remote: Remote): Action = when (remote) {
        Remote.UNREADABLE -> Action.NONE
        Remote.UNCHANGED -> pick(local, Action.NONE, Action.PUBLISH, Action.PUBLISH_TOMBSTONE)
        Remote.CHANGED -> pick(local, Action.APPLY, Action.MERGE, Action.IMPORT_AGAIN)
        // An edit here wins over the deletion: it is written again with a higher version.
        Remote.TOMBSTONE -> pick(local, Action.DELETE_LOCAL, Action.PUBLISH, Action.FORGET)
        Remote.MISSING -> pick(local, Action.PUBLISH, Action.PUBLISH, Action.PUBLISH_TOMBSTONE)
    }

    private fun pick(local: Local, unchanged: Action, changed: Action, gone: Action) = when (local) {
        Local.UNCHANGED -> unchanged
        Local.CHANGED -> changed
        Local.GONE -> gone
    }

    /**
     * What a contact file is compared with the last synced state: [lastVersion] is the version this phone synced
     * (null: never), [seenVersion] the highest it ever saw of this sid, deletions included.
     */
    fun remote(fileVersion: Long?, deleted: Boolean, readable: Boolean, lastVersion: Long?, seenVersion: Long?): Remote = when {
        fileVersion == null -> Remote.MISSING
        !readable -> Remote.UNREADABLE
        fileVersion < maxOf(lastVersion ?: 0L, seenVersion ?: 0L) -> Remote.MISSING
        lastVersion != null && fileVersion == lastVersion -> Remote.UNCHANGED
        deleted -> Remote.TOMBSTONE
        else -> Remote.CHANGED
    }

    /** A file for a sid this phone doesn't sync: new unless it is a deletion, or no newer than one this phone saw. */
    fun isNewContact(fileVersion: Long, deleted: Boolean, seenVersion: Long?): Boolean =
        !deleted && (seenVersion == null || fileVersion > seenVersion)

    /**
     * How long a file this phone syncs may stay unreadable before this phone writes its own copy over it: long enough
     * for a file still being copied in to arrive whole ([CORRUPT_GRACE_MS]), and, for a file signed by a key this
     * phone doesn't count as a member yet, for that member's journal to arrive too ([STRANGER_GRACE_MS]).
     */
    const val CORRUPT_GRACE_MS: Long = 60L * 60 * 1000
    const val STRANGER_GRACE_MS: Long = 24L * 60 * 60 * 1000

    /**
     * A file this phone syncs that it can't use ([Remote.UNREADABLE]): damaged, sealed with another key, signed by
     * someone who isn't a member. Left alone for a grace period since [since] (when this phone first found it so), then
     * treated as missing, so this phone's copy is written again: anyone who can write to the folder could otherwise
     * freeze a contact for good. [stranger]: well formed and signed, by a key that isn't a member.
     */
    fun unreadable(since: Long, now: Long, stranger: Boolean): Remote =
        if (now - since >= if (stranger) STRANGER_GRACE_MS else CORRUPT_GRACE_MS) Remote.MISSING else Remote.UNREADABLE

    /** Versions are times (ms) or just above one: one further ahead than this is not believed (it would freeze a contact). */
    const val MAX_AHEAD_MS: Long = 366L * 24 * 60 * 60 * 1000

    fun plausibleVersion(version: Long, now: Long): Boolean = version <= now + MAX_AHEAD_MS

    /** The version to write: above everything seen for the sid, and at least the clock. */
    fun nextVersion(lastSeen: Long, now: Long): Long = FolderSyncRules.nextVersion(lastSeen, now)

    /** Deletions that make a run wait for the user, as the folder sync's (more than 3, and over a quarter). */
    fun mustConfirm(deletions: Int, entries: Int): Boolean = FolderSyncRules.isMassDeletion(deletions, entries)
}
