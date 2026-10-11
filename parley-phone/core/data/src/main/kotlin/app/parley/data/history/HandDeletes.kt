package app.parley.data.history

import android.content.Context
import app.parley.data.StatePrefs

/**
 * Calls deleted by hand: number memory reads the whole archive again at its next rebuild, so nothing it kept comes
 * from them (a name, a count). Marked before the delete, so a crash between the two errs on reading again; a flag
 * that can't be read counts as set.
 */
internal class HandDeletes(private val context: Context) {
    private fun prefs() = StatePrefs.open(context, "history_upkeep")

    fun mark() {
        runCatching { prefs().edit().putBoolean(K_PENDING, true).commit() }
    }

    fun pending(): Boolean = runCatching { prefs().getBoolean(K_PENDING, false) }.getOrDefault(true)

    fun done() {
        runCatching { prefs().edit().remove(K_PENDING).commit() }
    }

    private companion object {
        const val K_PENDING = "rescan"
    }
}
