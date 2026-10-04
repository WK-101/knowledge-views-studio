package app.parley.common.people

/**
 * The selection bar's Edit… actions on several contacts, device and private mixed (docs/CONTACT_MODEL.md). They work
 * like the contact page does for one contact of either kind; only moving to another account is for address-book
 * contacts alone, since a private contact lives in Parley rather than in an account.
 */
enum class BulkEdit(val deviceOnly: Boolean = false) {
    ADD_LABEL, REMOVE_LABEL, RINGTONE, SIM,

    /**
     * Copies each one's writable copies into the chosen account and removes them where they were (History & undo keeps
     * the contact as it was); SIM and read-only copies stay where they are ([BulkEdits.moveSplit]).
     */
    MOVE_ACCOUNT(deviceOnly = true),
}

object BulkEdits {
    /**
     * Who an edit changes: [ids] the ones it acts on, [unchanged] the ones already as asked (in the label, in the
     * account…), and how many private contacts it leaves out ([skippedPrivate]).
     */
    data class Plan(val ids: List<Long>, val unchanged: List<Long> = emptyList(), val skippedPrivate: Int = 0)

    /** The plan for [edit] over [selected]; [already] says a contact is already as the edit asks. */
    fun plan(edit: BulkEdit, selected: Collection<Long>, already: (Long) -> Boolean = { false }): Plan {
        val distinct = selected.distinct()
        val (eligible, private) = if (edit.deviceOnly) distinct.partition { !BulkActions.isPrivate(it) } else distinct to emptyList()
        val (same, change) = eligible.partition(already)
        return Plan(change, same, private.size)
    }

    /** For Undo: each changed contact's value before the edit set [after] (the ones that already had it aren't listed). */
    fun <K, V> previous(before: Map<K, V>, after: V): Map<K, V> = before.filterValues { it != after }

    /** The labels the selection can leave: those at least one of them is in, with how many, most first then by title. */
    fun removableLabels(selected: Collection<Long>, labelsOf: (Long) -> Set<String>): List<Pair<String, Int>> =
        selected.distinct().flatMap { labelsOf(it) }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key to it.value }

    /**
     * Joining a label: the ones newly added, which Undo takes out again (members from before stay). Leaving one: the
     * members it removes, which Undo puts back.
     */
    fun labelChange(edit: BulkEdit, selected: Collection<Long>, membersBefore: Set<Long>): List<Long> = when (edit) {
        BulkEdit.ADD_LABEL -> selected.distinct().filter { it !in membersBefore }
        BulkEdit.REMOVE_LABEL -> selected.distinct().filter { it in membersBefore }
        else -> emptyList()
    }

    /**
     * One copy (raw contact) of a contact about to move: whether Parley may write to its account, whether that account
     * is the target already, and whether it is a messenger's own copy (WhatsApp…), which follows its app.
     */
    data class MoveCopy(val rawId: Long, val writable: Boolean, val inTarget: Boolean, val messenger: Boolean = false)

    /** What moving one contact to another account does with its copies. */
    sealed interface MoveSplit {
        /** No copy Parley may write (only on the SIM or in a read-only account): nothing can move, nothing changes. */
        data object NoWritableCopy : MoveSplit

        /** Every writable copy is in the target already: nothing to do. */
        data object AlreadyThere : MoveSplit

        /**
         * [moving] are copied into the target and then removed where they were; [staying] stay as they are (copies in the
         * target already, SIM, read-only and messenger copies) and are kept linked with the new copy. [keptReadOnly]
         * counts the SIM and read-only ones among them, which the user is told about.
         */
        data class Move(val moving: List<Long>, val staying: List<Long>, val keptReadOnly: Int) : MoveSplit
    }

    /**
     * Only writable copies outside the target move. A SIM or read-only copy is never copied (its numbers would be
     * doubled) nor removed, and a copy already in the target is never re-created (that would lose what only its server
     * keeps).
     */
    fun moveSplit(copies: List<MoveCopy>): MoveSplit {
        val distinct = copies.distinctBy { it.rawId }
        if (distinct.none { it.writable }) return MoveSplit.NoWritableCopy
        val (moving, staying) = distinct.partition { it.writable && !it.inTarget }
        if (moving.isEmpty()) return MoveSplit.AlreadyThere
        return MoveSplit.Move(moving.map { it.rawId }, staying.map { it.rawId }, staying.count { !it.writable && !it.messenger })
    }
}
