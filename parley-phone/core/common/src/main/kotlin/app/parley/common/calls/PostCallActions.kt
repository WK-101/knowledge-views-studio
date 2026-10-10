package app.parley.common.calls

/**
 * The post-call card for a number that isn't saved: a few large buttons for what most people do right after such a
 * call (save it, be reminded to call back, block it), the rest under one More. The card closes itself a moment after
 * the call, so it shows what matters first instead of ten buttons of equal weight.
 */
object PostCallActions {
    enum class Action {
        /** Save, which opens New contact · Add to a contact · Privately for 7 days. */
        SAVE,
        REMIND_ME,
        BLOCK,
        UNBLOCK,
        MESSAGE_OR_CALL_ON,
        REPORT,
        ASK_NAME,
        SCAM_CHECK,
        CALL_SAVED_NUMBER,
    }

    data class Facts(
        /** The number is blocked already: Unblock takes Block's place. */
        val blocked: Boolean = false,
        /** An emergency service: never blocked or reported. */
        val emergency: Boolean = false,
        /** "Text me your name" is set, so Ask their name can be offered. */
        val nameReply: Boolean = false,
        /**
         * The call matched a scam signal (screening warned about the number, your calls tag it as a sales line) or
         * the caller claimed to be an organisation or a relative (Is this a scam? or Check it's really them was opened
         * during the call): Was it a scam? and Call a saved number come forward.
         */
        val suspicious: Boolean = false,
    )

    data class Layout(val primary: List<Action>, val more: List<Action>)

    fun layout(f: Facts): Layout {
        val block = if (f.emergency) null else if (f.blocked) Action.UNBLOCK else Action.BLOCK
        val safety = listOf(Action.SCAM_CHECK, Action.CALL_SAVED_NUMBER)
        val primary = listOfNotNull(Action.SAVE, Action.REMIND_ME, block) + if (f.suspicious) safety else emptyList()
        val more = listOfNotNull(
            Action.MESSAGE_OR_CALL_ON,
            Action.ASK_NAME.takeIf { f.nameReply },
            Action.REPORT.takeIf { !f.emergency },
        ) + if (f.suspicious) emptyList() else safety
        return Layout(primary, more)
    }
}
