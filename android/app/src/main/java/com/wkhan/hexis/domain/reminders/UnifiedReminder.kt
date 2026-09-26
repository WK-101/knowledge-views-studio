package com.wkhan.hexis.domain.reminders

/**
 * W3 (cross-module unification) — the ONE reminder MODEL the storage layer lacks.
 *
 * The audit found the app models a scheduled reminder six different ways, one per domain: the task
 * `ReminderEntity` (a typed multi-mode row), habit `reminderTimes` (a CSV of minutes-from-midnight),
 * event `alertsMinutes` (a CSV of minutes-before), note `reminderAt` + `reminderExtra` (a nullable epoch
 * plus a CSV of epochs), routine `whenReminderMin` (a nullable int in settings-JSON), and occasion
 * `prepLeadDays` / `keepInTouchDays` (day counts). Only the picker presets were ever shared; there was no
 * common way to *read* or reason about "every reminder in the app."
 *
 * [UnifiedReminder] is that common lens: a pure, source-agnostic value each storage shape normalizes into,
 * so any surface can list, sort, or count reminders across all domains without knowing each format. This is
 * the verifiable half of "one reminder model" — unifying the STORAGE into a single table is a separate,
 * migration-bearing step (which needs the instrumented MigrationTest to land safely). The normalizers below
 * are pure functions over the raw stored values, fully covered by UnifiedReminderTest.
 */
enum class ReminderSource { TASK, HABIT, EVENT, NOTE, ROUTINE, OCCASION }

/** How a reminder's fire time is expressed at its source (the shapes genuinely differ). */
enum class ReminderTiming {
    ABSOLUTE,      // a specific instant (note reminderAt/extra, absolute task reminder)
    TIME_OF_DAY,   // minutes from midnight, on the item's schedule days (habit, routine)
    BEFORE_EVENT,  // minutes before the event/occasion it hangs off (event alert, occasion prep)
    RELATIVE,      // offset relative to a task's due / start / deadline
}

/** One reminder, normalized out of whatever shape its domain happens to store it in. */
data class UnifiedReminder(
    val source: ReminderSource,
    val sourceId: String,           // the owning task / habit / event / … id
    val label: String,              // the owning item's title, for display
    val timing: ReminderTiming,
    val minuteOfDay: Int? = null,   // set for TIME_OF_DAY (0..1439)
    val minutesBefore: Int? = null, // set for BEFORE_EVENT / RELATIVE
    val atMillis: Long? = null,     // set for ABSOLUTE
    val detail: String = "",        // source-specific extra (rrule, task mode, "prep", …)
)

object UnifiedReminders {

    private fun csvInts(csv: String): List<Int> = csv.split(',').mapNotNull { it.trim().toIntOrNull() }
    private fun csvLongs(csv: String): List<Long> = csv.split(',').mapNotNull { it.trim().toLongOrNull() }

    private const val MINS_PER_DAY = 24 * 60

    /** Habit reminders: a CSV of minutes-from-midnight → one TIME_OF_DAY reminder each (deduped, in range). */
    fun fromHabit(habitId: String, name: String, reminderTimesCsv: String): List<UnifiedReminder> =
        csvInts(reminderTimesCsv).filter { it in 0..1439 }.distinct().sorted().map {
            UnifiedReminder(ReminderSource.HABIT, habitId, name, ReminderTiming.TIME_OF_DAY, minuteOfDay = it)
        }

    /** Event alerts: a CSV of minutes-before → one BEFORE_EVENT reminder each (deduped, non-negative). */
    fun fromEvent(eventId: String, title: String, alertsMinutesCsv: String): List<UnifiedReminder> =
        csvInts(alertsMinutesCsv).filter { it >= 0 }.distinct().sorted().map {
            UnifiedReminder(ReminderSource.EVENT, eventId, title, ReminderTiming.BEFORE_EVENT, minutesBefore = it)
        }

    /** Routine daily nudge: a nullable minutes-from-midnight → 0 or 1 TIME_OF_DAY reminder. */
    fun fromRoutine(routineId: String, name: String, whenReminderMin: Int?): List<UnifiedReminder> =
        whenReminderMin?.takeIf { it in 0..1439 }?.let {
            listOf(UnifiedReminder(ReminderSource.ROUTINE, routineId, name, ReminderTiming.TIME_OF_DAY, minuteOfDay = it))
        } ?: emptyList()

    /** Note reminders: the primary `reminderAt` plus a CSV of extra epoch-millis → one ABSOLUTE each. */
    fun fromNote(noteId: String, title: String, reminderAt: Long?, reminderExtraCsv: String, reminderRrule: String?): List<UnifiedReminder> =
        (listOfNotNull(reminderAt) + csvLongs(reminderExtraCsv)).filter { it > 0L }.distinct().sorted().map {
            UnifiedReminder(ReminderSource.NOTE, noteId, title, ReminderTiming.ABSOLUTE, atMillis = it, detail = reminderRrule.orEmpty())
        }

    /** Occasion nudges: a prep lead (days-before) and/or a keep-in-touch cadence → BEFORE_EVENT / TIME_OF_DAY. */
    fun fromOccasion(occasionId: String, title: String, prepLeadDays: Int, keepInTouchDays: Int): List<UnifiedReminder> = buildList {
        if (prepLeadDays > 0)
            add(UnifiedReminder(ReminderSource.OCCASION, occasionId, title, ReminderTiming.BEFORE_EVENT, minutesBefore = prepLeadDays * MINS_PER_DAY, detail = "prep"))
        if (keepInTouchDays > 0)
            add(UnifiedReminder(ReminderSource.OCCASION, occasionId, title, ReminderTiming.TIME_OF_DAY, detail = "keep-in-touch/${keepInTouchDays}d"))
    }

    private val ABSOLUTE_TASK_TYPES = setOf("absolute", "dueDayAt")

    /** Task reminders (the typed `ReminderEntity`): its `type` decides ABSOLUTE vs RELATIVE; raw type kept in detail. */
    fun fromTask(taskId: String, title: String, type: String, atTime: Long?, offsetMin: Int?): UnifiedReminder {
        val absolute = type in ABSOLUTE_TASK_TYPES
        return UnifiedReminder(
            source = ReminderSource.TASK,
            sourceId = taskId,
            label = title,
            timing = if (absolute) ReminderTiming.ABSOLUTE else ReminderTiming.RELATIVE,
            atMillis = atTime.takeIf { absolute },
            minutesBefore = offsetMin.takeIf { !absolute },
            detail = type,
        )
    }

    /**
     * Merge several already-normalized lists into one stable ordering: soonest-of-day first for
     * TIME_OF_DAY, then the rest, grouped by source. A single place any "all reminders" surface can read.
     */
    fun ordered(vararg lists: List<UnifiedReminder>): List<UnifiedReminder> =
        lists.asList().flatten().sortedWith(
            compareBy({ it.source.ordinal }, { it.minuteOfDay ?: Int.MAX_VALUE }, { it.minutesBefore ?: Int.MAX_VALUE }, { it.atMillis ?: Long.MAX_VALUE }, { it.label }),
        )
}
