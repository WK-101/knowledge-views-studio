package app.parley.common.backup

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.IsoFields

/** A file in the backup folder. [timestamp] is epoch millis (from the name or the file's mtime). */
data class BackupFile(val name: String, val timestamp: Long)

sealed interface RetentionPolicy {
    /** Keep the newest [keepLast] backups. */
    data class Simple(val keepLast: Int) : RetentionPolicy {
        init { require(keepLast >= 0) }
    }

    /**
     * Grandfather-father-son: keep the newest backup of each of the last [daily] days, [weekly] ISO
     * weeks, [monthly] months and [yearly] years that have a backup (like restic's `forget --keep-*`).
     */
    data class Periodic(val daily: Int, val weekly: Int, val monthly: Int, val yearly: Int) : RetentionPolicy {
        init { require(daily >= 0 && weekly >= 0 && monthly >= 0 && yearly >= 0) }
    }
}

/**
 * [keep] and [delete] partition the Parley backups; [ignored] are files that don't match Parley's
 * naming pattern and are never touched.
 */
data class RetentionDecision(val keep: List<BackupFile>, val delete: List<BackupFile>, val ignored: List<BackupFile>)

object RetentionDecider {
    /** `parley-backup-yyyyMMdd-HHmmss.parley` */
    val NAME_PATTERN = Regex("parley-backup-(\\d{8}-\\d{6})\\.parley")
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Mass-deletion guard thresholds: pause if more than 20 % or more than 50 contacts disappeared. */
    const val MAX_DROP_FRACTION = 0.20
    const val MAX_DROP_ABSOLUTE = 50

    fun fileName(time: Instant, zone: ZoneId): String = "parley-backup-" + STAMP.format(LocalDateTime.ofInstant(time, zone)) + ".parley"

    /** Parses the timestamp embedded in a Parley backup name, or null if it isn't one. */
    fun parseName(name: String, zone: ZoneId): Instant? {
        val m = NAME_PATTERN.matchEntire(name) ?: return null
        return try {
            LocalDateTime.parse(m.groupValues[1], STAMP).atZone(zone).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun isCandidate(name: String): Boolean = NAME_PATTERN.matches(name)

    /**
     * Decides which backups to delete. Rules: only files matching [NAME_PATTERN] are candidates;
     * the newest (non-future) backup is always kept; files dated after [now] are never deleted (clock
     * changes) and don't count towards any bucket. Output lists are newest first.
     */
    fun decide(files: List<BackupFile>, policy: RetentionPolicy, now: Instant, zone: ZoneId): RetentionDecision {
        val (candidates, ignored) = files.partition { isCandidate(it.name) }
        val nowMs = now.toEpochMilli()
        val (future, past) = candidates.partition { it.timestamp > nowMs }
        val sorted = past.sortedWith(compareByDescending<BackupFile> { it.timestamp }.thenByDescending { it.name })
        val keep = LinkedHashSet<BackupFile>()
        sorted.firstOrNull()?.let(keep::add)
        when (policy) {
            is RetentionPolicy.Simple -> sorted.take(policy.keepLast).forEach(keep::add)
            is RetentionPolicy.Periodic -> {
                fun bucket(n: Int, key: (LocalDateTime) -> Any) {
                    if (n <= 0) return
                    val seen = HashSet<Any>()
                    for (f in sorted) {
                        val k = key(LocalDateTime.ofInstant(Instant.ofEpochMilli(f.timestamp), zone))
                        if (seen.add(k)) {
                            keep.add(f)
                            if (seen.size == n) break
                        }
                    }
                }
                bucket(policy.daily) { it.toLocalDate() }
                bucket(policy.weekly) { it.get(IsoFields.WEEK_BASED_YEAR) * 100 + it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }
                bucket(policy.monthly) { it.year * 100 + it.monthValue }
                bucket(policy.yearly) { it.year }
            }
        }
        val order = compareByDescending<BackupFile> { it.timestamp }.thenByDescending { it.name }
        return RetentionDecision(
            keep = (future + keep).sortedWith(order),
            delete = sorted.filter { it !in keep },
            ignored = ignored,
        )
    }

    /**
     * Mass-deletion guard: true when rotation must pause because the contact count dropped by more
     * than 20 % or by more than 50 contacts since the previous backup (a sync accident or a wipe
     * shouldn't rotate away the last good backups).
     */
    fun mustPauseRotation(previousCount: Int, currentCount: Int): Boolean {
        val drop = previousCount - currentCount
        if (drop <= 0) return false
        return drop > MAX_DROP_ABSOLUTE || drop.toDouble() > previousCount * MAX_DROP_FRACTION
    }
}
