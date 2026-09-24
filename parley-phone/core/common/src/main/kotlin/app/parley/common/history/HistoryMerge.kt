package app.parley.common.history

import app.parley.common.CallEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Merges the system call log with Parley's archive. */
object HistoryMerge {
    /**
     * [provider] rows win; archive rows are added only when the provider no longer has them (same number,
     * same start second, same type). Result is newest first.
     */
    fun merge(provider: List<CallEntry>, archive: List<CallEntry>): List<CallEntry> {
        if (archive.isEmpty()) return provider
        val have = HashSet<String>(provider.size * 2)
        provider.forEach { have += key(it) }
        val extra = archive.filter { key(it) !in have }
        if (extra.isEmpty()) return provider
        return (provider + extra).sortedByDescending { it.date }
    }

    fun key(e: CallEntry): String = NumberKeys.dedupe(if (e.presentationHidden) "" else e.number, e.date) + "|" + e.type

    /**
     * F21: the [rows] still to restore: those whose [key] isn't among [present] already, each once. Restoring the
     * same batch twice (a double tap, a retry after a crash half-way) then adds nothing the second time.
     */
    fun <T> missing(rows: List<T>, present: Iterable<T>, key: (T) -> String): List<T> {
        val have = HashSet<String>()
        present.forEach { have += key(it) }
        return rows.filter { have.add(key(it)) }
    }
}

/** "Delete calls from…" choices for one number (K10). */
enum class DeleteRange {
    ALL,
    LAST_YEAR,
    LAST_MONTH,
    LAST_WEEK,
    LAST_DAY,
    SINCE_DATE,
    ;

    /** Calls at or after the returned epoch millis are deleted. */
    fun since(now: Long, zone: ZoneId, picked: LocalDate? = null): Long {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        return when (this) {
            ALL -> Long.MIN_VALUE
            LAST_YEAR -> t.minusYears(1).toInstant().toEpochMilli()
            LAST_MONTH -> t.minusMonths(1).toInstant().toEpochMilli()
            LAST_WEEK -> t.minusWeeks(1).toInstant().toEpochMilli()
            LAST_DAY -> t.minusDays(1).toInstant().toEpochMilli()
            SINCE_DATE -> requireNotNull(picked) { "Pick a date" }.atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }

    companion object {
        fun select(calls: List<CallEntry>, since: Long): List<CallEntry> = calls.filter { it.date >= since }
    }
}
