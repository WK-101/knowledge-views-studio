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
     * other script in list order, its letters one by one unless the whole list has more than [MAX_PER_SCRIPT] of them
     * (then [SAMPLED] of them, evenly spaced, the first included). [favouritesAt]: the favourites lead the list at this index,
     * and "★" jumps there.
     */
    fun entries(sections: List<Pair<String, Int>>, favouritesAt: Int? = null): List<Entry> {
        val out = ArrayList<Entry>()
        if (favouritesAt != null) out += Entry(FAVOURITES, favouritesAt)
        val seen = HashSet<String>()
        val unique = sections.filter { (key, _) -> key.isNotEmpty() && seen.add(key) }
        // A script's letters are counted over the whole list, not run by run: Chinese and Japanese names sorted by
        // reading can come in many short runs between Latin ones, and each would otherwise stay under the limit.
        val kept = HashSet<String>()
        unique.groupBy { scriptOf(it.first) }.forEach { (script, keys) ->
            if (script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.COMMON || keys.size <= MAX_PER_SCRIPT) {
                keys.forEach { kept += it.first }
            } else {
                spread(keys.size, SAMPLED).forEach { kept += keys[it].first }
            }
        }
        unique.forEach { (key, at) -> if (key in kept) out += Entry(key, at) }
        return out
    }

    /**
     * [list] in the order its index needs: sorted by [name] with the Contacts list's collation ([order]), then each
     * starting letter ([ListSections.letterOf]) gathered into one run where the collation first reaches it. The sort
     * and the grouping use the same key, so every letter is one block the index can reach (a reading-order sort of
     * Chinese names, say, would otherwise split a letter into several).
     */
    fun <T> grouped(list: List<T>, order: Comparator<String>, name: (T) -> String): List<T> {
        val groups = LinkedHashMap<String, MutableList<T>>()
        Collation.sortedBy(list, order, name).forEach { groups.getOrPut(ListSections.letterOf(name(it))) { ArrayList() } += it }
        return groups.values.flatten()
    }

    /**
     * The sections of a list without letter headers (a picker), in [grouped] order: each starting letter
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

    /**
     * What leads the Contacts list, in order, above its alphabetical part: the chips row, the "private details locked"
     * card, My card, the favourites and the Circle. The index's targets count every one of them shown.
     */
    data class Lead(
        val privateLocked: Boolean = false,
        val me: Boolean = false,
        val favourites: Boolean = false,
        val circle: Boolean = false,
    ) {
        /** Rows before the first letter (the chips row is always there). */
        val rows: Int get() = 1 + listOf(privateLocked, me, favourites, circle).count { it }

        /** The favourites' row, when shown. */
        val favouritesAt: Int? get() = if (favourites) 1 + listOf(privateLocked, me).count { it } else null
    }

    /**
     * The entries TalkBack's adjustable control steps through: all but a leading "★". Jumping to the favourites moves
     * the list above its letters, where the index hides, and the control would vanish from under TalkBack's focus;
     * the favourites are reached by heading navigation instead.
     */
    fun spoken(entries: List<Entry>): IntRange {
        val from = if (entries.firstOrNull()?.label == FAVOURITES) 1 else 0
        return from until entries.size
    }

    /** The largest letter on the index, in dp at the default font size; it grows with the font scale. */
    const val LETTER_DP = 13f

    /** A letter's height as a share of its row, so neighbours never touch. */
    private const val LETTER_SHARE = 0.62f

    /** The font scale the index follows at most (beyond it, rows stay this tall and more letters become dots). */
    private const val MAX_FONT_SCALE = 2f

    /** The smallest row a letter gets at [fontScale], from [minSlotDp] at the default size: larger fonts get fewer, larger rows. */
    fun minSlot(minSlotDp: Float, fontScale: Float): Float = minSlotDp * fontScale.coerceIn(1f, MAX_FONT_SCALE)

    /**
     * A letter's size in dp for rows [rowDp] tall at [fontScale]: as large as the row allows, at most [LETTER_DP]
     * scaled with the font, so a large font makes the index compact (dots between letters) instead of overlapping.
     * Shown as sp, it is divided by the font scale again.
     */
    fun letterDp(rowDp: Float, fontScale: Float): Float =
        (rowDp * LETTER_SHARE).coerceIn(0f, LETTER_DP * fontScale.coerceIn(1f, MAX_FONT_SCALE))

    /** [n] positions spread evenly over 0 until [count], the first and the last included. */
    private fun spread(count: Int, n: Int): List<Int> {
        if (n >= count) return List(count) { it }
        if (n <= 1) return listOf(0)
        return List(n) { k -> (k * (count - 1).toDouble() / (n - 1)).roundToInt() }.distinct()
    }

    private fun scriptOf(key: String): Character.UnicodeScript =
        runCatching { Character.UnicodeScript.of(key.codePointAt(0)) }.getOrDefault(Character.UnicodeScript.COMMON)
}
