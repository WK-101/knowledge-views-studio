package app.parley.common.backup

/** Which daily snapshots a "Clear snapshots" from History & undo keeps. */
enum class SnapshotKeep {
    /** Snapshots from the last [SnapshotClearing.RECENT_DAYS] days stay, so recent changes can still be undone. */
    RECENT,

    /** Only the newest snapshot stays: "what changed" still works from it on, nothing older can be restored. */
    LATEST,

    /** Every snapshot goes; Parley takes a new one with its next daily run. */
    NONE,
}

/** Picks the snapshots a clear removes. Pure, so the choice is tested apart from the files. */
object SnapshotClearing {
    const val RECENT_DAYS = 30L
    private const val DAY_MS = 86_400_000L

    /** The timestamps (of [all]) to delete for [keep], at [now]. */
    fun toDrop(all: Collection<Long>, keep: SnapshotKeep, now: Long): Set<Long> = when (keep) {
        SnapshotKeep.NONE -> all.toSet()
        SnapshotKeep.LATEST -> all.maxOrNull()?.let { newest -> all.filter { it != newest }.toSet() }.orEmpty()
        SnapshotKeep.RECENT -> all.filter { now - it > RECENT_DAYS * DAY_MS }.toSet()
    }
}
