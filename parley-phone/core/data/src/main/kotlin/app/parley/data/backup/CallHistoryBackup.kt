package app.parley.data.backup

import app.parley.common.backup.CallHistoryLine

/** Parley's call-history archive as an optional backup section (`callhistory.jsonl`). */
interface CallHistoryBackup {
    suspend fun backupLines(): List<CallHistoryLine>

    /** Returns calls restored. */
    suspend fun restoreLines(lines: List<CallHistoryLine>): Int = beginRestore().let { r -> r.add(lines).also { r.finish() } }

    /**
     * A restore written a chunk at a time (a large archive is never one list): what every chunk would otherwise read
     * again (the whole system call log, the private numbers) is read once, and the archive is reloaded once at the end.
     */
    suspend fun beginRestore(): Restore

    interface Restore {
        /** Restores [lines]; returns calls restored. */
        suspend fun add(lines: List<CallHistoryLine>): Int

        /** Call once after the last chunk. */
        suspend fun finish()
    }
}
