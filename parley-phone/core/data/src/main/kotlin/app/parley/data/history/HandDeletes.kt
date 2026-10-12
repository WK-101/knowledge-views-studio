package app.parley.data.history

import android.content.Context
import app.parley.common.catching
import app.parley.data.StatePrefs

/**
 * Calls deleted by hand: number memory reads the whole archive again at its next rebuild, so nothing it kept comes
 * from them (a name, a count). Marked before the delete, so a crash between the two errs on reading again; a flag
 * that can't be read counts as set.
 */
internal class HandDeletes(private val context: Context) {
    private fun prefs() = StatePrefs.open(context, "history_upkeep")

    fun mark() {
        catching { prefs().edit().putBoolean(K_PENDING, true).commit() }
    }

    fun pending(): Boolean = catching { prefs().getBoolean(K_PENDING, false) }.getOrDefault(true)

    fun done() {
        catching { prefs().edit().remove(K_PENDING).commit() }
    }

    private companion object {
        const val K_PENDING = "rescan"
    }
}
