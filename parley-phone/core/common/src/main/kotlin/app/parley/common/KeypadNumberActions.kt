package app.parley.common

/**
 * Where the keypad offers the actions for a typed number, so each one shows in exactly one place.
 *
 * With no contact among the matches, the results area is otherwise empty: the actions are rows there, as in most
 * dialers, with room for a line of explanation and a full-width touch target. With contacts matching, the list is
 * theirs and can run off screen, so the actions sit in the compact chip row at its foot, always in view. The two
 * places never show at the same time.
 */
object KeypadNumberActions {
    enum class Action { MESSAGE_OR_CALL, CREATE_CONTACT, ADD_TO_CONTACT, SAVE_TEMPORARY }

    /** Digits a number needs before offering to save it (shorter is a code or a typo in progress). */
    const val SAVE_MIN_DIGITS = 3

    /** [rows] go at the end of the results list; [chips] in the row above the keypad. At most one is non-empty. */
    data class Placement(val rows: List<Action>, val chips: List<Action>) {
        companion object {
            val NONE = Placement(emptyList(), emptyList())
        }
    }

    /**
     * @param typed what's in the number field.
     * @param textSearch letters typed on a hardware keyboard: a name search, so there's no number to act on.
     * @param serviceCode a USSD or `*#…#` code (see [PhoneIdentity.isServiceCode]): dialled, never saved or messaged.
     * @param contactMatches whether any contact is among the results.
     * @param known whether the typed number is a saved contact's number (nothing to save then).
     */
    fun place(typed: String, textSearch: Boolean, serviceCode: Boolean, contactMatches: Boolean, known: Boolean): Placement {
        val number = typed.trim()
        if (number.isEmpty() || textSearch || serviceCode) return Placement.NONE
        // Most used first: reaching the number, then saving it (a new contact, into one, or for a while).
        val actions = buildList {
            add(Action.MESSAGE_OR_CALL)
            if (!known && number.count { it.isDigit() } >= SAVE_MIN_DIGITS) {
                add(Action.CREATE_CONTACT)
                add(Action.ADD_TO_CONTACT)
                add(Action.SAVE_TEMPORARY)
            }
        }
        return if (contactMatches) Placement(rows = emptyList(), chips = actions) else Placement(rows = actions, chips = emptyList())
    }
}
