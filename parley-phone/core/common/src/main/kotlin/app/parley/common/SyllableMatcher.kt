package app.parley.common

/**
 * Keypad matching over syllables, for names written in Chinese, Japanese or Korean. Each name character becomes one
 * unit holding the keypad code of its romanised reading ("张" → "zhang" → 94264); runs of other letters form one unit
 * per word.
 *
 * A query is a run of consecutive units, each contributing a non-empty prefix of its code: its initial ("zs" for
 * 张三), the whole syllable ("zhangsan"), or anything in between ("zhangs", "zsan"). The last unit may be partial,
 * so the matcher is prefix-closed: every prefix of a matching query matches too.
 */
object SyllableMatcher {
    /** @param positions the name index of each code character (all the same for a single character). */
    class Unit(val code: String, val positions: IntArray) {
        fun range(length: Int): IntRange = positions[0]..positions[length - 1]
    }

    data class Result(val firstUnit: Int, val ranges: List<IntRange>)

    fun match(query: String, units: List<Unit>): Result? {
        if (query.isEmpty() || units.isEmpty()) return null
        for (start in units.indices) {
            val taken = IntArray(units.size)
            val failed = HashSet<Long>()
            if (consume(query, 0, units, start, taken, failed)) {
                val ranges = (start until units.size).takeWhile { taken[it] > 0 }.map { units[it].range(taken[it]) }
                return Result(start, ranges)
            }
        }
        return null
    }

    /** Depth-first, longest prefix first so whole syllables win the highlight. */
    private fun consume(query: String, qi: Int, units: List<Unit>, u: Int, taken: IntArray, failed: HashSet<Long>): Boolean {
        if (qi == query.length) return true
        if (u >= units.size) return false
        val key = (u.toLong() shl 32) or qi.toLong()
        if (key in failed) return false
        val code = units[u].code
        var common = 0
        while (common < code.length && qi + common < query.length && code[common] == query[qi + common]) common++
        for (k in common downTo 1) {
            taken[u] = k
            if (consume(query, qi + k, units, u + 1, taken, failed)) return true
        }
        taken[u] = 0
        failed += key
        return false
    }
}
