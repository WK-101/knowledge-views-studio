package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The period-spanning SHARE feature's configuration: WHAT a shared WEEK / MONTH / YEAR roll-up card
 * includes, section by section. It is the roll-up counterpart to [DayShareConfig] — a small, pure,
 * Compose-free data model that (de)serializes to/from ONE settings-JSON string (see [PeriodShareConfigs]),
 * persisted under a single [AppSettings.periodShareConfigJson] key. No Room schema change.
 *
 * One config serves all three periods (week / month / year); the renderer only draws a section when the
 * selected period actually has that data, so e.g. the execution-score section is a week-only measure and
 * is simply skipped for month / year. Defaults are chosen so a shared roll-up ≈ the on-screen roll-up:
 * the felt trend, execution score, wins, a compact habit + time summary, goals and the tasks count are on;
 * themes are off until opted in. The card is rendered fully on-device to a PNG (permission-free,
 * FileProvider + ACTION_SEND) — this only decides which blocks the renderer draws.
 *
 * The tri-states reuse [HabitDetail] (OFF / COUNT / DETAILED) and [TimeDetail] (OFF / TOTAL / DETAILED)
 * so the period card speaks the same language as the day card.
 */
@Serializable
data class PeriodShareConfig(
    // ── Felt ──
    /** The average rating (stars) + average mood over the period. */
    val feltTrend: Boolean = true,
    /** The week execution score (planned commitments vs done). Week-only; skipped for month / year. */
    val executionScore: Boolean = true,
    // ── Highlights ──
    /** The period's top wins (week / month) and the standout highlight (year). */
    val wins: Boolean = true,
    // ── Habits · Tracked time (tri-state) ──
    val habits: HabitDetail = HabitDetail.COUNT,
    val time: TimeDetail = TimeDetail.TOTAL,
    // ── Progress ──
    /** Goals advanced across the period. */
    val goals: Boolean = true,
    /** Top recurring themes of the period's reflections. */
    val themes: Boolean = false,
    /** The tasks-completed count over the period. */
    val tasks: Boolean = true,
    // ── Footer ──
    /** The footer tagline / credibility line. */
    val footerTagline: Boolean = true,
    // ── Style ──
    /** The visual style the card renders in — PERSONAL (warm/dark) or PROFESSIONAL (clean, document-style). */
    val style: ShareStyle = ShareStyle.PERSONAL,
)

object PeriodShareConfigs {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Parse the config from its settings JSON ("" / malformed = the defaults, so old installs round-trip). */
    fun parse(s: String): PeriodShareConfig =
        if (s.isBlank()) PeriodShareConfig()
        else runCatching { json.decodeFromString<PeriodShareConfig>(s) }.getOrDefault(PeriodShareConfig())

    /** Serialize the config back to a settings JSON string. */
    fun encode(c: PeriodShareConfig): String = runCatching { json.encodeToString(c) }.getOrDefault("")
}
