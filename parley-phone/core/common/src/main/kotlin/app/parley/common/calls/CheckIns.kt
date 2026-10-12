package app.parley.common.calls

/**
 * Check-in: Rescue call's second mode ([RescuePlan]). The same call rings at a set time; answering it means "I'm
 * fine". If it isn't answered (declined, rung out, kept from ringing by a real call, or too late), Parley offers one
 * tap to text or call a helper. Parley never sends or dials by itself: the text opens in the messages app, ready for
 * the user to send, and the call is placed only from that tap.
 */
object CheckIns {
    /** A check-in rings later, never "now": the times it offers. */
    val whenChoices: List<RescueWhen> = RescueWhen.entries.filter { it != RescueWhen.NOW }

    /** [choice] as a check-in takes it: "now" becomes the shortest wait. */
    fun whenFor(choice: RescueWhen): RescueWhen = if (choice == RescueWhen.NOW) RescueWhen.IN_15 else choice

    /**
     * Whether the check-in went unconfirmed and the helper should be offered: [answered] true is "I'm fine"; false
     * (declined or rung out) and null (it never rang: a real call was up, or Android delivered the alarm too late)
     * are not.
     */
    fun asksForHelp(answered: Boolean?): Boolean = answered != true

    /** The helper a check-in offers: the one chosen if still a helper, else the first; none when there are none. */
    fun helperFor(helpers: List<Helper>, chosen: String?): Helper? =
        helpers.firstOrNull { chosen != null && it.number == chosen } ?: helpers.firstOrNull()

    /** How [h] is named: a private helper by their number while private contacts are hidden (as a private caller is). */
    fun shownName(h: Helper, hidePrivate: Boolean): String = if (h.private && hidePrivate) h.number else h.name.ifBlank { h.number }

    /** Whether the screen can set it: a check-in needs a helper to offer; a rescue call needs who calls. */
    fun ready(checkIn: Boolean, name: String, number: String?, helper: String?): Boolean =
        if (checkIn) !helper.isNullOrBlank() else name.isNotBlank() || !number.isNullOrBlank()
}
