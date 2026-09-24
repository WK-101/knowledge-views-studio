package app.parley.common

/** Finds likely duplicate contacts by shared phone number, e-mail, or identical normalised name. */
object Duplicates {

    fun find(contacts: List<ContactSummary>): List<List<ContactSummary>> {
        val parent = IntArray(contacts.size) { it }
        fun root(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun union(a: Int, b: Int) { parent[root(a)] = root(b) }

        val byKey = HashMap<String, Int>()
        contacts.forEachIndexed { i, c ->
            val keys = buildList {
                nameKey(c.displayName)?.let { add("n:$it") }
                c.phones.forEach { p -> PhoneNumbers.matchKey(p.number).takeIf { it.length >= 7 }?.let { add("p:$it") } }
                c.emails.forEach { add("e:" + it.trim().lowercase()) }
            }
            for (k in keys) {
                val prev = byKey.putIfAbsent(k, i)
                if (prev != null) union(prev, i)
            }
        }
        return contacts.indices.groupBy { root(it) }.values
            .filter { it.size > 1 }
            .map { idx -> idx.map { contacts[it] } }
            .sortedBy { it.first().displayName.lowercase() }
    }

    /** Word-order-insensitive, accent-insensitive name key. Single-word names are too ambiguous. */
    fun nameKey(name: String): String? {
        val words = TextSearch.normalize(name).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        return words.sorted().joinToString(" ")
    }
}
