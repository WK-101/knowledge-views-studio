package app.parley.common

/**
 * Moves rows stored under the old last-9-digits key ([PhoneIdentity.legacyKey]) to [PhoneIdentity.key].
 *
 * The old key alone can't be turned into the new one (the country code is gone), so each old key is resolved through
 * the numbers the phone knows (contacts, call history): when exactly one known line ends in those digits, rows move
 * to its key. When none or several do (two countries sharing the last digits), the row keeps its old key, and every
 * lookup still finds it through [PhoneIdentity.lookupKeys].
 */
object PhoneKeyMigration {

    /** Old key → new key for every old key in [stored] that resolves to exactly one known line. */
    fun plan(stored: Collection<String>, knownNumbers: Iterable<String?>, region: String?): Map<String, String> {
        val wanted = stored.filter { PhoneIdentity.isLegacyKey(it) }.toHashSet()
        if (wanted.isEmpty()) return emptyMap()
        val candidates = HashMap<String, MutableSet<String>>()
        for (n in knownNumbers) {
            val legacy = PhoneIdentity.legacyKey(n)
            if (legacy !in wanted) continue
            val key = PhoneIdentity.key(n, region).ifEmpty { continue }
            candidates.getOrPut(legacy) { HashSet() } += key
        }
        return candidates.mapNotNull { (old, keys) -> keys.singleOrNull()?.let { old to it } }.toMap()
    }

    /**
     * Applies [plan] to a key → value store (preferences written by number). A value whose new key already holds one
     * is dropped in favour of the newer entry, as chosen by [newer]; keys that don't resolve are kept as they are.
     */
    fun <V> rekeyMap(entries: Map<String, V>, plan: Map<String, String>, newer: (V, V) -> V = { current, _ -> current }): Map<String, V> {
        if (plan.isEmpty()) return entries
        val out = LinkedHashMap<String, V>()
        // Entries already under a new key first, so a migrated old entry merges into them.
        for ((k, v) in entries) if (k !in plan) out[k] = v
        for ((k, v) in entries) {
            val to = plan[k] ?: continue
            out[to] = out[to]?.let { newer(it, v) } ?: v
        }
        return out
    }
}
