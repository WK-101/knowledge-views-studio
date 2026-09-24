package app.parley.common.people

/**
 * Which contact a relation ("Spouse: Anna") points to. The Data row keeps only the name, as every other app expects;
 * Parley remembers the related contact's lookup key (and last known id) beside it in its own metadata, so the link
 * survives renames and duplicate names.
 */
object RelationLinks {
    data class Link(val lookupKey: String, val contactId: Long)

    sealed interface Target {
        /** Open this contact. */
        data class Contact(val id: Long) : Target

        /** Several contacts have that name: let the user choose. */
        data class Choose(val ids: List<Long>) : Target

        data object None : Target
    }

    /** Map key for a relation name: trimmed, case-insensitive. */
    fun nameKey(name: String): String = name.trim().lowercase()

    fun encode(links: Map<String, Link>): String =
        links.entries.sortedBy { it.key }.joinToString("\n") { (name, l) -> listOf(esc(name), esc(l.lookupKey), l.contactId.toString()).joinToString("\t") }

    fun decode(s: String?): Map<String, Link> {
        if (s.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Link>()
        s.split('\n').forEach { line ->
            val p = line.split('\t')
            if (p.size != 3) return@forEach
            val id = p[2].toLongOrNull() ?: return@forEach
            out[unesc(p[0])] = Link(unesc(p[1]), id)
        }
        return out
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] })
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Where tapping relation [name] should go. [stored]: the remembered link, if any; [resolve] turns it into the
     * contact's current id (null when that contact is gone). [contacts]: (id, display name) of every contact, for
     * the name fallback. [self] is never offered.
     */
    fun resolve(name: String, stored: Link?, resolve: (Link) -> Long?, contacts: List<Pair<Long, String>>, self: Long? = null): Target {
        stored?.let { l -> resolve(l)?.takeIf { it != self }?.let { return Target.Contact(it) } }
        val key = nameKey(name)
        if (key.isEmpty()) return Target.None
        val matches = contacts.filter { (id, n) -> id != self && nameKey(n) == key }.map { it.first }.distinct()
        return when (matches.size) {
            0 -> Target.None
            1 -> Target.Contact(matches.single())
            else -> Target.Choose(matches)
        }
    }

    /**
     * Links to remember after the relations of a contact were saved: an existing link is kept while its name is still
     * a relation; a new name that matches exactly one contact is linked to it. Names no longer used are dropped.
     * [picked] (I5): contacts chosen with the editor's contact picker, by name key; they win over everything else.
     */
    fun update(
        names: List<String>,
        existing: Map<String, Link>,
        contacts: List<Triple<Long, String, String>>,
        self: Long? = null,
        picked: Map<String, Link> = emptyMap(),
    ): Map<String, Link> {
        val out = LinkedHashMap<String, Link>()
        for (n in names) {
            val k = nameKey(n)
            if (k.isEmpty() || k in out) continue
            picked[k]?.takeIf { it.contactId != self }?.let { out[k] = it; continue }
            existing[k]?.let { out[k] = it; continue }
            val m = contacts.filter { (id, display, _) -> id != self && nameKey(display) == k }
            if (m.size == 1) out[k] = Link(m.single().third, m.single().first)
        }
        return out
    }
}
