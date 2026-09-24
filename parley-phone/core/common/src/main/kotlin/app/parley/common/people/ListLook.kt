package app.parley.common.people

import app.parley.common.Initials

/** U4: what a swipe on a contact or Recents row does. [DELETE] always comes with an Undo. */
enum class SwipeAction(val label: String) {
    NONE("Nothing"),
    CALL("Call"),
    MESSAGE("Message"),
    MESSAGE_ON("Message on…"),
    BLOCK("Block"),
    DELETE("Delete (with undo)"),
    ;

    companion object {
        fun parse(s: String?, default: SwipeAction): SwipeAction = entries.firstOrNull { it.name == s } ?: default
    }
}

/** U4 settings: off by default; right swipe calls and left swipe messages once turned on. */
data class SwipeConfig(val enabled: Boolean = false, val right: SwipeAction = SwipeAction.CALL, val left: SwipeAction = SwipeAction.MESSAGE) {
    /** The action for a swipe towards the end (right in left-to-right languages) or the start. */
    fun action(towardsEnd: Boolean): SwipeAction = if (!enabled) SwipeAction.NONE else if (towardsEnd) right else left

    /** Actions a row can offer: a row without a number can only be deleted; Recents rows can't be blocked twice… */
    fun available(action: SwipeAction, hasNumber: Boolean, canDelete: Boolean): Boolean = when (action) {
        SwipeAction.NONE -> false
        SwipeAction.CALL, SwipeAction.MESSAGE, SwipeAction.MESSAGE_ON, SwipeAction.BLOCK -> hasNumber
        SwipeAction.DELETE -> canDelete
    }
}

/** U6: how avatars without a photo look. */
enum class AvatarStyle(val label: String) {
    COLOURFUL("Colourful letters"),
    GREY("Grey monogram"),
}

object AvatarText {
    /**
     * U6 emoji-as-avatar: a name that starts with an emoji ("🐶 Rex", "🏠 Home") shows that emoji instead of
     * letters. Returns the first grapheme when it is an emoji (flags, skin tones and ZWJ sequences included).
     */
    fun leadingEmoji(name: String): String? {
        val t = name.trim()
        if (t.isEmpty()) return null
        val g = Initials.firstGrapheme(t)
        val cp = g.codePointAt(0)
        return g.takeIf { isEmoji(cp) }
    }

    private fun isEmoji(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || // pictographs, emoticons, transport, flags (regional indicators), supplemental
            cp in 0x2600..0x27BF || // misc symbols and dingbats
            cp in 0x2B00..0x2BFF || // stars, arrows used as emoji
            cp in 0x1F1E6..0x1F1FF ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 ||
            cp in 0x2194..0x21AA || cp in 0x231A..0x23FF || cp in 0x25AA..0x25FE || cp == 0x3030 || cp == 0x303D
}
