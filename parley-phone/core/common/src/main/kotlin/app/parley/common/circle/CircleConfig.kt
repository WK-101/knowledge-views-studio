package app.parley.common.circle

import app.parley.common.Codecs
import kotlinx.serialization.Serializable

/** How keep-in-touch reminders arrive. */
enum class ReminderDelivery {
    /** One Sunday notification with up to three people (the default). */
    WEEKLY_DIGEST,

    /** One notification per person as they come due, at most [CircleConfig.weeklyCap] a week. */
    AS_DUE,
}

/**
 * Circle settings, kept in their own small store like the v3.1 call switches so they don't touch
 * the main settings document. The on/off switches for reminders stay in [app.parley.common.AppSettings]
 * (`birthdayReminders`, `reachOutNudges`).
 */
@Serializable
data class CircleConfig(
    val delivery: ReminderDelivery = ReminderDelivery.WEEKLY_DIGEST,
    /** AS_DUE: at most this many keep-in-touch notifications a week. */
    val weeklyCap: Int = 3,
    /** Also remind this many days before a date (0 = only on the day). */
    val dateLeadDays: Int = 0,
    /** "Log this?" per channel; channels not listed ask. */
    val logModes: Map<InteractionChannel, LogMode> = emptyMap(),
    /** The Circle section at the top of Favourites is folded. */
    val favoritesSectionCollapsed: Boolean = false,
    /** "Suggested for your Circle" was dismissed. */
    val suggestionsDismissed: Boolean = false,
    /** The People card in Insights ([PeopleCardChoice] with [firstMover]). */
    val peopleCard: Boolean = true,
    /** "who usually reaches out first" on the People card (private; can be hidden). */
    val firstMover: Boolean = true,
    /** "Anything to remember?" after calls with contacts (opt-in). */
    val memoryPrompt: Boolean = false,
    /**
     * The older "Notes on the lock screen" switch. Folded into Privacy › Caller on the lock screen ("Name and notes")
     * at the next start ([app.parley.common.calls.LockScreenCaller.folded]) and then off; read nowhere else. Kept so
     * an older backup's choice is folded the same way.
     */
    val memoryOnLockScreen: Boolean = false,
    /** The pre-call peek before calling from a contact's page. */
    val preCallPeek: Boolean = true,
) {
    fun logMode(channel: InteractionChannel): LogMode = logModes[channel] ?: LogMode.ASK

    fun withLogMode(channel: InteractionChannel, mode: LogMode): CircleConfig = copy(logModes = logModes + (channel to mode))

    companion object {
        val CAP_CHOICES = listOf(1, 2, 3, 5, 7)
        val LEAD_CHOICES = listOf(0, 1, 3, 7)

        private val json = Codecs.full

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

/**
 * Settings › Recents & history › People card, one choice where there were two switches: the card off, on, or on with
 * "who usually reaches out first". The card's own ⋮ changes the same two values.
 */
enum class PeopleCardChoice {
    OFF, ON, ON_WITH_FIRST_MOVER;

    /** [c] with this choice. Off keeps "who reaches out first" as it was, for when the card comes back. */
    fun applyTo(c: CircleConfig): CircleConfig = when (this) {
        OFF -> c.copy(peopleCard = false)
        ON -> c.copy(peopleCard = true, firstMover = false)
        ON_WITH_FIRST_MOVER -> c.copy(peopleCard = true, firstMover = true)
    }

    companion object {
        fun of(c: CircleConfig): PeopleCardChoice = when {
            !c.peopleCard -> OFF
            c.firstMover -> ON_WITH_FIRST_MOVER
            else -> ON
        }
    }
}
