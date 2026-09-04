package com.cairn.reader.ui.util

/** Compact relative time for list rows: "just now", "3h", "yesterday", "4d", "2w". */
fun formatAgo(epochMillis: Long?, now: Long = System.currentTimeMillis()): String {
    if (epochMillis == null || epochMillis <= 0L) return ""
    val diff = (now - epochMillis).coerceAtLeast(0)
    val minutes = diff / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days == 1L -> "yesterday"
        days < 7 -> "${days}d"
        days < 30 -> "${days / 7}w"
        days < 365 -> "${days / 30}mo"
        else -> "${days / 365}y"
    }
}
