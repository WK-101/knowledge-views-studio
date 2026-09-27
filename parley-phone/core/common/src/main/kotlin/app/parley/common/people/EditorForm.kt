package app.parley.common.people

/**
 * E1 (v3.4 editor): the pure rules behind the contact editor: which optional groups "Add more info" still offers,
 * when Save is enabled, what counts as a change, and gentle format checks. The UI (ContactEditScreen) feeds it
 * plain values so it stays testable here.
 */
object EditorForm {
    /** Optional groups, in the order the "Add more info" sheet lists them. Phones and e-mails are always shown. */
    enum class Kind { NAME_DETAILS, DATE, ADDRESS, WEBSITE, HANDLE, RELATION, NOTE }

    /** The kinds the sheet still offers: every optional kind not on screen yet ([shown]), in sheet order. */
    fun addable(shown: Set<Kind>, allowed: Set<Kind> = Kind.entries.toSet()): List<Kind> =
        Kind.entries.filter { it in allowed && it !in shown }

    /**
     * Rows that count when comparing the draft with where editing started: a row that was never saved ([isNew])
     * and is still blank adds nothing, so adding an empty "Phone" and removing it again isn't a change.
     */
    fun <T> meaningful(rows: List<T>, isNew: (T) -> Boolean, isBlank: (T) -> Boolean): List<T> =
        rows.filterNot { isNew(it) && isBlank(it) }

    /** Whether anything was typed at all ([values]: every text of the draft). */
    fun hasContent(values: Iterable<String>): Boolean = values.any { it.isNotBlank() }

    /**
     * Save is enabled for a new contact once it holds something, and for an existing one once something really
     * changed (a new contact prefilled from a number can be saved straight away). Never while saving.
     */
    fun canSave(isNew: Boolean, changed: Boolean, hasContent: Boolean, saving: Boolean): Boolean = when {
        saving -> false
        isNew -> hasContent
        else -> changed
    }

    private val email = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s.]+$")

    /** A gentle hint, never a blocker: the text doesn't look like an e-mail address. Blank is fine. */
    fun emailLooksWrong(value: String): Boolean {
        val v = value.trim()
        return v.isNotEmpty() && !email.matches(v)
    }

    /**
     * A gentle hint: a "number" without any digit, or an e-mail typed into the number field. Letters alone are
     * fine (1-800-FLOWERS, "ext"), so is dial punctuation (pauses, *, #).
     */
    fun phoneLooksWrong(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        return '@' in v || v.none { it.isDigit() }
    }
}

/**
 * E1: stable keys for the editor's rows, one list per group, so rows animate in and out and keep their focus when
 * another row of the group is added or removed. Keys are never reused.
 */
class RowKeys {
    private var next = 1L
    private val groups = HashMap<String, MutableList<Long>>()

    /** The keys of [group]'s [size] rows; grows or shrinks at the end when the list changed elsewhere. */
    fun keys(group: String, size: Int): List<Long> {
        val l = groups.getOrPut(group) { ArrayList() }
        while (l.size < size) l += next++
        while (l.size > size) l.removeAt(l.lastIndex)
        return l.toList()
    }

    /** A row appended to [group], which had [sizeBefore] rows; returns its key (to move the focus there). */
    fun added(group: String, sizeBefore: Int): Long {
        keys(group, sizeBefore)
        val k = next++
        groups.getValue(group) += k
        return k
    }

    /** Row [index] of [group] was removed: the rows after it keep their keys. */
    fun removed(group: String, index: Int) {
        val l = groups[group] ?: return
        if (index in l.indices) l.removeAt(index)
    }
}
