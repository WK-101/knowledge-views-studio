package app.parley.common.calls

import app.parley.common.CallEntry
import app.parley.common.CallType

/** P8: how Recents lists calls. */
enum class RecentsLayout {
    /** Calls in a row from the same number on the same day share one row (the layout Parley always had). */
    GROUPED,

    /** Every call on its own row. */
    CHRONOLOGICAL,

    /** One row per number per day, even when other calls came in between. */
    BY_DAY,
}

/** P8: turns a newest-first call list into Recents rows for a [RecentsLayout]. */
object RecentsGrouping {
    /**
     * [items] must be newest first. [key] identifies the caller (the same key = the same row), [day] the local day.
     * Rows keep the order of their newest call; the calls inside a row stay newest first.
     */
    fun <T> group(items: List<T>, layout: RecentsLayout, key: (T) -> String, day: (T) -> Long): List<List<T>> = when (layout) {
        RecentsLayout.CHRONOLOGICAL -> items.map { listOf(it) }
        RecentsLayout.GROUPED -> {
            val out = ArrayList<MutableList<T>>()
            var lastKey: String? = null
            var lastDay = Long.MIN_VALUE
            for (e in items) {
                val k = key(e)
                val d = day(e)
                if (out.isNotEmpty() && k == lastKey && d == lastDay) {
                    out.last() += e
                } else {
                    out += mutableListOf(e)
                    lastKey = k
                    lastDay = d
                }
            }
            out
        }
        RecentsLayout.BY_DAY -> {
            val rows = LinkedHashMap<Pair<Long, String>, MutableList<T>>()
            for (e in items) rows.getOrPut(day(e) to key(e)) { mutableListOf() } += e
            rows.values.toList()
        }
    }
}

/** P5: which calls "Clear call history" removes. */
enum class ClearScope {
    ALL,

    /** Numbers that aren't contacts (or private contacts), private numbers included. */
    UNKNOWN_NUMBERS,
    MISSED,

    /** Exactly what Recents shows now (its filters and search). */
    SHOWN,
}

/** P5: picks the calls to clear. Private-contact calls (negative ids) live in the vault and are never cleared here. */
object ClearHistory {
    fun select(calls: List<CallEntry>, scope: ClearScope, isKnown: (String) -> Boolean, shownIds: Set<Long> = emptySet()): List<CallEntry> =
        calls.filter { e ->
            e.id > 0 && when (scope) {
                ClearScope.ALL -> true
                ClearScope.UNKNOWN_NUMBERS -> e.presentationHidden || e.number.isBlank() || !isKnown(e.number)
                ClearScope.MISSED -> e.type == CallType.MISSED || e.type == CallType.REJECTED
                ClearScope.SHOWN -> e.id in shownIds
            }
        }
}

/** P6: Telecom's disconnect cause, without the Android types. */
enum class EndCode { LOCAL, REMOTE, BUSY, ERROR, RESTRICTED, CANCELED, OTHER, MISSED, REJECTED, UNKNOWN }

/** P6: why an outgoing call didn't go through. */
enum class FailureKind { AIRPLANE_MODE, NO_SIM_SELECTED, BUSY, OTHER }

/** P6: what is known when a call leaves Telecom. */
data class EndFacts(
    val outgoing: Boolean,
    val connected: Boolean,
    val code: EndCode?,
    /** The call ended while it was still asking which SIM to use. */
    val endedInSimPicker: Boolean,
    /** The user hung up or cancelled it themselves. */
    val userEnded: Boolean,
    val airplaneMode: Boolean,
    val emergency: Boolean,
    val hasNumber: Boolean,
)

/**
 * P6: an outgoing call that never connected and that the user didn't end gets a failure banner with the reason
 * and Retry. A call the other side declined or that was busy on purpose isn't a "failure" of the phone, except
 * BUSY, which is worth retrying.
 */
