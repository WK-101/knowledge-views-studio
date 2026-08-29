package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tier U12 — a lightweight, fully on-device automation layer. A rule fires when you *start* tracking a
 * chosen activity, and its action is either a notification ("phone on silent?") or chaining another
 * activity to start too. No network, no Tasker, no special permission — Simple Time Tracker's complex
 * rules, reimagined for an offline unified store. Rules serialise into settings (and the JSON backup).
 */
@Serializable
data class AutomationRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    // Trigger: this activity's timer starting.
    val whenActivityId: String,
    // Action: "notify" posts [notifyText]; "start" also begins [startActivityId] (needs multi-timer on);
    // "stop" stops [stopActivityId] (blank = every other running timer).
    val actionType: String = ACTION_NOTIFY,
    val notifyText: String = "",
    val startActivityId: String = "",
    val stopActivityId: String = "",
    // Expert guards (R23): only fire inside a time-of-day window and/or on certain weekdays. -1 = unset;
    // [days] is a CSV of ISO day numbers (1=Mon…7=Sun), blank = any day. A window whose start > end wraps
    // past midnight (e.g. 22:00–06:00).
    val afterMin: Int = -1,
    val beforeMin: Int = -1,
    val days: String = "",
) {
    fun passesGuard(nowMin: Int, dow: Int): Boolean {
        val dayList = days.split(",").mapNotNull { it.trim().toIntOrNull() }
        val dayOk = dayList.isEmpty() || dow in dayList
        val timeOk = when {
            afterMin < 0 && beforeMin < 0 -> true
            afterMin >= 0 && beforeMin >= 0 && afterMin <= beforeMin -> nowMin in afterMin..beforeMin
            afterMin >= 0 && beforeMin >= 0 -> nowMin >= afterMin || nowMin <= beforeMin // wraps midnight
            afterMin >= 0 -> nowMin >= afterMin
            else -> nowMin <= beforeMin
        }
        return dayOk && timeOk
    }

    companion object {
        const val ACTION_NOTIFY = "notify"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
    }
}

object AutomationRules {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(s: String): List<AutomationRule> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<AutomationRule>>(s) }.getOrDefault(emptyList())

    fun encode(rules: List<AutomationRule>): String = runCatching { json.encodeToString(rules) }.getOrDefault("")

    /** The enabled rules triggered by starting [activityId]. */
    fun onStart(rules: List<AutomationRule>, activityId: String): List<AutomationRule> =
        rules.filter { it.enabled && it.whenActivityId == activityId }
}
