package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * R38 — the DEDICATED CALENDAR layer. A real calendar has EVENTS, not tasks: an event reserves time,
 * has a place, and is never "completed" or rolled over. Fully offline; all fields are part of the
 * lossless backup. No calendar-provider access, no network.
 */

/** A local, colour-coded calendar the user can toggle on/off (Work · Personal · Fitness…). */
@Serializable
@Entity(tableName = "event_calendars")
@androidx.compose.runtime.Immutable
data class EventCalendarEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Long,
    val visible: Boolean = true,
    val orderIndex: Int = 0,
    val isDefault: Boolean = false,
    val workspaceId: String = "default",
    val createdAt: Long,
)

/**
 * A calendar event. Times are epoch millis. An all-day event uses local-midnight bounds and [allDay].
 * A "floating" event keeps the same wall-clock time in any timezone (a 9am workout stays 9am if you
 * travel). Recurrence uses our own compact rule (domain/recurrence/Recurrence.kt); [exDates] skip
 * instances (comma of epoch-days) and a per-instance edit is a separate event carrying [recurrenceParentId].
 */
@Serializable
@Entity(tableName = "events", indices = [Index("calendarId"), Index("startMillis"), Index("recurrenceParentId")])
data class EventEntity(
    @PrimaryKey val id: String,
    val calendarId: String,
    val title: String,
    val location: String = "",
    val notes: String = "",
    val url: String = "",
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val floating: Boolean = false,        // same clock time in every timezone
    val timezone: String = "",            // TZID; blank = device zone
    val colorArgb: Long? = null,          // per-event override; null = use the calendar's colour
    val rrule: String = "",               // Recurrence.encode format; blank = one-off
    val exDates: String = "",             // comma of epoch-days to skip
    val recurrenceParentId: String? = null,   // set on a per-instance override event
    val recurrenceDate: Long = 0,         // epoch-day of the overridden instance
    val alertsMinutes: String = "",       // comma of minutes-before for alerts (e.g. "10,1440")
    val busy: Boolean = true,             // counts toward busy/free (an "available" event is transparent)
    val linkedTaskId: String? = null,     // a time block created from a task
    // R52 — invitations. A meeting invite (Teams/Meet/Zoom…) is just an event with a join link (in [url]),
    // an organizer and attendees, plus your RSVP. The conferencing provider is derived from the URL host.
    val organizer: String = "",           // "Name" or "Name <email>"
    val attendees: String = "",           // newline- or comma-separated names/emails
    val rsvp: String = "",                // "" | "yes" | "maybe" | "no"
    // R53 — invite lifecycle. [uid] is the iCalendar UID (stable across updates); [sequence] is the
    // revision. A re-imported invite with a matching uid UPDATES in place; a higher sequence supersedes;
    // METHOD:CANCEL removes it. Our own events leave uid blank (their [id] is identity enough).
    val uid: String = "",
    val sequence: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun durationMin(): Long = ((endMillis - startMillis) / 60000L).coerceAtLeast(0)
}
