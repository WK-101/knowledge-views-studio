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
