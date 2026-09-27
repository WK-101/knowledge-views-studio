package app.parley.common.people

/** The A–Z rail's maths: which entry is under the finger, and where the letter bubble goes. */
object FastScroll {
    /** The entry at [y] on a rail [height] tall with [count] evenly spaced entries (clamped to the ends). */
    fun indexAt(y: Float, height: Float, count: Int): Int {
        if (count <= 0) return -1
        if (height <= 0f) return 0
        return ((y / height) * count).toInt().coerceIn(0, count - 1)
    }

    /**
     * Which section the list shows at the top: the last of [starts] (first item index of each section, ascending)
     * at or before [firstVisible], or -1 above the first section.
     */
    fun sectionAt(firstVisible: Int, starts: List<Int>): Int {
        var found = -1
        for ((i, s) in starts.withIndex()) {
            if (s <= firstVisible) found = i else break
        }
        return found
    }

    /** The bubble's top for a finger at [y]: its bottom corner at the finger, kept inside the rail's [height]. */
    fun bubbleTop(y: Float, height: Float, bubble: Float): Float = (y - bubble).coerceIn(0f, maxOf(0f, height - bubble))
}
