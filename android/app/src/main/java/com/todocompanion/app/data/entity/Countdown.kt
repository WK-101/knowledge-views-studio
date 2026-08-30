package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * R43 — "Occasions": a countdown grew up into a life-events model (inspired by the open-source Birday,
 * but going beyond it because this app owns the tasks + calendar too). One row still counts down to a
 * date, but now it can also be a yearly BIRTHDAY / ANNIVERSARY / MEMORIAL / NAME_DAY / HOLIDAY that
 * recurs every year, shows the person's AGE (or the anniversary's Nth year), the next occurrence, the
 * zodiac sign, and — uniquely for a unified app — can auto-spawn a "prepare / buy a gift" task a few
 * days before. Entirely offline; part of the lossless backup. All new columns are additive with safe
 * defaults so old backups round-trip unchanged.
 */
@Serializable
@Entity(tableName = "countdowns")
data class CountdownEntity(
    @PrimaryKey val id: String,
    val title: String,
    val targetMillis: Long,      // the origin/target date (start of day, local); for yearly events this is
                                 // the ORIGINAL date (e.g. the birth date) — the next occurrence is computed.
    val emoji: String? = null,
    val colorArgb: Long? = null,
    val pinned: Boolean = false, // surfaced on the widget
    val sortOrder: Double = 0.0,
    val createdAt: Long,
    // R43 life-events fields ---------------------------------------------------------------------
    val eventType: String = "COUNTDOWN", // one of LifeEvent.EventType names
    val yearly: Boolean = false,          // recurs every year (birthdays, anniversaries, name days…)
    val yearKnown: Boolean = true,        // false = the year is unknown / not counted, so age is hidden
    val personName: String = "",          // optional "whose" — "Sara", "Mum & Dad"
    val notes: String = "",
    val prepLeadDays: Int = 0,            // >0 = auto-create a prep task this many days before each occurrence
    val prepTaskId: String? = null,       // the id of the auto-created prep task, so we can keep it in sync
    // R45 "beyond countdowns" fields --------------------------------------------------------------
    val countUp: Boolean = false,         // count UP from the date ("time since") instead of down
    val unit: String = "days",            // display/milestone unit: days | weeks | workdays | hours | sleeps
    val category: String = "",            // free-form grouping ("Family", "Work"…); "" = none
    val archived: Boolean = false,        // kept but out of the main list
    val favorite: Boolean = false,        // pinned to the top / "important"
    val photoBase64: String? = null,      // an optional photo face (added via the system gallery picker)
    val locked: Boolean = false,          // gate this occasion behind the app's biometric lock
    // R46 "relationship + intelligence" fields ---------------------------------------------------
    val keepInTouchDays: Int = 0,         // >0 = a keep-in-touch cadence; flag when days-since-last-moment exceeds it
    val momentsJson: String = "",         // logged moments/answers as a JSON array of {d:epochDay,n:note} — rides
                                          // the existing countdowns backup/sync, so no new table is needed
    val recurCalendar: String = "gregorian", // yearly recurrence calendar: gregorian | hijri (tabular, offline)
)
