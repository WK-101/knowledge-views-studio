package app.parley.common.sync.shared

/**
 * The name a joined shared label gets on this phone. Joining never lands in a label that is already here just because
 * the names match: the first sync would share every contact of that label with people who never saw them. A label of
 * that name already here gets a suffix ("Family (shared)"); using an existing label is the user's explicit choice.
 */
object SharedLabelTitles {
    /**
     * [base] when no label in [taken] has that name (labels compare as the address book does: trimmed, exact), else
     * the first of [suffixed] (1, 2, …) that is free.
     */
    fun fresh(base: String, taken: Collection<String>, suffixed: (Int) -> String): String {
        val names = taken.map { it.trim() }.toSet()
        val b = base.trim()
        if (b !in names) return b
        return generateSequence(1) { it + 1 }.map { suffixed(it).trim() }.first { it !in names }
    }
}
