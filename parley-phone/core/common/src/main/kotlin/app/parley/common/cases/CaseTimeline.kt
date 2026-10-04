package app.parley.common.cases

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.circle.Promises
import kotlin.math.abs

/** One dated line of a case file's timeline. */
sealed interface CaseEntry {
    val at: Long

    /**
     * A call: [type] as the call history has it (null for one only the case file kept, the log having trimmed it, then
     * [incoming] says which way), how long it lasted, the time on hold and the menu keys sent ("214").
     */
    data class Call(
        override val at: Long,
        val type: CallType?,
        val incoming: Boolean,
        val durationSec: Long,
        val holdSec: Long = 0,
        val menu: String = "",
    ) : CaseEntry

    /** A note written after a call with the organisation. */
    data class Note(override val at: Long, val text: String) : CaseEntry

    /** A reference number was added (its value isn't part of the timeline). */
    data class Reference(override val at: Long, val id: String, val label: String) : CaseEntry
}

/** A note after a call, as the timeline takes it. */
data class CaseNote(val at: Long, val text: String)

/** The few facts the case card shows. */
data class CaseSummary(
    val calls: Int = 0,
    val firstCallAt: Long? = null,
    val lastCallAt: Long? = null,
    /** Calls whose hold time is known (hold mode was used). */
    val heldCalls: Int = 0,
    val totalHoldSec: Long = 0,
    val longestHoldSec: Long = 0,
    /** The menu keys of the latest call that sent any ("214"). */
    val menu: String = "",
    val references: Int = 0,
    val openPromises: Int = 0,
) {
    val averageHoldSec: Long get() = if (heldCalls == 0) 0 else totalHoldSec / heldCalls

    /** Nothing to show yet: no call, reference or promise. */
    val isEmpty: Boolean get() = calls == 0 && references == 0 && openPromises == 0
}

/** A case file as its screen shows it: the entries newest first, the summary and the open promises. */
data class CaseTimeline(val entries: List<CaseEntry>, val summary: CaseSummary, val promises: List<Promises.Item>)

/**
 * Puts a case file's timeline together from what Parley already keeps: the calls with the organisation (Android's call
 * log and Parley's copy), what the case file kept per call (hold time, menu keys), notes written after calls, the
 * reference numbers and the open promises of the contact's notes. Pure.
 */
object CaseTimelines {
    /** A call of the history and one the case file kept are the same call when they started this close together. */
    const val SAME_CALL_MS = 2 * 60_000L

    /**
     * [history]: the calls with the case's numbers (any order); [notes]: notes after those calls; [promises]: the
     * promises in the contact's notes (open ones are shown).
     */
    fun assemble(case: CaseFile?, history: List<CallEntry>, notes: List<CaseNote>, promises: List<Promises.Item>): CaseTimeline {
        val kept = case?.calls.orEmpty().sortedBy { it.at }.toMutableList()
        val calls = history.filterNot { it.presentationHidden }.sortedBy { it.date }.map { e ->
            val same = kept.filter { abs(it.at - e.date) <= SAME_CALL_MS }.minByOrNull { abs(it.at - e.date) }
            if (same != null) kept.remove(same)
            CaseEntry.Call(
                e.date, e.type, e.type != CallType.OUTGOING, e.durationSec,
                holdSec = same?.holdSec ?: 0, menu = same?.menu.orEmpty(),
            )
        } + kept.map { k -> CaseEntry.Call(k.at, null, k.incoming, k.durationSec, k.holdSec, k.menu) }
        val refs = case?.references.orEmpty().map { CaseEntry.Reference(it.at, it.id, it.label) }
        val noteEntries = notes.filter { it.text.isNotBlank() }.map { CaseEntry.Note(it.at, it.text.trim()) }
        val entries = (calls + noteEntries + refs).sortedWith(compareByDescending<CaseEntry> { it.at }.thenBy { order(it) })
        val open = promises.filterNot { it.done }
        return CaseTimeline(entries, summary(calls, case?.references.orEmpty().size, open.size), open)
    }

    private fun summary(calls: List<CaseEntry.Call>, references: Int, openPromises: Int): CaseSummary {
        val held = calls.filter { it.holdSec > 0 }
        return CaseSummary(
            calls = calls.size,
            firstCallAt = calls.minOfOrNull { it.at },
            lastCallAt = calls.maxOfOrNull { it.at },
            heldCalls = held.size,
            totalHoldSec = held.sumOf { it.holdSec },
            longestHoldSec = held.maxOfOrNull { it.holdSec } ?: 0,
            menu = calls.filter { it.menu.isNotEmpty() }.maxByOrNull { it.at }?.menu.orEmpty(),
            references = references,
            openPromises = openPromises,
        )
    }

    /** At the same moment: the call first, then its note, then a reference given in it. */
    private fun order(e: CaseEntry): Int = when (e) {
        is CaseEntry.Call -> 0
        is CaseEntry.Note -> 1
        is CaseEntry.Reference -> 2
    }
}
