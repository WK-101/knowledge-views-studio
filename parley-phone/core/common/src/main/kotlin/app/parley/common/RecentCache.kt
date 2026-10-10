package app.parley.common

/**
 * A bounded cache that forgets the least recently used entry when full, one at a time, never everything at once: a
 * working set just over the bound (every number of a large address book, a long call history) still hits for nearly all
 * of it. Thread-safe; [resize] lets a caller that knows its working set (the address book's numbers) make room for it.
 */
class RecentCache<K : Any, V : Any>(capacity: Int) {
    @Volatile var capacity: Int = capacity.coerceAtLeast(1)
        private set

    private val map = object : LinkedHashMap<K, V>(INITIAL, LOAD, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > this@RecentCache.capacity
    }

    operator fun get(key: K): V? = synchronized(map) { map[key] }

    operator fun set(key: K, value: V) {
        synchronized(map) { map[key] = value }
    }

    val size: Int get() = synchronized(map) { map.size }

    /** Makes the cache hold at least [atLeast] entries, up to [ceiling]; it never shrinks here. */
    fun resize(atLeast: Int, ceiling: Int = Int.MAX_VALUE) {
        val next = atLeast.coerceAtMost(ceiling)
        if (next > capacity) synchronized(map) { capacity = next }
    }

    fun clear() = synchronized(map) { map.clear() }

    private companion object {
        const val INITIAL = 256
        const val LOAD = 0.75f
    }
}
