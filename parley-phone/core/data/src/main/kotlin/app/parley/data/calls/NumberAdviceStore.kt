package app.parley.data.calls

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the user answered to advice about a number: "Numbers that seem out of service" dismissed (and when, so a new
 * failure brings it back), and SIM suggestions answered or already offered after a call. Kept by the line keys of
 * [CallQualityStore.keyOf] (the call archive's keyed fingerprint, never the number), in app-private preferences.
 */
class NumberAdviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)

    /** Bumped on every write, so screens re-read. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** When "out of service" was dismissed for [lineKey], or null. */
    fun deadDismissedAt(lineKey: String): Long? = prefs.getLong(DEAD + lineKey, 0L).takeIf { it > 0 }

    fun dismissDead(lineKey: String, at: Long = System.currentTimeMillis()) = write { putLong(DEAD + lineKey, at) }

    /** Undoes [dismissDead]. */
    fun undismissDead(lineKey: String) = write { remove(DEAD + lineKey) }

    /** SIM suggestions for [lineKeys] already answered (accepted or dismissed): the SIM ids. */
    fun simAnswered(lineKeys: Collection<String>): Set<String> = idsFor(KEY_ANSWERED, lineKeys)

    fun answerSim(lineKeys: Collection<String>, simId: String) = add(KEY_ANSWERED, lineKeys, simId)

    /** Suggestions already offered on the call-ended screen for [lineKeys] (it offers each one once). */
    fun simOfferedAfterCall(lineKeys: Collection<String>): Set<String> = idsFor(KEY_OFFERED, lineKeys)

    fun markSimOfferedAfterCall(lineKeys: Collection<String>, simId: String) = add(KEY_OFFERED, lineKeys, simId)

    fun clear() = write { clear() }

    private fun idsFor(set: String, lineKeys: Collection<String>): Set<String> {
        val keys = lineKeys.toSet()
        return prefs.getStringSet(set, emptySet()).orEmpty().mapNotNull { e ->
            val cut = e.indexOf(SEP)
            if (cut <= 0 || e.substring(0, cut) !in keys) null else e.substring(cut + 1)
        }.toSet()
    }

    @Synchronized
    private fun add(set: String, lineKeys: Collection<String>, simId: String) {
        if (lineKeys.isEmpty()) return
        val next = prefs.getStringSet(set, emptySet()).orEmpty().toMutableSet()
        lineKeys.forEach { next += it + SEP + simId }
        // A phone collects a few of these a year; a generous cap keeps the file small whatever happens.
        write { putStringSet(set, next.toList().takeLast(MAX_ENTRIES).toSet()) }
    }

    private fun write(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        _version.value++
    }

    private companion object {
        const val FILE = "parley_number_advice"
        const val DEAD = "dead:"
        const val KEY_ANSWERED = "sim_answered"
        const val KEY_OFFERED = "sim_offered"
        const val SEP = '|'
        const val MAX_ENTRIES = 500
    }
}
