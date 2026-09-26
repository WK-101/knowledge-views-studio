package app.parley.common.circle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** R4: how keep-in-touch reminders arrive. */
enum class ReminderDelivery {
    /** One Sunday notification with up to three people (the default). */
    WEEKLY_DIGEST,

    /** One notification per person as they come due, at most [CircleConfig.weeklyCap] a week. */
    AS_DUE,
}

/**
 * Circle settings (R1, R3, R4, R5), kept in their own small store like the v3.1 call switches so they don't touch
 * the main settings document. The on/off switches for reminders stay in [app.parley.common.AppSettings]
 * (`birthdayReminders`, `reachOutNudges`).
 */
@Serializable
data class CircleConfig(
    val delivery: ReminderDelivery = ReminderDelivery.WEEKLY_DIGEST,
    /** AS_DUE: at most this many keep-in-touch notifications a week. */
    val weeklyCap: Int = 3,
    /** R5: also remind this many days before a date (0 = only on the day). */
    val dateLeadDays: Int = 0,
    /** R3: "Log this?" per channel; channels not listed ask. */
    val logModes: Map<InteractionChannel, LogMode> = emptyMap(),
    /** R1: the Circle section at the top of Favourites is folded. */
    val favoritesSectionCollapsed: Boolean = false,
    /** R1: "Suggested from your calls" was dismissed. */
    val suggestionsDismissed: Boolean = false,
    /** R6: the People card in Insights. */
    val peopleCard: Boolean = true,
    /** R6: "who usually reaches out first" on the People card (private; can be hidden). */
    val firstMover: Boolean = true,
    /** R8: "Anything to remember?" after calls with contacts (opt-in). */
    val memoryPrompt: Boolean = false,
    /** R8: the last note and open promises also on the incoming-call screen while the phone is locked. */
    val memoryOnLockScreen: Boolean = false,
    /** R8/R9/X1: the pre-call peek before calling from a contact's page. */
    val preCallPeek: Boolean = true,
) {
    fun logMode(channel: InteractionChannel): LogMode = logModes[channel] ?: LogMode.ASK

    fun withLogMode(channel: InteractionChannel, mode: LogMode): CircleConfig = copy(logModes = logModes + (channel to mode))

    companion object {
        val CAP_CHOICES = listOf(1, 2, 3, 5, 7)
        val LEAD_CHOICES = listOf(0, 1, 3, 7)

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String?): CircleConfig = if (text.isNullOrBlank()) {
            CircleConfig()
        } else {
            try {
                json.decodeFromString(serializer(), text).normalised()
            } catch (_: Exception) {
                CircleConfig()
            }
        }

        fun encode(c: CircleConfig): String = json.encodeToString(serializer(), c)

        private fun CircleConfig.normalised() = copy(
            weeklyCap = weeklyCap.takeIf { it in CAP_CHOICES } ?: 3,
            dateLeadDays = dateLeadDays.takeIf { it in LEAD_CHOICES } ?: 0,
        )
    }
}
