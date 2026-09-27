package app.parley.common.calls

import app.parley.common.SimAccount

/**
 * K1 (v3.4): the keypad's Call pill. One SIM (or none, or more than [MAX_SEGMENTS] call accounts): a single Call
 * pill that follows the usual SIM rules (remembered SIM, default SIM, else the SIM question). Two or three SIMs:
 * one pill split into a segment per SIM, each calling with its own SIM.
 */
object CallPill {
    const val MAX_SEGMENTS = 3

    /**
     * One segment. [label]: what the segment shows (the SIM's own or carrier label, shortened), or null to show
     * the slot name ("SIM 1"). [slot]: 1-based SIM slot, null when unknown (SIP accounts). [carrier]: the full
     * label for TalkBack, null when it only repeats the slot name. [preferred]: the SIM a plain Call would use.
     */
    data class Segment(val simId: String, val slot: Int?, val label: String?, val carrier: String?, val preferred: Boolean)

    /** Characters a segment label may show, by the number of segments. */
    fun maxChars(segments: Int): Int = when {
        segments <= 1 -> 14
        segments == 2 -> 10
        else -> 6
    }

    /** The segments for [sims], or an empty list for a single Call pill. [preferredId]: the SIM a plain Call would use. */
    fun segments(sims: List<SimAccount>, preferredId: String?): List<Segment> {
        if (sims.size < 2 || sims.size > MAX_SEGMENTS) return emptyList()
        val max = maxChars(sims.size)
        val raw = sims.map { sim ->
            val slot = sim.slotIndex.takeIf { it >= 0 }?.plus(1)
            val name = sim.label.trim().replace(WHITESPACE, " ")
            val generic = name.isEmpty() || isGeneric(name, slot)
            Segment(
                simId = sim.id,
                slot = slot,
                label = if (generic) null else shorten(name, max),
                carrier = if (generic) null else name,
                preferred = sim.id == preferredId,
            )
        }
        // Two SIMs from the same carrier (or cut to the same text) would look alike: show their slots instead.
        val clashes = raw.mapNotNull { it.label }.groupingBy { it.lowercase() }.eachCount().filterValues { it > 1 }.keys
        return raw.map { s ->
            if (s.label != null && s.label.lowercase() in clashes && s.slot != null) s.copy(label = null) else s
        }
    }

    /** "SIM 1", "SIM1", "sim 2": the system's placeholder label, which says nothing more than the slot. */
    fun isGeneric(label: String, slot: Int?): Boolean {
        val m = GENERIC.matchEntire(label.trim()) ?: return false
        return slot == null || m.groupValues[1].toIntOrNull() == slot
    }

    /**
     * [text] cut to at most [max] characters (the ellipsis included), at a word break when that keeps at least
     * half of it, and never inside a surrogate pair.
     */
    fun shorten(text: String, max: Int): String {
        val t = text.trim().replace(WHITESPACE, " ")
        if (t.length <= max || max < 2) return t
        var cut = max - 1
        if (Character.isLowSurrogate(t[cut])) cut--
        val head = t.substring(0, cut)
        val space = head.lastIndexOf(' ')
        val kept = if (space >= (max - 1) / 2) head.substring(0, space) else head
        return kept.trimEnd(' ', '-', '·', ',', '.') + ELLIPSIS
    }

    /** The folded keypad button's badge: the typed number's last digits ("…5678"), or null with nothing dialable typed. */
    fun badge(number: String, keep: Int = 4): String? {
        val shown = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (shown.isEmpty()) return null
        return if (shown.length <= keep) shown else ELLIPSIS + shown.takeLast(keep)
    }

    private const val ELLIPSIS = "…"
    private val WHITESPACE = Regex("\\s+")
    private val GENERIC = Regex("(?i)sim\\s*(\\d+)")
}
