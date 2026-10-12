package app.parley.situations

import android.content.Context
import androidx.core.content.edit
import app.parley.common.catching
import app.parley.common.situations.SituationSuggestion
import app.parley.common.situations.SituationSuggestions
import app.parley.common.situations.SuggestionKind
import app.parley.common.situations.SuggestionMarks
import app.parley.common.suspendRunCatching
import app.parley.container
import app.parley.data.StatePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/**
 * Finds the Situation suggestion to show ([SituationSuggestions]) from what Parley already keeps (the calls, private
 * contacts' while they can be read, and the Bluetooth device calls were answered on), and keeps only the answer: when
 * one was last answered and the kinds not to suggest again, on this phone.
 */
object SituationSuggester {
    private const val PREFS = "situation_suggestions"
    private const val K_ANSWERED = "answered_at"
    private const val K_NEVER = "never"

    /** How long to wait for the call history to load before giving up for now. */
    private const val LOAD_MS = 10_000L

    /** A suggestion worked out is reused this long, so the home screen doesn't read the history each time it shows. */
    private const val REUSE_MS = 6 * 3_600_000L

    @Volatile
    private var found: Pair<Long, SituationSuggestion?>? = null

    fun marks(context: Context): SuggestionMarks {
        val p = StatePrefs.open(context.applicationContext, PREFS)
        val never = p.getStringSet(K_NEVER, null).orEmpty().mapNotNull { n -> SuggestionKind.entries.firstOrNull { it.name == n } }.toSet()
        return SuggestionMarks(p.getLong(K_ANSWERED, 0), never)
    }

    /** The suggestion to show now, or null; worked out off the main thread. */
    suspend fun find(context: Context, now: Long = System.currentTimeMillis()): SituationSuggestion? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val marks = marks(app)
        // Shown at most once a week: nothing else is read before then.
        if (now - marks.answeredAt < SituationSuggestions.WEEK_MS) return@withContext null
        val c = app.container
        // Reused, unless the Situation it would change has started switching itself on meanwhile.
        found?.takeIf { now - it.first in 0 until REUSE_MS }?.let { (_, s) ->
            if (s == null || c.situations.list.value.any { it.id == s.situationId && !it.automatic }) return@withContext s
        }
        val calls = withTimeoutOrNull(LOAD_MS) { c.history.callsWithPrivate.filterNotNull().first() } ?: return@withContext null
        val rings = catching { c.ringFacts.all() }.getOrNull().orEmpty()
        val cars = c.driveProfile.config.value.cars.isNotEmpty()
        SituationSuggestions.suggest(calls, rings, c.situations.list.value, cars, marks, now, ZoneId.systemDefault()).also { found = now to it }
    }

    /** "Not now" ([never] false) or "Don't suggest this" ([never] true): the card goes, and none comes for a week. */
    fun answer(context: Context, kind: SuggestionKind, never: Boolean, now: Long = System.currentTimeMillis()) {
        val p = StatePrefs.open(context.applicationContext, PREFS)
        val before = p.getStringSet(K_NEVER, null).orEmpty()
        found = null
        p.edit {
            putLong(K_ANSWERED, now)
            if (never) putStringSet(K_NEVER, before + kind.name)
        }
    }

    /** "Set it up": the Situation takes the suggestion (its page opens next, with who may ring), and none comes for a week. */
    suspend fun accept(context: Context, s: SituationSuggestion): Boolean {
        val app = context.applicationContext
        val ok = suspendRunCatching { app.container.situations.edit(s.situationId) { s.applyTo(it) } }.getOrDefault(false)
        if (ok) withContext(Dispatchers.IO) { answer(app, s.kind, never = false) }
        return ok
    }
}
