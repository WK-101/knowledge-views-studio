package com.cairn.reader.domain.review

import kotlin.math.roundToInt

/**
 * A compact human label for a next-review interval in whole days, shared by both schedulers so the
 * grade buttons read identically whichever engine is running. A non-positive interval means the
 * card relearns in the same session (the ~10-minute step), shown as "<10m".
 */
fun intervalLabel(days: Int): String = when {
    days <= 0 -> "<10m"
    days == 1 -> "1d"
    days < 30 -> "${days}d"
    days < 365 -> "${(days / 30.0).roundToInt()}mo"
    else -> "${(days / 365.0).roundToInt()}y"
}
