package app.parley.common.people

/**
 * Two-way relations. "Mother: Ana" on Sam means Ana is Sam's mother, so Ana's contact gets "Child: Sam". [inverse]
 * is the type the other contact gets; [plan] decides what to write there after Sam was saved, touching only rows that
 * Parley itself added and that are still exactly as it left them (a relation the user wrote or changed on Ana is
 * never removed or rewritten).
 */
object RelationMirror {
    /**
     * What is known about someone's gender: only what their contact says in its pronouns ([genderOf]), never a guess
     * from their name. Android contacts have no such field, so it is usually [UNKNOWN] and the neutral word is used.
     */
    enum class Gender { UNKNOWN, FEMALE, MALE }

    private val femaleWords = setOf("she", "her", "hers", "herself")
    private val maleWords = setOf("he", "him", "his", "himself")

    /**
     * The gender a contact's pronouns say: "she/her" is [Gender.FEMALE], "he/him" [Gender.MALE]. Anything else ("they",
     * "she/they", "ze/hir", blank) is [Gender.UNKNOWN], so the other contact gets the neutral word.
     */
    fun genderOf(pronouns: String?): Gender {
        val words = pronouns.orEmpty().lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> Gender.UNKNOWN
            words.all { it in femaleWords } -> Gender.FEMALE
            words.all { it in maleWords } -> Gender.MALE
            else -> Gender.UNKNOWN
        }
    }

    /**
     * One relation row as Parley compares it: the name shown, and the type ([typeKey] of [RelationTypes], or null
     * with the stored custom [label] for a type Parley doesn't know).
     */
    data class Row(val name: String, val typeKey: String?, val label: String?) {
        /** The same relation, whatever the name's case or spacing. */
        fun sameAs(o: Row): Boolean = RelationLinks.nameKey(name) == RelationLinks.nameKey(o.name) && sameType(o)

        /** The same type (a known one, or the same custom label whatever its case). */
        fun sameType(o: Row): Boolean = typeKey == o.typeKey &&
            (typeKey != null || label.orEmpty().trim().equals(o.label.orEmpty().trim(), ignoreCase = true))
    }

    /** The word for the other side: [neutral] while the gender of the one who holds the relation isn't known. */
    private data class Inv(val neutral: String, val female: String = neutral, val male: String = neutral)

    private fun same(k: String) = Inv(k)

    /** A gendered family of words: each of [keys] maps to [inv]. */
    private fun all(inv: Inv, vararg keys: String) = keys.map { it to inv }

    /**
     * Keys of [RelationTypes] and what the other person is then, by the gender of the one who holds the relation
     * ("Wife: Sam" on Alex gives Sam "Husband: Alex" when Alex is he/him, "Wife: Alex" when she/her, "Spouse: Alex"
     * otherwise). A key missing here has no fair opposite (a crush, a muse, an emergency contact), so nothing is said.
     */
    private val inverses: Map<String, Inv> = listOf(
        all(Inv("spouse", "wife", "husband"), "spouse", "wife", "husband"),
        all(Inv("ex-spouse", "ex-wife", "ex-husband"), "ex-spouse", "ex-wife", "ex-husband"),
        all(Inv("partner", "girlfriend", "boyfriend"), "girlfriend", "boyfriend"),
        all(same("partner"), "partner"),
        all(same("domestic-partner"), "domestic-partner"),
        all(same("fiance"), "fiance"),
        all(same("ex-partner"), "ex-partner"),
        all(same("date"), "date"),
        all(same("sweetheart"), "sweetheart"),
        all(Inv("child", "daughter", "son"), "parent", "mother", "father"),
        all(Inv("parent", "mother", "father"), "child", "son", "daughter"),
        all(Inv("sibling", "sister", "brother"), "sibling", "sister", "brother"),
        all(Inv("half-sibling", "half-sister", "half-brother"), "half-sibling", "half-sister", "half-brother"),
        all(Inv("stepchild", "stepdaughter", "stepson"), "stepparent", "stepmother", "stepfather"),
        all(Inv("stepparent", "stepmother", "stepfather"), "stepchild", "stepson", "stepdaughter"),
        all(Inv("stepsibling", "stepsister", "stepbrother"), "stepsibling", "stepsister", "stepbrother"),
        all(Inv("grandchild", "granddaughter", "grandson"), "grandparent", "grandmother", "grandfather"),
        all(Inv("grandparent", "grandmother", "grandfather"), "grandchild", "grandson", "granddaughter"),
        all(Inv("niece-or-nephew", "niece", "nephew"), "aunt-or-uncle", "aunt", "uncle"),
        all(Inv("aunt-or-uncle", "aunt", "uncle"), "niece-or-nephew", "niece", "nephew"),
        all(Inv("child-in-law", "daughter-in-law", "son-in-law"), "parent-in-law", "mother-in-law", "father-in-law"),
        all(Inv("parent-in-law", "mother-in-law", "father-in-law"), "child-in-law", "son-in-law", "daughter-in-law"),
        all(Inv("sibling-in-law", "sister-in-law", "brother-in-law"), "sibling-in-law", "sister-in-law", "brother-in-law"),
        all(Inv("godchild", "goddaughter", "godson"), "godparent", "godmother", "godfather"),
        all(Inv("godparent", "godmother", "godfather"), "godchild", "goddaughter", "godson"),
        all(same("cousin"), "cousin"),
        all(same("relative"), "relative"),
        all(same("kin"), "kin"),
        all(same("ward"), "guardian"),
        all(same("guardian"), "ward"),
        all(same("friend"), "friend"),
        all(same("contact"), "contact"),
        all(same("acquaintance"), "acquaintance"),
        all(same("met"), "met"),
        all(same("co-resident"), "co-resident"),
        all(same("roommate"), "roommate"),
        all(same("neighbor"), "neighbor"),
        all(same("classmate"), "classmate"),
        all(same("student"), "teacher"),
        all(same("teacher"), "student"),
        all(same("mentee"), "mentor"),
        all(same("mentor"), "mentee"),
        all(same("co-worker"), "co-worker"),
        all(same("colleague"), "colleague"),
        // Your manager has you as a direct report; your assistant has you as their manager (Android's own type, so
        // other apps show it in their language).
        all(same("report"), "manager"),
        all(same("report"), "boss"),
        all(same("manager"), "report"),
        all(same("manager"), "assistant"),
        all(same("employee"), "employer"),
        all(same("employer"), "employee"),
        all(same("supplier"), "client"),
        all(same("client"), "supplier"),
        all(same("client"), "agent"),
        all(same("tenant"), "landlord"),
        all(same("landlord"), "tenant"),
        all(same("patient"), "doctor"),
        all(same("doctor"), "patient"),
        all(same("referral"), "referred-by"),
        all(same("referred-by"), "referral"),
        all(same("related"), "related"),
    ).flatten().toMap()

    /**
     * The type the other contact gets when this contact has a relation of type [t] to them; [selfGender] is this
     * contact's gender (son or daughter only when it is known). Null: no fair opposite.
     */
    fun inverse(t: RelationType, selfGender: Gender = Gender.UNKNOWN): RelationType? {
        val inv = inverses[t.key] ?: return null
        val key = when (selfGender) {
            Gender.FEMALE -> inv.female
            Gender.MALE -> inv.male
            Gender.UNKNOWN -> inv.neutral
        }
        return RelationTypes.byKey(key)
    }

    /**
     * The row the other contact should have for this contact's relation [typeKey]/[label]: named [selfName], with
     * the inverse type. A custom label Parley doesn't know says nothing about the other side, so it gets the plain
     * "Related" until the user names it ([Correction]). Null: nothing to say.
     */
    fun reciprocal(typeKey: String?, label: String?, selfName: String, selfGender: Gender = Gender.UNKNOWN): Row? {
        if (selfName.isBlank()) return null
        if (typeKey == null) {
            val l = label?.trim().orEmpty()
            return if (l.isEmpty()) null else Row(selfName, RELATED, null)
        }
        val t = RelationTypes.byKey(typeKey) ?: return null
        val inv = inverse(t, selfGender) ?: return null
        return Row(selfName, inv.key, null)
    }

    /** The type the other side of a custom label gets. */
    const val RELATED = "related"

    // ---- Corrections: the user said what the other side really is.

    /**
     * Contact [from]'s relation shows on contact [to] as [computed] (a type key, or a custom label with a null key);
     * the user changed it there to [corrected]. Remembered by both Parley keys, so the same relation shows (and is
     * written) the corrected way from then on, on either side, until the relation itself changes type.
     */
    data class Correction(val from: String, val to: String, val computed: Row, val corrected: Row)

    /** [row] (what [from]'s relation would show on [to]) as the user corrected it, or [row] itself. */
    fun corrected(row: Row, from: String, to: String, corrections: List<Correction>): Row =
        corrections.firstOrNull { it.from == from && it.to == to && it.computed.sameType(row) }
            ?.let { row.copy(typeKey = it.corrected.typeKey, label = it.corrected.label) } ?: row

    /**
     * [corrections] with [c] remembered (one per pair and computed type); correcting back to what Parley would show
     * anyway forgets it.
     */
    fun remember(corrections: List<Correction>, c: Correction): List<Correction> {
        val rest = corrections.filterNot { it.from == c.from && it.to == c.to && it.computed.sameType(c.computed) }
        return if (c.corrected.sameType(c.computed)) rest else rest + c.copy(computed = c.computed.copy(name = ""), corrected = c.corrected.copy(name = ""))
    }

    fun encodeCorrections(list: List<Correction>): String = list.joinToString("\n") { c ->
        listOf(c.from, c.to, c.computed.typeKey.orEmpty(), c.computed.label.orEmpty(), c.corrected.typeKey.orEmpty(), c.corrected.label.orEmpty())
            .joinToString("\t") { esc(it) }
    }

    fun decodeCorrections(s: String?): List<Correction> {
        if (s.isNullOrEmpty()) return emptyList()
        return s.split('\n').mapNotNull { line ->
            val p = line.split('\t').map(::unesc)
            if (p.size != 6 || p[0].isEmpty() || p[1].isEmpty()) return@mapNotNull null
            Correction(p[0], p[1], Row("", p[2].ifEmpty { null }, p[3].ifEmpty { null }), Row("", p[4].ifEmpty { null }, p[5].ifEmpty { null }))
        }
    }

    /** What to do on one other contact. */
    sealed interface Step {
        val target: String

        /** Add [row]. */
        data class Add(override val target: String, val row: Row) : Step

        /** Replace the row Parley added ([old], still as it left it) with [row]. */
        data class Change(override val target: String, val old: Row, val row: Row) : Step

        /** Remove the row Parley added ([old], still as it left it). */
        data class Remove(override val target: String, val old: Row) : Step
    }

    /**
     * Steps after a contact was saved. [wanted]: other contact (by a stable key) → the row it should now have for this
     * contact. [created]: rows Parley added on other contacts for this one earlier. [rowsOf]: the other contact's
     * current relation rows (null when it can't be written, it is gone, or it is read-only; it is then left alone).
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // One decision table, kept in one place.
    fun plan(wanted: Map<String, Row>, created: Map<String, Row>, rowsOf: (String) -> List<Row>?): List<Step> {
        val out = ArrayList<Step>()
        for ((target, row) in wanted) {
            val rows = rowsOf(target) ?: continue
            val mine = created[target]
            when {
                // Already as wanted (added earlier, or the user wrote it themselves); an older row of Parley's goes.
                rows.any { it.sameAs(row) } -> if (mine != null && !mine.sameAs(row) && rows.any { it.sameAs(mine) }) out += Step.Remove(target, mine)
                mine != null && rows.any { it.sameAs(mine) } -> out += Step.Change(target, mine, row)
                // The user already names this person there in their own words: leave it.
                rows.any { RelationLinks.nameKey(it.name) == RelationLinks.nameKey(row.name) } -> Unit
                mine != null && rows.any { RelationLinks.nameKey(it.name) == RelationLinks.nameKey(mine.name) } -> Unit
                else -> out += Step.Add(target, row)
            }
        }
        for ((target, mine) in created) {
            if (target in wanted) continue
            val rows = rowsOf(target) ?: continue
            if (rows.any { it.sameAs(mine) }) out += Step.Remove(target, mine)
        }
        return out
    }

    /**
     * The record of rows Parley added, after [done] were written: rows it wrote are remembered, removed ones and
     * ones the user has since changed on the other contact are forgotten (they are the user's now).
     */
    fun record(wanted: Map<String, Row>, created: Map<String, Row>, rowsOf: (String) -> List<Row>?, done: List<Step>): Map<String, Row> {
        val out = LinkedHashMap(created)
        for ((target, mine) in created) {
            val rows = rowsOf(target) ?: continue
            if (rows.none { it.sameAs(mine) } && target !in wanted) out.remove(target)
        }
        for (s in done) {
            when (s) {
                is Step.Add -> out[s.target] = s.row
                is Step.Change -> out[s.target] = s.row
                is Step.Remove -> if (out[s.target]?.sameAs(s.old) == true) out.remove(s.target)
            }
        }
        return out
    }

    /** [rows] after [step]: the one changed row edited or dropped in place, or [step]'s row appended. */
    fun <T> apply(step: Step, rows: List<T>, rowOf: (T) -> Row, make: (Row, T?) -> T): List<T> = when (step) {
        is Step.Add -> rows + make(step.row, null)
        is Step.Change -> {
            val i = rows.indexOfFirst { rowOf(it).sameAs(step.old) }
            if (i < 0) rows else rows.toMutableList().also { it[i] = make(step.row, it[i]) }
        }
        is Step.Remove -> {
            val i = rows.indexOfFirst { rowOf(it).sameAs(step.old) }
            if (i < 0) rows else rows.filterIndexed { j, _ -> j != i }
        }
    }

    /** The step that takes [step] back (for Undo), when the other contact still shows what [step] wrote. */
    fun undo(step: Step): Step = when (step) {
        is Step.Add -> Step.Remove(step.target, step.row)
        is Step.Change -> Step.Change(step.target, step.row, step.old)
        is Step.Remove -> Step.Add(step.target, step.old)
    }

    // ---- Relations with a private contact: shown from Parley's own links, never written to the other contact.

    /**
     * Another contact's relation to this one: [ownerKey] (its Parley key) names this contact as [row] on its own
     * contact; [ownerName] is its name as shown; [ownerPrivate]: it is a private contact; [ownerGender]: what its
     * pronouns say ([genderOf]).
     */
    data class Incoming(val ownerKey: String, val ownerName: String, val ownerPrivate: Boolean, val row: Row, val ownerGender: Gender = Gender.UNKNOWN)

    /**
     * A relation shown on this contact's page for [ownerKey]'s relation to it ([row] names the other contact, as the
     * user corrected it); [computed] is what Parley worked out, which a correction is remembered against.
     */
    data class Shown(val ownerKey: String, val row: Row, val computed: Row = row)

    /**
     * The relations a contact's page shows from other contacts' relations to it, where Parley doesn't write the
     * opposite row because one of the two is private (writing it would put a private contact's name, or a link to it,
     * where other apps can read it, or add rows to sealed details nobody is editing). Between two contacts of the
     * address book the opposite row is written instead ([plan]), so those are left out here. [selfPrivate]: this
     * contact is private; [privateShown]: private contacts may be shown (not in discreet mode). [own]: this contact's
     * own rows: a person it already names isn't shown twice. Each other contact once. [selfKey] and [corrections]:
     * what the user said the other side really is ([corrected]).
     */
    fun fromOthers(
        incoming: List<Incoming>,
        own: List<Row>,
        selfPrivate: Boolean,
        privateShown: Boolean,
        selfKey: String = "",
        corrections: List<Correction> = emptyList(),
    ): List<Shown> {
        if (!privateShown) return emptyList()
        val named = own.map { RelationLinks.nameKey(it.name) }.toMutableSet()
        val owners = HashSet<String>()
        return incoming.filter { selfPrivate || it.ownerPrivate }
            .mapNotNull { i ->
                reciprocal(i.row.typeKey, i.row.label, i.ownerName, i.ownerGender)
                    ?.let { Shown(i.ownerKey, corrected(it, i.ownerKey, selfKey, corrections), it) }
            }
            .filter { s -> s.ownerKey !in owners && named.add(RelationLinks.nameKey(s.row.name)) && owners.add(s.ownerKey) }
    }

    // ---- The record of rows Parley added: one line per (this contact, other contact).

    data class Created(val from: String, val fromId: Long, val target: String, val targetId: Long, val row: Row)

    fun encode(list: List<Created>): String = list.joinToString("\n") { c ->
        listOf(c.from, c.fromId.toString(), c.target, c.targetId.toString(), c.row.name, c.row.typeKey.orEmpty(), c.row.label.orEmpty())
            .joinToString("\t") { esc(it) }
    }

    fun decode(s: String?): List<Created> {
        if (s.isNullOrEmpty()) return emptyList()
        return s.split('\n').mapNotNull { line ->
            val p = line.split('\t').map(::unesc)
            if (p.size != 7) return@mapNotNull null
            val fromId = p[1].toLongOrNull() ?: return@mapNotNull null
            val targetId = p[3].toLongOrNull() ?: return@mapNotNull null
            Created(p[0], fromId, p[2], targetId, Row(p[4], p[5].ifEmpty { null }, p[6].ifEmpty { null }))
        }
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
}
