package com.todocompanion.app.domain.recurrence

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * A deliberately small, offline recurrence model. A rule is stored as "FREQ:INTERVAL",
 * e.g. "WEEKLY:1" or "DAILY:2"; "WEEKDAYS:1" means Mon–Fri. This covers the common cases
 * without pulling in a full RFC-5545 RRULE engine.
 */
object Recurrence {

    /** (rule, label) options shown in the picker. A null rule means "does not repeat". */
    val PRESETS: List<Pair<String?, String>> = listOf(
        null to "Does not repeat",
        "DAILY:1" to "Daily",
        "WEEKDAYS:1" to "Every weekday",
        "WEEKLY:1" to "Weekly",
        "WEEKLY:2" to "Every 2 weeks",
        "MONTHLY:1" to "Monthly",
        "YEARLY:1" to "Yearly",
    )

    fun label(rule: String?): String? {
        if (rule.isNullOrBlank()) return null
        val (freq, interval) = split(rule)
        return when (freq) {
            "DAILY" -> if (interval == 1) "Daily" else "Every $interval days"
            "WEEKDAYS" -> "Every weekday"
            "WEEKLY" -> if (interval == 1) "Weekly" else "Every $interval weeks"
            "MONTHLY" -> if (interval == 1) "Monthly" else "Every $interval months"
            "YEARLY" -> if (interval == 1) "Yearly" else "Every $interval years"
            else -> null
        }
    }

    /** Next occurrence strictly after [fromMillis], preserving time-of-day. */
    fun next(rule: String, fromMillis: Long, zone: ZoneId): Long {
        val (freq, interval) = split(rule)
        val dt = Instant.ofEpochMilli(fromMillis).atZone(zone)
        val nd = when (freq) {
            "DAILY" -> dt.plusDays(interval.toLong())
            "WEEKLY" -> dt.plusWeeks(interval.toLong())
            "MONTHLY" -> dt.plusMonths(interval.toLong())
            "YEARLY" -> dt.plusYears(interval.toLong())
            "WEEKDAYS" -> {
                var d = dt.plusDays(1)
                while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
                d
            }
            else -> return fromMillis
        }
        return nd.toInstant().toEpochMilli()
    }

    private fun split(rule: String): Pair<String, Int> {
        val parts = rule.split(":")
        return parts[0].uppercase() to (parts.getOrNull(1)?.toIntOrNull() ?: 1)
    }
}
