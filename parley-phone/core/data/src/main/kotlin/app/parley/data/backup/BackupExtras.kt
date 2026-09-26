package app.parley.data.backup

/**
 * Feature data that travels inside the encrypted backup's settings section, under keys starting with [PREFIX]
 * (so they never land in the app settings store). Values are plain strings; keep each feature's total small.
 */
interface BackupExtras {
    suspend fun export(): Map<String, String>

    /** Receives only the keys starting with [PREFIX] from the backup being restored. */
    suspend fun import(values: Map<String, String>)

    /**
     * Like [import]; returns how many entries couldn't be matched to anyone on this phone (they are skipped, and
     * the restore reports the count).
     */
    suspend fun importCounting(values: Map<String, String>): Int {
        import(values)
        return 0
    }

    companion object {
        const val PREFIX = "x."
    }
}
