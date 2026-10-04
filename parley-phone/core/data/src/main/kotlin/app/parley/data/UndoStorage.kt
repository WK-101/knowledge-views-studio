package app.parley.data

import android.database.SQLException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.parley.common.backup.SnapshotKeep
import app.parley.common.storage.VacuumPolicy
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
            contactBytes = meta.journalBytes() + meta.journalPhotoBytes(),
            deletedCalls = calls,
            callBytes = callBytes,
            snapshotTimes = timeMachine.snapshotTimes(),
            snapshotBytes = timeMachine.storageBytes(),
        )
    }

    /** Forgets every undo copy of a contact change; returns how many. */
    suspend fun clearContactChanges(): Int = withContext(Dispatchers.IO + NonCancellable) {
        meta.clearJournal().also {
            dropUnusedJournalPhotos(meta)
            if (it > 0) compactDatabase(db.openHelper)
        }
    }

    /** Forgets one contact change (one row of the Contacts tab). */
    suspend fun forgetContactChange(entryId: Long): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        (meta.deleteJournalEntry(entryId) > 0).also { if (it) dropUnusedJournalPhotos(meta) }
    }

    /** Forgets the undo copies of deleted calls; the call log is untouched. Returns how many calls. */
    suspend fun clearDeletedCalls(): Int = history.forgetDeleted(null)

    /** Forgets one delete of calls (one row of the Calls tab). */
    suspend fun forgetDeletedCalls(batchId: Long): Int = history.forgetDeleted(batchId)

    /** Daily upkeep: gives free pages of Parley's database and the call archive back to the phone ([VacuumPolicy]). */
    suspend fun tidy(): Unit = withContext(Dispatchers.IO + NonCancellable) {
        tidyDatabase(db.openHelper)
        history.tidy()
    }

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

/** Returns free pages to the phone when they are more than a fifth of the file (see [VacuumPolicy]). Best effort. */
internal fun tidyDatabase(helper: SupportSQLiteOpenHelper) {
    try {
        val db = helper.writableDatabase
        val step = VacuumPolicy.decide(db.pragma("auto_vacuum").toInt(), db.pragma("freelist_count"), db.pragma("page_count"))
        if (step == VacuumPolicy.Step.NONE) return
        if (step == VacuumPolicy.Step.INCREMENTAL) {
            db.query("PRAGMA incremental_vacuum").use { c -> c.count }
        } else {
            // The mode takes effect with the next VACUUM, which rewrites the file once.
            db.query("PRAGMA auto_vacuum = ${VacuumPolicy.INCREMENTAL_MODE}").close()
            db.execSQL("VACUUM")
        }
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
    } catch (e: SQLException) {
        Log.w("UndoStorage", "Tidying ${helper.databaseName} failed", e)
    }
}

private fun SupportSQLiteDatabase.pragma(name: String): Long = query("PRAGMA $name").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
