package app.parley.common

import app.parley.common.calls.RecentsLayout
import app.parley.common.calls.LockScreenCaller
import app.parley.common.ux.CallScreenBackground
import app.parley.common.ux.RecentsStyle

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ListDensity { COMFORTABLE, COMPACT }

/** How an incoming call is answered. SWIPE protects against pocket answers; TAP is easiest to use. */
enum class AnswerGesture { SWIPE, TAP }

/** Home tabs. New tabs are added at the end; [NavTabs] keeps them hidden until the user shows them. */
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
    /** "Show names as": last name first ("Jones, Robert"); apart from [sortByFirstName], as in Android's Contacts. */
    val showNamesLastFirst: Boolean = false,
    val showSimLabels: Boolean = true,
    val defaultAccountType: String? = null,
    val defaultAccountName: String? = null,
    val quickReplies: List<String> = DEFAULT_QUICK_REPLIES,
    /**
     * The reply offered first to numbers that aren't saved (declining with a message, the post-call card): asks them
     * to text their name. Edited with the quick replies; blank: not offered.
     */
    val nameReply: String = DEFAULT_NAME_REPLY,
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
    /** Delete call history older than N days (0 = keep): Parley's archive, and the system call log when [callLogRetentionChosen]. */
    val callLogRetentionDays: Int = 0,
    /** The user picked [callLogRetentionDays] themselves (a default never trims the system call log). */
    val callLogRetentionChosen: Boolean = false,
    /** Show message and call buttons on contact rows. */
    val contactRowActions: Boolean = false,
    /** A relation with another saved contact is added to that contact too, with the opposite type. */
    val mirrorRelations: Boolean = true,
    /** A temporary contact whose time is up waits for your "Delete" (one notification asks); off: deleted at once. */
    val askBeforeDeletingTemporary: Boolean = true,
    /** Order and visibility of the home tabs (bottom bar and navigation rail). */
    val navTabs: NavTabs = NavTabs(),
    /** Grouped (as before), every call on its own row, or one row per number per day. */
    val recentsLayout: RecentsLayout = RecentsLayout.GROUPED,
    /** Rich call rows (shapes, tints, sequence dots, Call back pill) or the simple icons. */
    val recentsStyle: RecentsStyle = RecentsStyle.RICH,
    /** Optional combined surfaces (keypad in Recents, favourites in Contacts) and the Recents row tap. */
    val surfaces: SurfaceLayout = SurfaceLayout(),
    /** The call screen's background: the caller's colour, or the theme's plain background. */
    val callBackground: CallScreenBackground = CallScreenBackground.CALLER_COLOUR,
    /** How much of a caller's name call notifications and the call screen show while the phone is locked. */
    val lockScreenCaller: LockScreenCaller = LockScreenCaller.NAME,
    /** Recents opens on the filter chip used last (see [app.parley.common.calls.RecentsCallers.restored]). */
    val rememberRecentsFilter: Boolean = true,
    /** The Recents filter chip used last, by name ("UNKNOWN"); empty for All. */
    val recentsFilter: String = "",
    /** Show contact photos (and call-screen pictures) on the call screen; a contact can override it either way. */
    val showCallerPhoto: Boolean = true,
    /** Ask to switch answered calls to RTT (real-time text) where the SIM supports it. */
    val answerWithRtt: Boolean = false,
    /**
     * Keep the name the mobile network shows for a caller after the call, for Recents, the number's page and under a
     * saved name ([app.parley.common.calls.NetworkName]). Off by default, also for a phone that kept names before
     * the setting existed (those stay put until the user turns it on or deletes them).
     */
    val rememberNetworkNames: Boolean = false,
    /**
     * Never stored. Set only while a duress unlock's hiding is on (see [app.parley.common.security.DuressPolicy]): the
     * safety switches as the settings screens show them, while [hideVault] above is forced on for everything else.
     */
    val duress: DuressView? = null,
) {
    /**
     * These settings with the older "Notes on the lock screen" switch ([notesSwitch]) folded into "Caller on the lock
     * screen" ([LockScreenCaller.folded]).
     */
    fun withLockScreenNotes(notesSwitch: Boolean): AppSettings = copy(lockScreenCaller = LockScreenCaller.folded(lockScreenCaller, notesSwitch))

    companion object {
        val DEFAULT_QUICK_REPLIES = listOf(
            "Can't talk now. Call me later?",
            "I'll call you right back.",
            "I'm in a meeting.",
            "Can you text me instead?",
        )

        const val DEFAULT_NAME_REPLY = "Sorry, I don't answer unknown numbers. Please text me your name and why you're calling."
    }
}

/** What the Privacy page shows for the switches a duress unlock overrides (the values as the user left them). */
data class DuressView(val hideVault: Boolean, val privateVaultHistory: Boolean)
