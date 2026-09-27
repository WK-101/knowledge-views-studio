package app.parley.common.calls

/** How Recents lists calls. */
enum class RecentsLayout {
    /** Calls in a row from the same number on the same day share one row (the layout Parley always had). */
    GROUPED,

    /** Every call on its own row. */
    CHRONOLOGICAL,

    /** One row per number per day, even when other calls came in between. */
    BY_DAY,
}

/** Turns a newest-first call list into Recents rows for a [RecentsLayout]. */
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
