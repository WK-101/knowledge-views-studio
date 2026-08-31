package com.todocompanion.app.domain.reminders

/**
 * R59 (Wave 1 · reminder parity) — ONE shared set of reminder lead-time presets, snooze durations and
 * intensity tiers, used by every reminder picker in the app (task editor, quick-add, calendar-event
 * alerts). Before this, each surface carried its own divergent offset list; now they all read from here,
 * so a "30 min before" means the same thing wherever you set it. Fully on-device — no network, no service.
 */
object ReminderPresets {
    /** Minutes-before presets, shared across tasks, quick-add and calendar-event alerts. */
    val OFFSETS: List<Int> = listOf(0, 5, 10, 15, 30, 60, 120, 1440)

    /** Snooze-duration presets (minutes) offered in Settings. */
    val SNOOZE: List<Int> = listOf(5, 10, 15, 30, 60, 120)

    /** Long label for menus — "At time", "30 min before", "2 hr before", "1 day before". */
    fun beforeLabel(min: Int): String = when {
        min <= 0 -> "At time"
        min % 1440 == 0 -> "${min / 1440} day${if (min / 1440 == 1) "" else "s"} before"
        min % 60 == 0 -> "${min / 60} hr before"
        else -> "$min min before"
    }

    /** Compact chip label — "At start", "30m", "2h", "1d". */
    fun shortLabel(min: Int): String = when {
        min <= 0 -> "At start"
        min % 1440 == 0 -> "${min / 1440}d"
        min % 60 == 0 -> "${min / 60}h"
        else -> "${min}m"
    }

    /** Snooze duration label — "10 min", "1 hour", "2 hours". */
    fun snoozeLabel(min: Int): String = when {
        min < 60 -> "$min min"
        min == 60 -> "1 hour"
        min % 60 == 0 -> "${min / 60} hours"
        else -> "$min min"
    }

    // ── Reminder intensity tiers ──────────────────────────────────────────────────────────────────────
    // The alarm engine already encodes intensity as two booleans on a reminder: `annoying` (re-fire until
    // done) and `escalate` (tighten the cadence + a full-screen alarm once ignored). These three tiers are
    // the human-facing surface for that existing behaviour.
    const val TIER_GENTLE = 0       // fire once, then quiet
    const val TIER_PERSISTENT = 1   // re-fire every 15 min until the task is done
    const val TIER_INSISTENT = 2    // escalate: tightening cadence + full-screen alarm

    val TIER_LABELS = listOf("Gentle", "Persistent", "Insistent")
    val TIER_BLURBS = listOf(
        "Fires once, then stays quiet.",
        "Keeps re-alerting every 15 minutes until you finish it.",
        "Takes over the screen like an alarm and won't let up until it's done.",
    )

    fun tierAnnoying(tier: Int): Boolean = tier >= TIER_PERSISTENT
    fun tierEscalate(tier: Int): Boolean = tier >= TIER_INSISTENT
    fun tierOf(annoying: Boolean, escalate: Boolean): Int = when {
        escalate -> TIER_INSISTENT
        annoying -> TIER_PERSISTENT
        else -> TIER_GENTLE
    }
}
