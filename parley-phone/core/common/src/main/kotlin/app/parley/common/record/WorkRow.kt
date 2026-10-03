package app.parley.common.record

/**
 * The work row (Organization): which columns Parley edits and which it only keeps, and what a save does to it.
 *
 * Google Contacts, Outlook and vCard imports fill more of the row than company and title: a department, an office
 * location, a job description. A row holding only those used to look blank to the editor and was deleted on the
 * next save. Now a save writes only the columns Parley edits and never deletes a row that holds anything else.
 */
object WorkRow {
    /** Company, job title and department: the columns the editor changes. */
    val EDITED: List<String> = listOf(Col.D1, Col.D4, Col.D5)

    /** Columns a save keeps as they are: label, job description, ticker symbol, phonetic name and office location. */
    val KEPT: List<String> = listOf(Col.D3, Col.D6, Col.D7, Col.D8, Col.D9)

    /** Whether the row holds content in a column Parley doesn't edit, given its [values] by column name. */
    fun holdsOthers(values: Map<String, String?>): Boolean = KEPT.any { !values[it].isNullOrBlank() }

    enum class Action {
        /** Nothing to write. */
        NONE,
        INSERT,
        UPDATE,

        /** Clear the edited columns but keep the row: it still holds other content. */
        CLEAR,
        DELETE,
    }

    /**
     * What a save does: [exists] when the contact has a work row, [editedBlank] when company, title and department are
     * all blank now, [same] when none of them changed, [holdsOthers] when the row holds other content.
     */
    fun action(exists: Boolean, editedBlank: Boolean, same: Boolean, holdsOthers: Boolean): Action = when {
        exists && same -> Action.NONE
        exists && editedBlank -> if (holdsOthers) Action.CLEAR else Action.DELETE
        exists -> Action.UPDATE
        editedBlank -> Action.NONE
        else -> Action.INSERT
    }
}
