package app.parley.data.backup

import app.parley.common.backup.CallHistoryLine

/** Parley's call-history archive as an optional backup section (`callhistory.jsonl`). */
interface CallHistoryBackup {
    suspend fun backupLines(): List<CallHistoryLine>

    /** Returns calls restored. */
    suspend fun restoreLines(lines: List<CallHistoryLine>): Int
}
