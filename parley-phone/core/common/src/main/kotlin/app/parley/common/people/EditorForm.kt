package app.parley.common.people

/**
 * The pure rules behind the contact editor: which groups the "Add" chips still offer, where the type selector goes,
 * when Save is enabled, what counts as a change, and gentle format checks. The UI (ContactEditScreen) feeds it
 * plain values so it stays testable here.
 */
object EditorForm {
    /**
     * The editor's groups that can be added or hidden. The first seven are the original optional kinds; the rest joined
     * when the editor started showing only what a contact holds (plus name and phone), so a new contact is short.
     * Names are kept in saved state, so entries are only ever appended.
     */
    enum class Kind { NAME_DETAILS, DATE, ADDRESS, WEBSITE, HANDLE, RELATION, NOTE, PHONE, EMAIL, WORK, LABELS, CALL_BACKGROUND, WHEN_THEY_CALL }

    /** The "Add" chips' order: the commonest kinds first, so the ones people want are visible without scrolling. */
    val chipOrder: List<Kind> = listOf(
        Kind.PHONE, Kind.EMAIL, Kind.WORK, Kind.DATE, Kind.ADDRESS, Kind.NOTE, Kind.WEBSITE, Kind.RELATION, Kind.HANDLE,
        Kind.WHEN_THEY_CALL, Kind.LABELS, Kind.CALL_BACKGROUND, Kind.NAME_DETAILS,
    )

    /** Kinds that hold several rows: their chip stays after the group is shown and adds another row. */
    val repeatable: Set<Kind> = setOf(Kind.PHONE, Kind.EMAIL, Kind.DATE, Kind.ADDRESS, Kind.WEBSITE, Kind.RELATION, Kind.HANDLE)

    /**
     * The "Add" chips, the editor's one add control: every [allowed] kind not on screen yet ([shown]), plus the
     * repeatable ones already shown unless their group still has an empty row to fill ([withBlankRow]).
     */
    fun addChoices(shown: Set<Kind>, withBlankRow: Set<Kind> = emptySet(), allowed: Set<Kind> = Kind.entries.toSet()): List<Kind> =
        chipOrder.filter { it in allowed && (it !in shown || (it in repeatable && it !in withBlankRow)) }

    /**
     * Where a value's type selector goes: inside the field at its end, or under the field only when the field would
     * get too narrow for the value ([fieldWidthDp]) or the font is large ([fontScale] ≥ 1.3).
     */
    fun typeBelow(fieldWidthDp: Float, fontScale: Float): Boolean = fieldWidthDp < MIN_TYPED_FIELD_DP || fontScale >= LARGE_FONT

    /**
     * The kinds "My card" can hold (it's your own card, shared as a QR code or vCard): the fields of [MeCard]. Dates,
     * relations, handles, labels and the call-screen picture belong to other people's contacts.
     */
    val meCardKinds: Set<Kind> = setOf(Kind.PHONE, Kind.EMAIL, Kind.WORK, Kind.ADDRESS, Kind.WEBSITE, Kind.NOTE)

    /**
     * The "Add" chips for My card: [addChoices] within [meCardKinds], and a single address (the card has one address
     * line) once one is on screen ([hasAddress]).
     */
    fun meCardChoices(shown: Set<Kind>, withBlankRow: Set<Kind>, hasAddress: Boolean): List<Kind> =
        addChoices(shown, withBlankRow, if (hasAddress) meCardKinds - Kind.ADDRESS else meCardKinds)

    private const val MIN_TYPED_FIELD_DP = 232f
    private const val LARGE_FONT = 1.3f

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
 * Stable keys for the editor's rows, one list per group, so rows animate in and out and keep their focus when
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
