package app.parley.common.people

/**
 * The bulk step of the lookup-key sweep. One listing of the address book's (lookup key, contact id) pairs answers
 * every stored key the address book still has; only keys it no longer has need the provider's own lookup, which
 * follows keys that changed when contacts were linked, renamed or first synced. That keeps a sweep over hundreds of
 * annotated contacts at one query plus a few lookups instead of two provider calls per key.
 */
object KeySweep {
    /** [direct]: stored key → (contact id, same key). [needLookup]: stored keys the listing doesn't have. */
    data class Split(val direct: Map<String, Pair<Long, String>>, val needLookup: List<String>)

    fun split(stored: Map<String, Long?>, current: Map<String, Long>): Split {
        val direct = LinkedHashMap<String, Pair<Long, String>>()
        val rest = ArrayList<String>()
        for (key in stored.keys) {
            val id = current[key]
            if (id != null) direct[key] = id to key else rest += key
        }
        return Split(direct, rest)
    }

    /**
     * What a sweep's outcome depends on. When neither the address book's pairs nor the stored keys (with the ids
     * they were saved with) changed since the last complete sweep, another one would find nothing to move.
     */
    data class Snapshot(val current: Map<String, Long>, val stored: Map<String, Long?>)
}
