package app.parley.common

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ListDensity { COMFORTABLE, COMPACT }

/** How an incoming call is answered. SWIPE protects against pocket answers; TAP is easiest to use. */
enum class AnswerGesture { SWIPE, TAP }

enum class StartTab { FAVORITES, RECENTS, CONTACTS, KEYPAD }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoledBlack: Boolean = false,
    val dynamicColor: Boolean = true,
    val density: ListDensity = ListDensity.COMFORTABLE,
    val answerGesture: AnswerGesture = AnswerGesture.SWIPE,
    val confirmBeforeCall: Boolean = false,
    val dialpadTones: Boolean = true,
    val dialpadHaptics: Boolean = true,
    val startTab: StartTab = StartTab.RECENTS,
    val sortByFirstName: Boolean = true,
    val showSimLabels: Boolean = true,
    val defaultAccountType: String? = null,
    val defaultAccountName: String? = null,
    val quickReplies: List<String> = DEFAULT_QUICK_REPLIES,
    val screening: ScreeningSettings = ScreeningSettings(),
    val onboardingDone: Boolean = false,
    // Security
    val appLock: Boolean = false,
    /** Minutes in the background before the lock returns (0 = immediately). */
    val lockAfterMinutes: Int = 1,
    val secureScreen: Boolean = false,
    /** Vault contacts hidden from lists and search ("discreet mode"). */
    val hideVault: Boolean = false,
    /** Copy vault calls out of the system call log. */
    val privateVaultHistory: Boolean = true,
    // Calls
    /** Ringtone for callers not in contacts (null = same as usual). */
    val unknownRingtone: String? = null,
    /** A blocked unknown number that calls again within 3 minutes is let through. */
    val repeatCallerRingsThrough: Boolean = true,
    // Reminders
    val birthdayReminders: Boolean = true,
    val birthdayReminderHour: Int = 9,
    val reachOutNudges: Boolean = true,
    // Call log
    /** Delete system call-log entries older than N days (0 = keep). */
    val callLogRetentionDays: Int = 0,
) {
    companion object {
        val DEFAULT_QUICK_REPLIES = listOf(
            "Can't talk now. Call me later?",
            "I'll call you right back.",
            "I'm in a meeting.",
            "Can you text me instead?",
        )
    }
}
