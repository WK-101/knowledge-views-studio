package app.parley.common

/** F22: when the keypad may make a sound. */
object KeypadFeedback {
    /**
     * A key tone plays only when Parley's own setting and the system's "Dial pad tones" are on and the phone isn't in
     * silent or vibrate mode (the stock dialer behaves the same way).
     */
    fun playTone(appSetting: Boolean, systemDialpadTones: Boolean, ringerNormal: Boolean): Boolean =
        appSetting && systemDialpadTones && ringerNormal
}

/**
 * S1/K7: which hardware keys the keypad takes before the focused view sees them. On its own the keypad takes them
 * all. Docked under Recents, Enter and Call belong to the keypad only while it's unfolded and focus isn't on a
 * Recents row (a D-pad user pressing Enter on a missed call opens that call, it doesn't recall the last number);
 * digits always go to the keypad, which unfolds to show them.
 */
object KeypadKeys {
    enum class Key { ENTER, CALL, DIGIT }

    fun keypadTakes(key: Key, docked: Boolean, expanded: Boolean, recentsFocused: Boolean): Boolean = when {
        !docked || key == Key.DIGIT -> true
        else -> expanded && !recentsFocused
    }
}
