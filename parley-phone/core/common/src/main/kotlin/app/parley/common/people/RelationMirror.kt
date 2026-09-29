package app.parley.common.people

/**
 * Two-way relations. "Mother: Ana" on Sam means Ana is Sam's mother, so Ana's contact gets "Child: Sam". [inverse]
 * is the type the other contact gets; [plan] decides what to write there after Sam was saved, touching only rows that
 * Parley itself added and that are still exactly as it left them (a relation the user wrote or changed on Ana is
 * never removed or rewritten).
 */
object RelationMirror {
    /** What is known about someone's gender. Android contacts have no such field, so it is usually [UNKNOWN]. */
    enum class Gender { UNKNOWN, FEMALE, MALE }

    /**
     * One relation row as Parley compares it: the name shown, and the type ([typeKey] of [RelationTypes], or null
     * with the stored custom [label] for a type Parley doesn't know).
     */
    data class Row(val name: String, val typeKey: String?, val label: String?) {
        /** The same relation, whatever the name's case or spacing. */
        fun sameAs(o: Row): Boolean = RelationLinks.nameKey(name) == RelationLinks.nameKey(o.name) && typeKey == o.typeKey &&
            (typeKey != null || label.orEmpty().trim().equals(o.label.orEmpty().trim(), ignoreCase = true))
    }

    /** Neutral words first, then the words to use when [Gender] is known. */
    private data class Inv(val neutral: String, val female: String? = null, val male: String? = null)

    private fun same(k: String) = Inv(k)

    /**
     * Keys of [RelationTypes] and what the other person is then. A key missing here has no fair opposite (a doctor's
     * patient, someone met, a muse), so nothing is written for it.
     */
    private val inverses: Map<String, Inv> = mapOf(
        "spouse" to same("spouse"),
        "wife" to Inv("spouse", "wife", "husband"),
        "husband" to Inv("spouse", "wife", "husband"),
        "partner" to same("partner"),
        "domestic-partner" to same("domestic-partner"),
        "girlfriend" to Inv("partner", "girlfriend", "boyfriend"),
        "boyfriend" to Inv("partner", "girlfriend", "boyfriend"),
        "fiance" to same("fiance"),
        "ex-partner" to same("ex-partner"),
        "date" to same("date"),
        "sweetheart" to same("sweetheart"),
        "mother" to Inv("child", "daughter", "son"),
        "father" to Inv("child", "daughter", "son"),
        "parent" to Inv("child", "daughter", "son"),
        "child" to Inv("parent", "mother", "father"),
        "son" to Inv("parent", "mother", "father"),
        "daughter" to Inv("parent", "mother", "father"),
        "sibling" to Inv("sibling", "sister", "brother"),
        "sister" to Inv("sibling", "sister", "brother"),
        "brother" to Inv("sibling", "sister", "brother"),
        "grandparent" to Inv("grandchild", "granddaughter", "grandson"),
        "grandmother" to Inv("grandchild", "granddaughter", "grandson"),
        "grandfather" to Inv("grandchild", "granddaughter", "grandson"),
        "grandchild" to Inv("grandparent", "grandmother", "grandfather"),
        "grandson" to Inv("grandparent", "grandmother", "grandfather"),
        "granddaughter" to Inv("grandparent", "grandmother", "grandfather"),
        // No neutral word for these in the list: "Relative" is true without guessing a gender.
        "aunt" to Inv("relative", "niece", "nephew"),
        "uncle" to Inv("relative", "niece", "nephew"),
        "niece" to Inv("relative", "aunt", "uncle"),
        "nephew" to Inv("relative", "aunt", "uncle"),
        "stepmother" to Inv("relative", "stepdaughter", "stepson"),
        "stepfather" to Inv("relative", "stepdaughter", "stepson"),
        "stepson" to Inv("relative", "stepmother", "stepfather"),
        "stepdaughter" to Inv("relative", "stepmother", "stepfather"),
        "stepsister" to Inv("relative", "stepsister", "stepbrother"),
        "stepbrother" to Inv("relative", "stepsister", "stepbrother"),
        "mother-in-law" to Inv("relative", "daughter-in-law", "son-in-law"),
        "father-in-law" to Inv("relative", "daughter-in-law", "son-in-law"),
        "son-in-law" to Inv("relative", "mother-in-law", "father-in-law"),
        "daughter-in-law" to Inv("relative", "mother-in-law", "father-in-law"),
        "sister-in-law" to Inv("relative", "sister-in-law", "brother-in-law"),
        "brother-in-law" to Inv("relative", "sister-in-law", "brother-in-law"),
        "cousin" to same("cousin"),
        "relative" to same("relative"),
        "kin" to same("kin"),
        "godparent" to same("godchild"),
        "godchild" to same("godparent"),
        "friend" to same("friend"),
        "contact" to same("contact"),
        "acquaintance" to same("acquaintance"),
        "co-resident" to same("co-resident"),
        "roommate" to same("roommate"),
        "neighbor" to same("neighbor"),
        "classmate" to same("classmate"),
        "teacher" to same("student"),
        "student" to same("teacher"),
        "co-worker" to same("co-worker"),
        "colleague" to same("colleague"),
        // Android's own pair, so other apps show both sides in their language.
        "manager" to same("assistant"),
        "assistant" to same("manager"),
        "boss" to same("employee"),
        "employee" to same("boss"),
        "client" to same("supplier"),
        "supplier" to same("client"),
        "agent" to same("client"),
        "landlord" to same("tenant"),
        "tenant" to same("landlord"),
    )

    /**
     * The type the other contact gets when this contact has a relation of type [t] to them; [selfGender] is this
     * contact's gender (son or daughter only when it is known). Null: no fair opposite.
     */
    fun inverse(t: RelationType, selfGender: Gender = Gender.UNKNOWN): RelationType? {
        val inv = inverses[t.key] ?: return null
        val key = when (selfGender) {
            Gender.FEMALE -> inv.female ?: inv.neutral
            Gender.MALE -> inv.male ?: inv.neutral
            Gender.UNKNOWN -> inv.neutral
        }
        return RelationTypes.byKey(key)
    }

    /**
     * The row the other contact should have for this contact's relation [typeKey]/[label]: named [selfName], with
     * the inverse type, or the same custom label mirrored when Parley doesn't know the type. Null: nothing to write.
     */
    fun reciprocal(typeKey: String?, label: String?, selfName: String, selfGender: Gender = Gender.UNKNOWN): Row? {
        if (selfName.isBlank()) return null
        if (typeKey == null) {
            val l = label?.trim().orEmpty()
            return if (l.isEmpty()) null else Row(selfName, null, l)
        }
        val t = RelationTypes.byKey(typeKey) ?: return null
        val inv = inverse(t, selfGender) ?: return null
        return Row(selfName, inv.key, null)
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
