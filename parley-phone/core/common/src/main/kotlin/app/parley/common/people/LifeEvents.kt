package app.parley.common.people

import app.parley.common.EventDate
import java.time.LocalDate

/**
 * "Date of death" support. Android has no event type for it, so Parley stores it as a custom event
 * (TYPE_CUSTOM = 0) labelled [DEATH_LABEL]; that travels through vCard (X-ABDATE + label), CSV, backups
 * and every sync account unchanged. Other spellings people use are recognised too.
 */
object LifeEvents {
    const val DEATH_LABEL = "Date of death"
    private const val TYPE_CUSTOM = 0
    private const val TYPE_BIRTHDAY = 3

    private val deathWords = setOf(
        "date of death", "death", "died", "deathdate", "death date", "passed away", "deceased", "rip",
        "todestag", "sterbedatum", "décès", "deces", "date de décès", "fallecimiento", "defunción", "morte", "overleden",
    )

    fun isDeath(type: Int, label: String?): Boolean =
        type == TYPE_CUSTOM && label != null && label.trim().lowercase().trimEnd('.', ':') in deathWords

    /** The contact's events as (type, label, date) → the parsed death date, if any. */
    fun deathDate(events: List<Triple<Int, String?, String>>): EventDate? =
        events.firstOrNull { isDeath(it.first, it.second) }?.let { EventDate.parse(it.third) }

    /**
     * Whether a birthday reminder should fire. Never for someone who has died: the date stays visible
     * ("would have turned N") but nobody gets a "turns 80 today!" notification.
     */
    fun remindBirthday(type: Int, deceased: Boolean): Boolean = !(deceased && type == TYPE_BIRTHDAY)

    /** "Would have turned N" on their next birthday, for someone who has died; null without a birth year. */
    fun wouldHaveTurned(birth: EventDate, today: LocalDate): Int? = birth.turning(today)

    /** Age at death, when both years are known. */
    fun ageAtDeath(birth: EventDate, death: EventDate): Int? {
        val by = birth.year ?: return null
        val dy = death.year ?: return null
        val before = death.month < birth.month || (death.month == birth.month && death.day < birth.day)
        return (dy - by - if (before) 1 else 0).takeIf { it >= 0 }
    }
}
