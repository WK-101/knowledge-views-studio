package app.parley.data.calltime

import app.parley.data.db.AppDatabase
import app.parley.data.db.CallUsageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Connected calls as the call path saw them, for call-time allowances. The system call log alone isn't enough: it can
 * be cleared by any dialer, trims itself, and has no calls with private contacts. Emergency calls are never written.
 */
class CallUsageLedger(private val db: AppDatabase) {
    private val dao get() = db.usageDao()

    suspend fun record(e: CallUsageEntity) = withContext(Dispatchers.IO) {
        dao.add(e)
        dao.prune(e.startedAt - KEEP_DAYS * DAY)
    }

    /** Calls since [since], newest first. Throws when the database can't be read (callers decide how to fail). */
    suspend fun since(since: Long): List<CallUsageEntity> = withContext(Dispatchers.IO) { dao.since(since) }

    companion object {
        /** A little over the longest allowance period (a week), plus room for a clock change. */
        const val KEEP_DAYS = 35L
        private const val DAY = 86_400_000L
    }
}
