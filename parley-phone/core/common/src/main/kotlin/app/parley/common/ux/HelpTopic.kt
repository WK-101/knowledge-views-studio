package app.parley.common.ux

/**
 * Help & troubleshooting: short task pages for what people ask a phone app about, each ending in the one place that
 * fixes it ([target]: a screen or a setting, opened as Tools opens it). Nothing here is a setting of its own. The
 * pages are in the order Help lists them: the call itself first, then contacts, then everything else. A few
 * [explainsFeature] pages also say what a feature is, for Tools rows whose feature lives on each label or contact.
 */
enum class HelpTopic(val target: CapabilityTarget?, val explainsFeature: Boolean = false) {
    /** "Parley's call screen doesn't show": Parley isn't the default phone app. */
    CALL_SCREEN(CapabilityTarget.Setting("default_dialer")),

    /** "A call didn't ring": Test a call says which rule did it, and replays last week. */
    DID_NOT_RING(CapabilityTarget.Screen(AppScreen.TEST_A_CALL)),

    /** "Calls are quiet while a Situation is on": Situations set who may ring. */
    SITUATION_QUIET(CapabilityTarget.Setting("situations")),

    /** "I'm waiting for a call from a number I don't know": Expecting a call. */
    EXPECTING(CapabilityTarget.Setting("expecting_call")),

    /** "Notifications for missed calls don't show": Android's notification settings for Parley. */
    NOTIFICATIONS(CapabilityTarget.Setting("notification_settings")),

    /** "Reminders, backups or Situations come late": battery optimisation. */
    LATE(CapabilityTarget.Setting("battery")),

    /** "A contact disappeared after Archive": the Archived list. */
    ARCHIVED(CapabilityTarget.Screen(AppScreen.ARCHIVED)),

    /** "I deleted or changed a contact by mistake": History & undo. */
    UNDO(CapabilityTarget.Screen(AppScreen.HISTORY_UNDO)),

    /** "I'm moving to a new phone": Backups (restore on the new one). */
    NEW_PHONE(CapabilityTarget.Screen(AppScreen.BACKUP)),

    /** "Something else isn't working": Diagnostics, with nothing personal in it. */
    SOMETHING_ELSE(CapabilityTarget.Setting("diagnostics")),

    /** Chapters: set on a label's page. */
    CHAPTERS(CapabilityTarget.Screen(AppScreen.LABELS), explainsFeature = true),

    /** To talk about: added on a contact's or a number's page, so there is no one place to open. */
    TALK_ABOUT(null, explainsFeature = true),

    /** The family spam shield: on each shared label's page. */
    FAMILY_SHIELD(CapabilityTarget.Screen(AppScreen.SHARED_LABELS), explainsFeature = true),
    ;

    /** A stable name for routes and saved state. */
    val key: String get() = name.lowercase()

    companion object {
        /** The pages Help lists (the feature pages are opened from their Tools rows). */
        val troubleshooting: List<HelpTopic> get() = entries.filterNot { it.explainsFeature }

        fun of(key: String): HelpTopic? = entries.firstOrNull { it.key == key }
    }
}
