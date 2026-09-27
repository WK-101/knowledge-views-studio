package app.parley.data.backup

/**
 * Feature data that travels inside the encrypted backup's settings section, under keys starting with [PREFIX]
 * (so they never land in the app settings store). Values are plain strings; keep each feature's total small.
 */
interface BackupExtras {
    /** A short name for the backup report when this part couldn't be exported. */
    val section: String

    /**
     * The [app.parley.common.storage.PersistentStores.Sections] this part writes. The backup checks every backed-up
     * store of the registry against these, so a store without a section is reported as missing, never skipped silently.
     */
    val sections: Set<String>

    /** Which restore choice brings this part back. */
    val restoreWith: RestorePart get() = RestorePart.SETTINGS

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

/** The restore options a feature part can follow. */
enum class RestorePart { CONTACTS, BLOCKING, SETTINGS }

/**
 * A part whose restore can weaken a safeguard (supervised call-time limits): it isn't applied on its own. The restore
 * reports it as waiting, and it is applied only after the user confirms (with the app lock) through [applyPending].
 */
interface ConfirmedRestore {
    /** Whether a restore left something waiting for confirmation. */
    fun hasPending(): Boolean

    /** Applies what is waiting; returns false when nothing was. */
    suspend fun applyPending(): Boolean

    fun discardPending()
}
