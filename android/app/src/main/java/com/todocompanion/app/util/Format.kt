package com.todocompanion.app.util

/**
 * The single, canonical minutes → "Xh Ym" duration formatter.
 *
 * `45 → "45m"`, `60 → "1h"`, `90 → "1h 30m"`, `0` or negative → `"0m"`.
 *
 * This replaces six divergent per-feature copies (previously named `fmtMin` / `fmtMinutes` / `fmtHm` /
 * `formatHm` / `fmtDuration`) — five of which rendered an exact hour as `"1h 0m"` while the rest rendered
 * `"1h"`. One source removes that drift and the trailing `" 0m"`. Pure, unit-testable.
 */
fun formatMinutes(min: Int): String {
    val m = if (min < 0) 0 else min
    return when {
        m < 60 -> "${m}m"
        m % 60 == 0 -> "${m / 60}h"
        else -> "${m / 60}h ${m % 60}m"
    }
}
