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
    // Action: "notify" posts [notifyText]; "start" also begins [startActivityId] (needs multi-timer on).
    val actionType: String = ACTION_NOTIFY,
    val notifyText: String = "",
    val startActivityId: String = "",
) {
    companion object {
        const val ACTION_NOTIFY = "notify"
        const val ACTION_START = "start"
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
