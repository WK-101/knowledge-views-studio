package app.parley.common.people

/**
 * A relationship status read from a contact's relations, since Android and vCard have no field for one: "Married to
 * Sam" from a spouse (wife, husband), "Partner of Sam" from a partner or domestic partner. The page's header shows
 * those; an ex-spouse is "Formerly married to Sam", said only on the relation's own row, never in the header.
 */
object RelationshipStatus {
    enum class Kind { MARRIED, PARTNER, FORMERLY_MARRIED }

    /** The status a relation of type [typeKey] ([RelationTypes] key) says, or null for any other relation. */
    fun kindOf(typeKey: String?): Kind? = when (typeKey) {
        "spouse", "wife", "husband" -> Kind.MARRIED
        "partner", "domestic-partner" -> Kind.PARTNER
        "ex-spouse", "ex-wife", "ex-husband" -> Kind.FORMERLY_MARRIED
        else -> null
    }

    /**
     * The header's status lines from [items] (the contact's relations, and those shown from other contacts): married
     * first, then partners, each person once, in the order the relations are kept; former spouses and nameless rows
     * are left out.
     */
    fun <T> header(items: List<T>, row: (T) -> RelationMirror.Row): List<Pair<Kind, T>> {
        val seen = HashSet<String>()
        val found = items.mapNotNull { item ->
            val r = row(item)
            val kind = kindOf(r.typeKey)?.takeIf { it != Kind.FORMERLY_MARRIED } ?: return@mapNotNull null
            val name = RelationLinks.nameKey(r.name)
            if (name.isEmpty()) null else Triple(kind, name, item)
        }
        return found.sortedBy { it.first.ordinal }.filter { seen.add(it.second) }.map { it.first to it.third }
    }
}
