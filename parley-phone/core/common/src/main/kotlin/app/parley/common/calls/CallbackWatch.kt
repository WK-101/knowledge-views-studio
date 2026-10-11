package app.parley.common.calls

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** By when "They'll call me back" expects the call. */
enum class CallbackBy { TODAY, TOMORROW, FRIDAY, IN_A_WEEK }

/**
 * Callback watch: "they'll call me back by Friday". From now until the evening of the chosen day the line may ring
 * past "who may ring" (an [ExpectedWindow] with [ExpectedWindow.callback], below rules and lists as every window is),
 * "This number never calls you" stays quiet for it, and a To call item ([ToCallSource.CALLBACK]) waits for that
 * evening: if no call has settled it by then, To call says they haven't called back. Pure: the app stores both.
 */
object CallbackWatch {
    /** "Today" is offered only while at least this many hours of the calling day are left. */
    const val TODAY_HOURS_LEFT = 2

    /** The by-days that make sense now, each with the end of its calling hours ([ExpectedCalls.DAY_END_HOUR]). */
    fun choices(now: Long, zone: ZoneId): List<Pair<CallbackBy, Long>> {
        val here = Instant.ofEpochMilli(now).atZone(zone)
        val today = here.toLocalDate()
        val list = ArrayList<Pair<CallbackBy, Long>>()
        if (here.hour < ExpectedCalls.DAY_END_HOUR - TODAY_HOURS_LEFT) list += CallbackBy.TODAY to endOf(today, zone)
        list += CallbackBy.TOMORROW to endOf(today.plusDays(1), zone)
        // This week's Friday, when it is further than tomorrow (Monday to Wednesday).
        val toFriday = DayOfWeek.FRIDAY.value - today.dayOfWeek.value
        if (toFriday >= 2) list += CallbackBy.FRIDAY to endOf(today.plusDays(toFriday.toLong()), zone)
        list += CallbackBy.IN_A_WEEK to endOf(today.plusDays(DAYS_A_WEEK), zone)
        return list
    }

    private const val DAYS_A_WEEK = 7L

    /** The end of [day]'s calling hours, when the watch closes and the To call line falls due. */
    fun endOf(day: LocalDate, zone: ZoneId): Long = day.atTime(ExpectedCalls.DAY_END_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

    /**
     * The watch's window for [number] (its To call line [key]) from [now] until [by]. [anyCaller] lets numbers nobody
     * saved ring too; [label] names who is expected, [privateName] when that is a private contact's name.
     */
    @Suppress("LongParameterList") // One window's fields, named at the call.
    fun window(number: String, key: String, now: Long, by: Long, anyCaller: Boolean, label: String?, privateName: Boolean): ExpectedWindow =
        ExpectedWindow(
            start = now, end = maxOf(by, now + 1), source = ExpectedSource.TO_CALL, key = key, label = label, number = number,
            privateName = privateName && label != null, callback = true, anyCaller = anyCaller,
        )
}
