package app.parley.common.people

/**
 * I5: relation types. Android has 14 built-in Relation types; vCard 4.0 (RFC 6350 RELATED) adds its own list, and
 * people want the everyday family words too. Each type has a stable English [key] (what's compared and exported,
 * so labels can be translated later without changing stored data) and an English [label].
 *
 * Built-in types are written with their Android TYPE; every other one as TYPE_CUSTOM with the English label, which
 * any contacts app shows as written and which vCard export keeps.
 */
data class RelationType(val key: String, val label: String, val androidType: Int = CUSTOM, val group: Group = Group.OTHER) {
    enum class Group(val title: String) { FAMILY("Family"), PARTNER("Partner"), SOCIAL("Friends and others"), WORK("Work"), OTHER("Other") }

    companion object {
        const val CUSTOM = 0
    }
}

object RelationTypes {
    private fun t(key: String, label: String, group: RelationType.Group, android: Int = RelationType.CUSTOM) = RelationType(key, label, android, group)
    private val F = RelationType.Group.FAMILY
    private val P = RelationType.Group.PARTNER
    private val S = RelationType.Group.SOCIAL
    private val W = RelationType.Group.WORK
    private val O = RelationType.Group.OTHER

    val all: List<RelationType> = listOf(
        // Android's built-in types (ContactsContract.CommonDataKinds.Relation.TYPE_*).
        t("spouse", "Spouse", P, 14),
        t("partner", "Partner", P, 10),
        t("domestic-partner", "Domestic partner", P, 4),
        t("child", "Child", F, 3),
        t("parent", "Parent", F, 9),
        t("mother", "Mother", F, 8),
        t("father", "Father", F, 5),
        t("sister", "Sister", F, 13),
        t("brother", "Brother", F, 2),
        t("relative", "Relative", F, 12),
        t("friend", "Friend", S, 6),
        t("manager", "Manager", W, 7),
        t("assistant", "Assistant", W, 1),
        t("referred-by", "Referred by", O, 11),
        // vCard 4.0 RELATED types (RFC 6350 §6.6.6).
        t("contact", "Contact", S),
        t("acquaintance", "Acquaintance", S),
        t("met", "Met", S),
        t("co-worker", "Co-worker", W),
        t("colleague", "Colleague", W),
        t("co-resident", "Co-resident", S),
        t("neighbor", "Neighbour", S),
        t("sibling", "Sibling", F),
        t("kin", "Kin", F),
        t("muse", "Muse", S),
        t("crush", "Crush", P),
        t("date", "Date", P),
        t("sweetheart", "Sweetheart", P),
        t("me", "Me", O),
        t("agent", "Agent", W),
        t("emergency", "Emergency contact", O),
        // Everyday family and life words.
        t("wife", "Wife", P),
        t("husband", "Husband", P),
        t("fiance", "Fiancé(e)", P),
        t("girlfriend", "Girlfriend", P),
        t("boyfriend", "Boyfriend", P),
        t("ex-partner", "Ex-partner", P),
        t("son", "Son", F),
        t("daughter", "Daughter", F),
        t("grandparent", "Grandparent", F),
        t("grandmother", "Grandmother", F),
        t("grandfather", "Grandfather", F),
        t("grandchild", "Grandchild", F),
        t("grandson", "Grandson", F),
        t("granddaughter", "Granddaughter", F),
        t("aunt", "Aunt", F),
        t("uncle", "Uncle", F),
        t("niece", "Niece", F),
        t("nephew", "Nephew", F),
        t("cousin", "Cousin", F),
        t("stepmother", "Stepmother", F),
        t("stepfather", "Stepfather", F),
        t("stepson", "Stepson", F),
        t("stepdaughter", "Stepdaughter", F),
        t("stepsister", "Stepsister", F),
        t("stepbrother", "Stepbrother", F),
        t("mother-in-law", "Mother-in-law", F),
        t("father-in-law", "Father-in-law", F),
        t("son-in-law", "Son-in-law", F),
        t("daughter-in-law", "Daughter-in-law", F),
        t("sister-in-law", "Sister-in-law", F),
        t("brother-in-law", "Brother-in-law", F),
        t("godparent", "Godparent", F),
        t("godchild", "Godchild", F),
        t("guardian", "Guardian", F),
        t("roommate", "Roommate", S),
        t("classmate", "Classmate", S),
        t("teacher", "Teacher", S),
        t("student", "Student", S),
        t("mentor", "Mentor", W),
        t("boss", "Boss", W),
        t("employee", "Employee", W),
        t("client", "Client", W),
        t("supplier", "Supplier", W),
        t("doctor", "Doctor", O),
        t("caregiver", "Caregiver", O),
        t("babysitter", "Babysitter", O),
        t("landlord", "Landlord", O),
        t("tenant", "Tenant", O),
    )

    private val byKey = all.associateBy { it.key }
    private val byAndroid = all.filter { it.androidType != RelationType.CUSTOM }.associateBy { it.androidType }
    private val byLabel = all.associateBy { norm(it.label) } + all.associateBy { norm(it.key) } +
        mapOf("neighbour" to byKey.getValue("neighbor"), "fiancee" to byKey.getValue("fiance"), "coworker" to byKey.getValue("co-worker"))

    fun byKey(key: String?): RelationType? = key?.let { byKey[it] }

    /** The type of a stored Relation row (TYPE, LABEL), or null for a custom label Parley doesn't know. */
    fun fromAndroid(type: Int, label: String?): RelationType? =
        if (type != RelationType.CUSTOM) byAndroid[type] else label?.let { byLabel[norm(it)] }

    /** TYPE and LABEL to store for [t]. */
    fun toAndroid(t: RelationType): Pair<Int, String?> = if (t.androidType != RelationType.CUSTOM) t.androidType to null else RelationType.CUSTOM to t.label

    /** Search in the picker: by label or key, accent- and case-insensitive. */
    fun search(query: String): List<RelationType> {
        val q = norm(query)
        if (q.isEmpty()) return all
        return all.filter { norm(it.label).contains(q) || norm(it.key).contains(q) }
            .sortedBy { if (norm(it.label).startsWith(q)) 0 else 1 }
    }

    private fun norm(s: String) = app.parley.common.TextSearch.normalize(s.trim()).replace(' ', '-')
}
