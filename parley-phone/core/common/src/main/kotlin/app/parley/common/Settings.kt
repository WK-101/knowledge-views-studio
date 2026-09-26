package app.parley.common

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ListDensity { COMFORTABLE, COMPACT }

/** How an incoming call is answered. SWIPE protects against pocket answers; TAP is easiest to use. */
enum class AnswerGesture { SWIPE, TAP }

/** Home tabs. New tabs are added at the end; [NavTabs] keeps them hidden until the user shows them (U6). */
enum class StartTab { FAVORITES, RECENTS, CONTACTS, KEYPAD, CIRCLE }

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
    /** Show message and call buttons on contact rows. */
    val contactRowActions: Boolean = false,
    /** Order and visibility of the home tabs (bottom bar and navigation rail). */
    val navTabs: NavTabs = NavTabs(),
    /** P8: grouped (as before), every call on its own row, or one row per number per day. */
    val recentsLayout: app.parley.common.calls.RecentsLayout = app.parley.common.calls.RecentsLayout.GROUPED,
    /** R4 (v3.3): rich call rows (shapes, tints, sequence dots, Call back pill) or the simple icons. */
    val recentsStyle: app.parley.common.ux.RecentsStyle = app.parley.common.ux.RecentsStyle.RICH,
    /** S1/S2 (v3.3): optional combined surfaces (keypad in Recents, favourites in Contacts) and the Recents row tap. */
    val surfaces: SurfaceLayout = SurfaceLayout(),
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
