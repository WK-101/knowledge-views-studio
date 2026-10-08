package app.parley.common.people

import app.parley.common.ux.ListSections
import kotlin.math.roundToInt

/**
 * The A–Z index beside an alphabetical list (Contacts, the contact pickers, a label's members): which entries it has,
 * how they fit a short screen, and where it sits. It belongs to the alphabetical part only: it starts below whatever
 * leads the list (filter chips, My card, the favourites and the Circle) and shows once that part is on screen, so it
 * never covers them. The drawing is core/ui's `AlphabetIndexRail`; the reasons are in docs/UI_DESIGN.md.
 */
object AlphabetIndex {
    /** The favourites' entry, first, when they lead the list and the entry jumps to them. */
    const val FAVOURITES = "★"

    /** Names that start with a digit or a symbol. */
    const val DIGITS = "#"

    /** A shorter list scrolls well enough without an index. */
    const val MIN_ITEMS = 30

    /** A script with more starting letters than this (Chinese characters, say) gets [SAMPLED] evenly spaced entries. */
    const val MAX_PER_SCRIPT = 40

    /** Entries kept for a script with more than [MAX_PER_SCRIPT] starting letters. */
    const val SAMPLED = 12

    /** One entry: what it shows and the lazy-list index it jumps to. */
    data class Entry(val label: String, val target: Int)

    /**
     * The entries for a list whose sections are [sections] (each section's key with the lazy-list index of its first
     * row, in list order): only sections the list has, Latin letters one by one, "#" where the list puts it, and each
     * other script kept as a run in list order, its letters one by one unless it has more than [MAX_PER_SCRIPT] (then
     * [SAMPLED] of them, evenly spaced, the first included). [favouritesAt]: the favourites lead the list at this index,
     * and "★" jumps there.
     */
    fun entries(sections: List<Pair<String, Int>>, favouritesAt: Int? = null): List<Entry> {
        val out = ArrayList<Entry>()
        if (favouritesAt != null) out += Entry(FAVOURITES, favouritesAt)
        val seen = HashSet<String>()
        val unique = sections.filter { (key, _) -> key.isNotEmpty() && seen.add(key) }
        var i = 0
        while (i < unique.size) {
            val script = scriptOf(unique[i].first)
            var j = i
            while (j < unique.size && scriptOf(unique[j].first) == script) j++
            val run = unique.subList(i, j)
            val kept = if (script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.COMMON || run.size <= MAX_PER_SCRIPT) {
                run
            } else {
                spread(run.size, SAMPLED).map { run[it] }
            }
            kept.forEach { (key, at) -> out += Entry(key, at) }
            i = j
        }
        return out
    }

    /**
     * The sections of a list without letter headers (a picker), sorted by name: each starting letter
     * ([ListSections.letterOf] of [names]) with the lazy-list index of its first row, the rows starting at [offset].
     */
    fun sectionsOf(names: List<String>, offset: Int): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var last: String? = null
        names.forEachIndexed { i, name ->
            val letter = ListSections.letterOf(name)
            if (letter != last) {
                out += letter to offset + i
                last = letter
            }
        }
        return out
    }

    /**
     * What the index draws in [slots] rows for [count] entries, as entry positions: all of them when they fit, else
     * as many as fit with a dot (null) between each two (the first and last always shown), as iOS and One UI do on a
     * short screen. Dragging still reaches every entry (it picks by position over the whole index).
     */
    fun compact(count: Int, slots: Int): List<Int?> {
        if (count <= 0 || slots <= 0) return emptyList()
        if (count <= slots) return List(count) { it }
        if (slots < 3) return listOf(0)
        val labels = ((slots + 1) / 2).coerceAtLeast(2)
        val picked = spread(count, labels)
        return buildList {
            picked.forEachIndexed { k, p ->
                if (k > 0) add(null)
                add(p)
            }
        }
    }

    /** Where the index sits ([top], from the top of the list), or that it is out of sight ([shown] false). */
    data class Placement(val shown: Boolean, val top: Float) {
        companion object {
            val HIDDEN = Placement(false, 0f)
        }
    }

    /**
     * Where the index goes for a list scrolled to [firstVisible] (the first row on screen), whose alphabetical part
     * starts at lazy-list index [start] and is drawn [startTop] pixels from the top when that row is on screen (null
     * when it isn't), in a list [viewport] pixels tall; [header] is a letter header's height (the index starts below
     * the pinned one). Shown once the alphabetical part reaches the top, or has room for an index at least
     * [minHeight] tall below its first header; hidden while it is further down (the chips, My card, favourites and
     * the Circle have the screen). While a finger is on it ([dragging]) it stays where it was ([held]), so a jump up
     * to "★" doesn't pull it from under the finger.
     */
    @Suppress("LongParameterList") // The list's geometry, each from its own measurement.
    fun placement(
        firstVisible: Int,
        start: Int,
        startTop: Float?,
        viewport: Float,
        header: Float,
        minHeight: Float,
        dragging: Boolean = false,
        held: Placement? = null,
    ): Placement {
        if (dragging && held != null && held.shown) return held
        if (viewport <= 0f || start < 0) return Placement.HIDDEN
        val top = when {
            firstVisible > start -> header
            startTop != null -> maxOf(startTop, 0f) + header
            firstVisible == start -> header
            else -> return Placement.HIDDEN
        }
        return if (viewport - top >= minHeight) Placement(true, top) else Placement.HIDDEN
    }

    /** [n] positions spread evenly over 0 until [count], the first and the last included. */
    private fun spread(count: Int, n: Int): List<Int> {
        if (n >= count) return List(count) { it }
        if (n <= 1) return listOf(0)
        return List(n) { k -> (k * (count - 1).toDouble() / (n - 1)).roundToInt() }.distinct()
    }

    private fun scriptOf(key: String): Character.UnicodeScript =
        runCatching { Character.UnicodeScript.of(key.codePointAt(0)) }.getOrDefault(Character.UnicodeScript.COMMON)
}