object CallFailure {
    fun classify(f: EndFacts): FailureKind? {
        if (!f.outgoing || f.connected || f.emergency || !f.hasNumber || f.userEnded) return null
        if (f.endedInSimPicker) return FailureKind.NO_SIM_SELECTED
        val code = f.code ?: return null
        if (code == EndCode.REMOTE || code == EndCode.REJECTED || code == EndCode.MISSED) return null
        if (f.airplaneMode) return FailureKind.AIRPLANE_MODE
        return when (code) {
            EndCode.BUSY -> FailureKind.BUSY
            EndCode.ERROR, EndCode.RESTRICTED, EndCode.CANCELED, EndCode.OTHER, EndCode.UNKNOWN -> FailureKind.OTHER
            // Telecom ended it on this phone without the user asking (no network, no SIM, a carrier refusal).
            EndCode.LOCAL -> FailureKind.OTHER
            else -> null
        }
    }
}

/** P9: the number the keypad's Call button dials. */
object DialTarget {
    /**
     * The typed number exactly as typed (`*`, `#`, `+`, pauses and waits included): the top search result is only
     * used when the input is a name search (letters from a hardware keyboard). Null when nothing is typed.
     */
    fun pick(typed: String, topMatch: String?): String? {
        val n = typed.trim()
        if (n.isEmpty()) return null
        return if (n.any { it.isLetter() }) topMatch else n
    }
}

/** P9: `*#*#1234#*#*` codes are sent to the app that owns them, not dialled. */
object DialCodes {
    private val secret = Regex("^\\*#\\*#([0-9]+)#\\*#\\*$")

    fun secretCode(number: String): String? = secret.find(number.trim())?.groupValues?.get(1)
}

/** P1/P9: which live call is in front and whether a second one is waiting. */
enum class LiveCallState { RINGING, ACTIVE, HOLDING, DIALING, OTHER }

object CallWaiting {
    data class Slots<T>(
        /** The call the screen is about: a ringing call first, then the active one, then one being dialled. */
        val primary: T?,
        /** While [primary] rings: the call that is already going (active, else held). */
        val current: T?,
        val held: List<T>,
        /** A ringing call with another call going: the call-waiting sheet, not the normal incoming screen. */
        val waiting: Boolean,
    )

    fun <T> slots(live: List<T>, state: (T) -> LiveCallState): Slots<T> {
        val at = listOf(LiveCallState.RINGING, LiveCallState.ACTIVE, LiveCallState.DIALING)
            .map { s -> live.indexOfFirst { state(it) == s } }.firstOrNull { it >= 0 } ?: if (live.isEmpty()) -1 else 0
        val primary = live.getOrNull(at)
        val others = live.filterIndexed { i, _ -> i != at }
        val held = others.filter { state(it) == LiveCallState.HOLDING }
        val current = others.firstOrNull { state(it) == LiveCallState.ACTIVE } ?: held.firstOrNull()
        return Slots(primary, current, held, primary != null && state(primary) == LiveCallState.RINGING && current != null)
    }

    /** P1: picture-in-picture only for a call that's going, never while one rings or asks for a SIM. */
    fun <T> pipAllowed(live: List<T>, state: (T) -> LiveCallState, askingForSim: (T) -> Boolean): Boolean =
        live.isNotEmpty() && live.none { state(it) == LiveCallState.RINGING || askingForSim(it) }
}

/** P4: the default-phone-app role request that Android answered without asking the user. */
object RoleRescue {
    /** A refusal faster than this can't have come from a person pressing Cancel on a dialog. */
    const val SILENT_CANCEL_MS = 300L

    fun silentlyRefused(granted: Boolean, elapsedMs: Long): Boolean = !granted && elapsedMs in 0 until SILENT_CANCEL_MS

    enum class Variant { ANDROID_10_11, ANDROID_12, ANDROID_13_PLUS }

    /** Where "Default apps" lives, and whether "Allow restricted settings" exists (Android 13+ sideloads). */
    fun variant(sdk: Int): Variant = when {
        sdk >= 33 -> Variant.ANDROID_13_PLUS
        sdk >= 31 -> Variant.ANDROID_12
        else -> Variant.ANDROID_10_11
    }
}
