package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The redesigned daily-review SHARE feature's configuration: WHAT the shared "My day" card includes,
 * section by section. It is a small, pure, Compose-free data model that (de)serializes to/from ONE
 * settings-JSON string (see [DayShareConfigs]), mirroring how the app already keeps list-like settings
 * data as JSON strings (WeeklyReviews / DailyQuestions / DayAlignment). No Room schema change: the whole
 * config lives under a single [AppSettings.dayShareConfigJson] key persisted through DataStore.
 *
 * Defaults are chosen so the default card ≈ today's card, just cleaner: the felt state, wins and a line
 * of reflection are on; tasks/habits/time show a compact count; every richer/new section is off until the
 * user opts in. The card itself is still rendered fully on-device to a PNG (permission-free, FileProvider
 * + ACTION_SEND) — this only decides which blocks the renderer draws.
 */

/** How much task detail the card carries. OFF hides it, COUNT shows "N done", FULL lists each title. */
enum class TaskDetail { OFF, COUNT, FULL }

/** How much habit detail the card carries. OFF hides, COUNT shows "k/n habits", DETAILED lists each. */
enum class HabitDetail { OFF, COUNT, DETAILED }

/** How much tracked-time detail the card carries. OFF hides, TOTAL shows a total, DETAILED lists each. */
enum class TimeDetail { OFF, TOTAL, DETAILED }

/**
 * The visual STYLE the share card is rendered in — shared by the day card ([DayShareConfig]) and the
 * period card ([PeriodShareConfig]).
 *
 * PERSONAL is the warm, dark "a day, closed" card (the day default). PROFESSIONAL is a clean, credible,
 * document-style card on a near-white ground with dark ink and a restrained single accent — a calm
 * "proof of work" record suitable to share as evidence of what was done. The renderer branches on this;
 * the choice is persisted in the config.
 */
enum class ShareStyle { PERSONAL, PROFESSIONAL }

@Serializable
data class DayShareConfig(
    // ── Felt state ──
    /** The 1–5 day rating, drawn as stars. */
    val rating: Boolean = true,
    /** The evening mood emoji + energy dots + the named emotion word. */
    val moodEnergyEmotion: Boolean = true,
    // ── Highlights ──
    /** The day's wins (already privacy-safe titles). */
    val wins: Boolean = true,
    /** The single highlight / high point. */
    val highlight: Boolean = false,
    /** The three good things (each may carry its inline "…and why"). */
    val gratitude: Boolean = false,
    /** The one lesson / what you'd change. */
    val lesson: Boolean = false,
    // ── Reflection ──
    /** The reflection prose. */
    val reflection: Boolean = true,
    /** Top recurring themes of the reflection (computed on the fly via TextInsights). */
    val themes: Boolean = false,
    // ── Tasks · Habits · Tracked time (tri-state) ──
    val tasks: TaskDetail = TaskDetail.COUNT,
    val habits: HabitDetail = HabitDetail.COUNT,
    val time: TimeDetail = TimeDetail.TOTAL,
    // ── Assessments ──
    /** Each answered daily question + its 1–5 effort score. */
    val dailyQuestions: Boolean = false,
    /** Goals advanced + values honored, from the day's alignment. */
    val alignment: Boolean = false,
    // ── Tomorrow ──
    /** The one thing that matters tomorrow (MIT). */
    val tomorrowFocus: Boolean = false,
    /** The tomorrow WOOP: the expected obstacle + the if-then plan. */
    val woop: Boolean = false,
    // ── Insights ──
    /** A single soft, non-causal observation (ReviewInsights.nudge for the surrounding window). */
    val pattern: Boolean = false,
    // ── Footer ──
    /** The footer tagline line ("Kairo · a day, closed · 100% offline"). */
    val footerTagline: Boolean = true,
    // ── Style ──
    /** The visual style the card renders in. PERSONAL (warm/dark) is the day default; PROFESSIONAL is the
     *  clean, document-style "proof of work" card. */
    val style: ShareStyle = ShareStyle.PERSONAL,
)

object DayShareConfigs {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Parse the config from its settings JSON ("" / malformed = the defaults, so old installs round-trip). */
    fun parse(s: String): DayShareConfig =
        if (s.isBlank()) DayShareConfig()
        else runCatching { json.decodeFromString<DayShareConfig>(s) }.getOrDefault(DayShareConfig())

    /** Serialize the config back to a settings JSON string. */
    fun encode(c: DayShareConfig): String = runCatching { json.encodeToString(c) }.getOrDefault("")
}
