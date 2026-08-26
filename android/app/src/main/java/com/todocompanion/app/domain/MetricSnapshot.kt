package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tier Z5 — one month's frozen snapshot of the cross-type meta-metrics the app now computes, so their
 * trend over time can be drawn. A "you over months" view no rival holds the inputs for. One row per
 * calendar month; stored as JSON in settings and captured on the first app-open of a new month.
 */
@Serializable
data class MetricSnapshot(
    val yearMonth: String,               // "2026-08"
    val calibrationPct: Int? = null,     // (calibration − 1) × 100; +20 = runs 20% over estimates
    val capacityH: Int? = null,          // real median tracked focus-hours/day
    val keystoneLiftPct: Int? = null,    // the keystone habit's task-output lift
    val balanceTopArea: String = "",     // the life area that took the most of the month
    val balanceTopShare: Int? = null,    // its share, 0..100
)

object MetricSnapshots {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun parse(s: String): List<MetricSnapshot> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<MetricSnapshot>>(s) }.getOrDefault(emptyList())
    fun encode(list: List<MetricSnapshot>): String = runCatching { json.encodeToString(list) }.getOrDefault("")
    /** Replace the row for the snapshot's month, else append; keep chronological, cap at 24 months. */
    fun upsert(list: List<MetricSnapshot>, snap: MetricSnapshot): List<MetricSnapshot> =
        (list.filterNot { it.yearMonth == snap.yearMonth } + snap).sortedBy { it.yearMonth }.takeLast(24)
}
