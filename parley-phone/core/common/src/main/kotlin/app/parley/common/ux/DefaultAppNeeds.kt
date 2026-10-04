package app.parley.common.ux

/**
 * The features that need Parley as the default phone app, said in place (on the feature's own row or screen) with one
 * "Make Parley the default" action, rather than only in the setup guide. docs/CALL_SCREEN_DESIGN.md lists where each
 * one is said.
 */
enum class DefaultAppFeature(
    /** Parley's call screening role is enough: blocking rules work while Parley screens calls for another phone app. */
    val screeningIsEnough: Boolean = false,
) {
    /** The voicemail inbox: Android lets only the default phone app read voicemail. */
    VOICEMAIL,

    /** Parley's call screen and everything on it (notes, scam check, hold mode, the helper, speaker by default). */
    CALL_SCREEN,

    /** A private contact's ringtone and "Send to voicemail": only Parley's own ringer knows the private name. */
    PRIVATE_CALLER,

    /** Call facts and quality ("why it rang", dropped calls), which Parley notes while it runs the call. */
    CALL_FACTS,

    /** A block rule limited to one SIM: only the phone app sees which SIM a call came in on. */
    SIM_RULES,

    /** A block Parley writes as its own rule when it can't use Android's blocked list. */
    BLOCKING(screeningIsEnough = true),
}

object DefaultAppNeeds {
    /** Whether [feature]'s note shows: it needs a role Parley doesn't hold right now. */
    fun noteShown(feature: DefaultAppFeature, isDefault: Boolean, isScreener: Boolean): Boolean =
        !isDefault && !(feature.screeningIsEnough && isScreener)
}
