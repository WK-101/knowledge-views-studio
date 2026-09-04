package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * Wave 1 — a distinct, guided Weekly Review (Get Clear → Get Current → Get Creative → Sharpen the saw).
 * It closes the week the way the Day Review closes a day, but persists only a little: the week's
 * reflection text, next week's focus, and which life areas the person touched on. No new Room table —
 * the whole store is ONE settings JSON value ([AppSettings.weeklyReviewsJson]) mapping an ISO-week key
 * to that week's [WeeklyReview], mirroring how the app already keeps list-like data as JSON strings.
 *
 * Pure and Compose-free so it unit-tests as plain Kotlin, matching DailyQuestions / DayAlignment.
 */
@Serializable
data class WeeklyReview(
    val isoWeek: String = "",       // the ISO-week key this review belongs to, e.g. "2026-W36"
    val reflection: String = "",    // Get Creative: what to try or change next week
    val nextFocus: String = "",     // the one focus set for next week
    val areas: List<String> = emptyList(), // Sharpen the saw: the life areas touched on
    val updatedAt: Long = 0,
) {
    val isEmpty: Boolean get() = reflection.isBlank() && nextFocus.isBlank() && areas.isEmpty()
}

object WeeklyReviews {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The life areas offered in the "sharpen the saw" step — a small, fixed, always-available set
     *  (Covey's roles / the four dimensions of renewal), so the step works with no other setup. */
    val AREAS: List<String> = listOf("Work", "Relationships", "Health", "Growth", "Rest")

    /** The ISO-8601 week key for a date, e.g. "2026-W36" (week-based year + week-of-week-based-year).
     *  Locale-independent, so a given calendar week has exactly one stable key regardless of the user's
     *  week-start setting; consecutive review weeks (7 days apart) always land on distinct keys. */
    fun isoWeekKey(date: LocalDate): String {
        val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val year = date.get(IsoFields.WEEK_BASED_YEAR)
        return "%04d-W%02d".format(year, week)
    }

    /** Parse the whole store (isoWeek → review) from its settings JSON ("" / malformed = empty). */
    fun parseAll(s: String): Map<String, WeeklyReview> =
        if (s.isBlank()) emptyMap()
        else runCatching { json.decodeFromString<Map<String, WeeklyReview>>(s) }.getOrDefault(emptyMap())

    /** Serialize the whole store back to a settings JSON string ("" when empty). */
    fun encodeAll(map: Map<String, WeeklyReview>): String =
        if (map.isEmpty()) "" else runCatching { json.encodeToString(map) }.getOrDefault("")

    /** The stored review for a week, or null if none. */
    fun forWeek(s: String, isoWeek: String): WeeklyReview? = parseAll(s)[isoWeek]

    /** True when a non-empty review has been recorded for [isoWeek] — drives the "Reviewed ✓" state. */
    fun isReviewed(s: String, isoWeek: String): Boolean = forWeek(s, isoWeek)?.isEmpty == false

    /** Upsert one week's review into the store, returning the new JSON string (an empty review clears it). */
    fun upsert(s: String, review: WeeklyReview): String {
        val map = parseAll(s).toMutableMap()
        if (review.isEmpty) map.remove(review.isoWeek) else map[review.isoWeek] = review
        return encodeAll(map)
    }
}
