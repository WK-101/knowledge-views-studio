package com.cairn.reader.ui.util

import android.content.Context
import android.text.format.DateFormat
import java.util.Date

/** Absolute date + time honoring the device's locale AND its 12/24-hour setting.
 *  e.g. "5 Sep 2026, 4:30 PM" or "5 Sep 2026, 16:30" depending on the phone's clock preference. */
fun formatDateTime(context: Context, epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0L) return ""
    val d = Date(epochMillis)
    val date = DateFormat.getMediumDateFormat(context).format(d)
    val time = DateFormat.getTimeFormat(context).format(d)
    return "$date, $time"
}

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
