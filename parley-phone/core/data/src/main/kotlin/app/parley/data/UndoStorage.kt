package app.parley.data

import android.database.SQLException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.parley.common.backup.SnapshotKeep
import app.parley.data.backup.TimeMachine
import app.parley.data.db.AppDatabase
import app.parley.data.db.MetaDao
import app.parley.data.history.CallHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The three undo stores behind History & undo, for its "Manage storage" sheet: the contact journal (sealed payloads
 * behind [meta], the [SealedMetaDao][app.parley.data.security.SealedMetaDao]), the deleted-call trash of [history]
 * and the daily snapshots of [timeMachine]. Clearing one means those things can no longer be undone; the contacts and
 * the call log themselves are never touched.
 */
class UndoStorage(
    private val db: AppDatabase,
    private val meta: MetaDao,
    private val history: CallHistory,
    private val timeMachine: TimeMachine,
) {
    data class Usage(
        val contactChanges: Int,
        val contactBytes: Long,
        val deletedCalls: Int,
        val callBytes: Long,
        /** When each daily snapshot was taken, oldest first. */
        val snapshotTimes: List<Long>,
        val snapshotBytes: Long,
    ) {
        val snapshots: Int get() = snapshotTimes.size
    }

    suspend fun usage(): Usage = withContext(Dispatchers.IO) {
        val (calls, callBytes) = history.trashUsage()
        Usage(
            contactChanges = meta.journalCount(),
            contactBytes = meta.journalBytes(),
            deletedCalls = calls,
            callBytes = callBytes,
            snapshotTimes = timeMachine.snapshots().map { it.timestamp },
            snapshotBytes = timeMachine.storageBytes(),
        )
    }

    /** Forgets every undo copy of a contact change; returns how many. */
    suspend fun clearContactChanges(): Int = withContext(Dispatchers.IO + NonCancellable) {
        meta.clearJournal().also { if (it > 0) compactDatabase(db.openHelper) }
    }

    /** Forgets one contact change (one row of the Contacts tab). */
    suspend fun forgetContactChange(entryId: Long): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        meta.deleteJournalEntry(entryId) > 0
    }

    /** Forgets the undo copies of deleted calls; the call log is untouched. Returns how many calls. */
    suspend fun clearDeletedCalls(): Int = history.forgetDeleted(null)

    /** Forgets one delete of calls (one row of the Calls tab). */
    suspend fun forgetDeletedCalls(batchId: Long): Int = history.forgetDeleted(batchId)

    /** Deletes the daily snapshots [keep] doesn't keep; returns how many went. */
    suspend fun clearSnapshots(keep: SnapshotKeep): Int = timeMachine.clear(keep)
}

/**
 * Rewrites a database without its free pages (best effort), so rows just cleared don't linger in the file. The undo
 * payloads were sealed, but names in the journal's list columns are not.
 */
internal fun compactDatabase(helper: SupportSQLiteOpenHelper) {
    try {
        helper.writableDatabase.run {
            execSQL("VACUUM")
            query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        }
    } catch (e: SQLException) {
        Log.w("UndoStorage", "Compacting ${helper.databaseName} failed", e)
    }
}
