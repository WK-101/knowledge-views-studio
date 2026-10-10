package app.parley.common.blocking

import app.parley.common.AppSettings
import app.parley.common.BlockAction
import app.parley.common.OffHours
import app.parley.common.OffHoursAllow
import app.parley.common.Schedule
import app.parley.common.ScreeningSettings
import app.parley.common.TraceMark
import app.parley.common.TraceStep

/**
 * The quick setups of Blocking & screening: situations, not mechanisms. Each changes a few switches; [current] names
 * the ones the switches match now, so the screen can say "You're on: Only people I know".
 */
enum class ScreeningPreset {
    KNOWN, TELEMARKETERS, NIGHTS, EVERYONE;

    /**
     * The settings with this setup applied. "Only people I know" silences strangers at all times, so it replaces a
     * schedule for unknown callers, unless [keepSchedule] (the confirm dialog says so and offers to keep it).
     */
    fun apply(a: AppSettings, keepSchedule: Boolean = false): AppSettings = when (this) {
        KNOWN -> a.copy(
            repeatCallerRingsThrough = true,
            screening = a.screening.copy(
                blockNonContacts = true, nonContactsSchedule = if (keepSchedule) a.screening.nonContactsSchedule else null,
                blockHidden = true, defaultAction = BlockAction.SILENCE,
                allowDialled = true, allowAnswered = true,
            ),
        )
        TELEMARKETERS -> a.copy(screening = a.screening.copy(blockFailedVerification = true, blockInvalid = true, blockNonContacts = false))
        NIGHTS -> a.copy(
            screening = a.screening.copy(
                offHours = OffHours(enabled = true, schedule = Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60), allow = OffHoursAllow.CONTACTS),
            ),
        )
        EVERYONE -> a.copy(
            screening = a.screening.copy(
                blockNonContacts = false, blockHidden = false, blockInvalid = false, blockFailedVerification = false,
                blockNeighbourSpoofing = false, offHours = a.screening.offHours.copy(enabled = false),
            ),
        )
    }

    /** The schedule for unknown callers this setup would remove from [s] (null: none), for the confirm dialog. */
    fun removedSchedule(s: ScreeningSettings): Schedule? = if (this == KNOWN) s.nonContactsSchedule else null

    companion object {
        /**
         * The setups [s] matches, in display order; empty when the switches are a mix of their own. "Only people I
         * know" covers "Stop telemarketers" (strangers are silenced anyway), and "Quiet nights" adds to either.
         */
        fun current(s: ScreeningSettings): List<ScreeningPreset> {
            val known = s.blockNonContacts && s.nonContactsSchedule == null
            val nothingOn = !s.blockNonContacts && !s.blockHidden && !s.blockInvalid && !s.blockFailedVerification &&
                !s.blockNeighbourSpoofing && !s.offHours.enabled
            return buildList {
                if (known) add(KNOWN)
                if (!known && s.blockFailedVerification && s.blockInvalid) add(TELEMARKETERS)
                if (s.offHours.enabled) add(NIGHTS)
                if (nothingOn) add(EVERYONE)
            }
        }
    }
}

/**
 * One stopped call from the screening log: when, from which number, silenced or declined, and whether a contact.
 * [person] names the contact (its key) when known, so one person calling from two numbers counts once.
 */
data class StoppedCall(val time: Long, val number: String?, val silenced: Boolean, val fromContact: Boolean, val person: String? = null)

/** The weekly line under the presets: "12 calls silenced · 0 contacts affected". */
data class ScreeningWeek(val silenced: Int, val declined: Int, val contactsAffected: Int) {
    val stopped: Int get() = silenced + declined
}

object ScreeningWeekly {
    const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    /** The trace step [app.parley.common.CallPolicy] writes when it checks for a contact ("Contact?: yes"). */
    const val CONTACT_CHECK = "Contact?"

    /** Whether a stopped call's trace shows the caller was a contact (a label rule or off hours stopped them). */
    fun fromContact(trace: List<TraceStep>): Boolean = trace.any { it.check == CONTACT_CHECK && it.mark == TraceMark.MATCH }

    /**
     * The last seven days up to [now]. Contacts are counted once each, however often they called; calls logged
     * before traces existed count as strangers, which is what they were screened as.
     */
    fun summarize(calls: List<StoppedCall>, now: Long): ScreeningWeek {
        val week = calls.filter { it.time in (now - WEEK_MS)..now }
        val contacts = week.filter { it.fromContact }.map { it.person ?: it.number.orEmpty() }.distinct().size
        return ScreeningWeek(week.count { it.silenced }, week.count { !it.silenced }, contacts)
    }
}
