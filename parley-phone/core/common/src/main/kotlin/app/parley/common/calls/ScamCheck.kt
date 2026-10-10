package app.parley.common.calls

/**
 * "Is this a scam?": a calm, offline checklist for a call from someone who isn't saved (or a saved organisation that
 * never called before), with the ways out Parley already has (Check it's really them, the family safe word, hanging up
 * to call the official number, Block, Report).
 * Parley can't hear the call, so it never says whether a call *is* a scam; it lists what scammers usually ask for.
 */
object ScamCheck {
    /** The warning signs, in the order the sheet lists them. */
    enum class Sign {
        /** "Act now", threats, keeping you on the line. */
        PRESSURE,

        /** Gift cards, crypto, wire transfers or payment apps. */
        UNUSUAL_PAYMENT,

        /** Asks for a code sent to your phone, a PIN or a password. */
        CODES,

        /** Wants you to install an app or give remote access. */
        REMOTE_ACCESS,

        /** "Your bank" asking you to move money to a "safe account". */
        SAFE_ACCOUNT,

        /** A family member in trouble who needs money now, often from a new number. */
        FAMILY_EMERGENCY,
    }

    val SIGNS: List<Sign> = Sign.entries

    /**
     * Offered under More for a live call from someone who isn't a contact or a private contact (a hidden number too),
     * once the caller lookup has finished, and for a saved organisation whose number never called you before
     * ([neverCallsYou], see [NeverCallsYou]): a faked caller ID shows a saved name. Never for an emergency call or a
     * conference.
     */
    @Suppress("LongParameterList") // One argument per fact the rule weighs.
    fun offered(
        live: Boolean,
        savedCaller: Boolean,
        lookedUp: Boolean,
        hidden: Boolean,
        emergency: Boolean,
        conference: Boolean,
        neverCallsYou: Boolean = false,
    ): Boolean = live && (neverCallsYou || !savedCaller && (lookedUp || hidden)) && !emergency && !conference

    /** The safe-word line shows when a family safe word is set (its question shows only on the safe-word card). */
    fun safeWordReminder(safeWordSet: Boolean, emergency: Boolean): Boolean = safeWordSet && !emergency
}

/**
 * The "Text me your name" reply for numbers that aren't saved (Settings › Messaging › Quick replies): first in the
 * decline-with-message list and on the post-call card, so an unknown caller who matters can say who they are. It is
 * sent through the carrier's reply-with-message or opened in the messaging app for the user to send; Parley never
 * sends a text itself.
 */
object NameReply {
    /** The reply to offer, or null: blank (turned off), a saved caller, or no number to text. */
    fun offered(text: String, savedCaller: Boolean, hasNumber: Boolean, emergency: Boolean): String? =
        text.trim().takeIf { it.isNotEmpty() && !savedCaller && hasNumber && !emergency }

    /** The decline-with-message list: the name reply first for an unknown caller, each text once. */
    fun replies(quickReplies: List<String>, nameReply: String?): List<String> =
        (listOfNotNull(nameReply) + quickReplies).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
