package app.parley.common.people

/**
 * Relations a device contact keeps in Parley only. Picking a private contact as the relation of an address-book
 * contact would write the private contact's name into that contact's Relation row, which its account syncs and any
 * app that reads contacts can see. "Keep it in Parley only" stores the relation here instead, beside the contact's
 * other Parley data (keyed by its lookup key, sealed at rest, carried by Parley's encrypted backups). Its link to the
 * private contact is a [RelationLinks.Link] like any other, by the relation's name.
 */
object ParleyRelations {
    /** One relation: the related person's name, and the Android Relation type and custom label it would have. */
    data class Entry(val name: String, val type: Int, val label: String? = null)

    /** One relation per line: name, type and label, tab-separated; null when there are none. */
    fun encode(entries: List<Entry>): String? = entries.filter { it.name.isNotBlank() }
        .joinToString("\n") { e -> listOf(RelationLinks.esc(e.name.trim()), e.type.toString(), RelationLinks.esc(e.label.orEmpty())).joinToString("\t") }
        .ifEmpty { null }

    fun decode(s: String?): List<Entry> {
        if (s.isNullOrEmpty()) return emptyList()
        return s.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 3) return@mapNotNull null
            val name = RelationLinks.unesc(p[0]).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(name, p[1].toIntOrNull() ?: 0, RelationLinks.unesc(p[2]).ifEmpty { null })
        }
    }

    /** Both lists in one, [into]'s first; the same name and type is kept once (two rows merged on a re-key). */
    fun merge(into: String?, from: String?): String? {
        val out = LinkedHashMap<Pair<String, Int>, Entry>()
        (decode(into) + decode(from)).forEach { e -> out.putIfAbsent(RelationLinks.nameKey(e.name) to e.type, e) }
        return encode(out.values.toList())
    }
}
