package app.parley.common.people

/**
 * Labels of private contacts (docs/CONTACT_MODEL.md). A label is still the address book's group (its name and id are
 * the group's); only *who is in it* is kept by Parley, sealed in the private contact's vault entry, so no other app can
 * see that a private contact exists or which labels it has.
 *
 * A membership keeps the group's id and its title: the id follows renames made anywhere, and the title finds the label
 * again when the id no longer exists (a restore on another phone, a label deleted and made again). Labels are one per
 * title across accounts (as the Contacts tab and every rule name them), so a membership resolves to the first group
 * with that title, its canonical id.
 */
object PrivateLabels {
    data class Membership(val groupId: Long, val title: String)

    /** A label group as the address book has it (id and title). */
    data class Group(val id: Long, val title: String)

    private fun key(title: String) = title.trim()

    /** The canonical group id of each label title: the first group with it. */
    fun canonical(groups: List<Group>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        groups.forEach { g -> if (key(g.title).isNotEmpty()) out.putIfAbsent(key(g.title), g.id) }
        return out
    }

    /**
     * [stored] as the labels are now: each membership's current title (by id, else by its stored title) with that
     * title's canonical id, once per title. A membership whose label can't be found is left out (see [unresolved]).
     * With no groups at all (the address book can't be read) the stored titles are trusted as they are.
     */
    fun resolve(stored: List<Membership>, groups: List<Group>): List<Membership> {
        if (groups.isEmpty()) return stored.distinctBy { key(it.title) }.map { it.copy(title = key(it.title)) }
        val byId = groups.associateBy { it.id }
        val canon = canonical(groups)
        return stored.mapNotNull { m ->
            val title = byId[m.groupId]?.title?.let(::key) ?: key(m.title).takeIf { it in canon } ?: return@mapNotNull null
            canon[title]?.let { Membership(it, title) }
        }.distinctBy { it.title }
    }

    /** Memberships whose label isn't there now: kept as they are, since nothing that shows labels could have removed them. */
    fun unresolved(stored: List<Membership>, groups: List<Group>): List<Membership> {
        if (groups.isEmpty()) return emptyList()
        val ids = groups.map { it.id }.toSet()
        val titles = canonical(groups).keys
        return stored.filter { it.groupId !in ids && key(it.title) !in titles }
    }

    /** Titles of the labels [stored] names now. */
    fun titles(stored: List<Membership>, groups: List<Group>): Set<String> = resolve(stored, groups).map { it.title }.toSet()

    /** Canonical group ids of [stored] (what the editor's label chips select). */
    fun ids(stored: List<Membership>, groups: List<Group>): Set<Long> = resolve(stored, groups).map { it.groupId }.toSet()

    /**
     * What to store after an edit that selected the groups [ids] (the editor, which lists one chip per title): those
     * labels, plus [previous] memberships the editor couldn't show.
     */
    fun fromIds(ids: Set<Long>, groups: List<Group>, previous: List<Membership>): List<Membership> {
        val byId = groups.associateBy { it.id }
        val chosen = ids.mapNotNull { id -> byId[id]?.let { Membership(id, key(it.title)) } }
        // Ids the listing doesn't know (unreadable right now): keep the earlier membership for them.
        val unknown = ids.filter { it !in byId }.mapNotNull { id -> previous.firstOrNull { it.groupId == id } }
        return (chosen + unknown + unresolved(previous, groups)).distinctBy { key(it.title) }
    }

    /**
     * [stored] (the memberships as they are now) after an edit that started from other ones and selected the groups
     * [added] and unselected [removed]: only those changes are applied, so a label added or removed elsewhere meanwhile
     * stays as it is.
     */
    fun edited(stored: List<Membership>, added: Set<Long>, removed: Set<Long>, groups: List<Group>): List<Membership> {
        val byId = groups.associateBy { it.id }
        var out = stored
        removed.forEach { id -> byId[id]?.let { g -> out = remove(out, g.title, groups) } ?: run { out = out.filterNot { it.groupId == id } } }
        added.forEach { id -> byId[id]?.let { g -> out = add(out, g) } }
        return out
    }

    fun add(stored: List<Membership>, group: Group): List<Membership> =
        if (stored.any { it.groupId == group.id || key(it.title) == key(group.title) }) stored else stored + Membership(group.id, key(group.title))

    fun remove(stored: List<Membership>, title: String, groups: List<Group>): List<Membership> {
        val ids = groups.filter { key(it.title) == key(title) }.map { it.id }.toSet()
        return stored.filterNot { key(it.title) == key(title) || it.groupId in ids }
    }

    /**
     * After labels were renamed or merged in Parley ([moves]: old title → new title; the new label's canonical id is
     * [targetIds]'s): memberships of the old titles move to the new ones, never twice.
     */
    fun renamed(stored: List<Membership>, moves: Map<String, String>, targetIds: Map<String, Long>): List<Membership> {
        if (moves.isEmpty()) return stored
        val norm = moves.entries.associate { key(it.key) to key(it.value) }
        return stored.map { m ->
            val to = norm[key(m.title)] ?: return@map m
            Membership(targetIds[to] ?: m.groupId, to)
        }.distinctBy { key(it.title) }
    }

    /** Members per label title over every private contact ([all]: vault id → titles). */
    fun counts(all: Map<Long, Set<String>>): Map<String, Int> {
        val out = HashMap<String, Int>()
        all.values.forEach { ts -> ts.forEach { t -> out[t] = (out[t] ?: 0) + 1 } }
        return out
    }
}
