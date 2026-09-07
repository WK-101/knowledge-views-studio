package com.obliviate.app.core

import java.io.File
import java.util.Locale

/** Recursively deletes the contents of [dir] but keeps [dir] itself. */
fun deleteContents(dir: File) {
    dir.listFiles()?.forEach { child ->
        if (child.isDirectory) deleteContents(child)
        child.delete()
    }
}

/** Sum of the sizes of all regular files under [dir]. */
fun dirSize(dir: File): Long {
    var total = 0L
    dir.listFiles()?.forEach { child ->
        total += if (child.isDirectory) dirSize(child) else child.length()
    }
    return total
}

/** Human-readable byte formatting, e.g. 1.5 GB. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

/** Human-readable throughput, e.g. 120 MB/s. */
fun formatSpeed(bytesPerSec: Long): String =
    if (bytesPerSec <= 0) "—" else "${formatBytes(bytesPerSec)}/s"

/** Human-readable duration from milliseconds, e.g. 2m 05s. */
fun formatDuration(millis: Long): String {
    val totalSec = millis / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> String.format(Locale.US, "%dh %02dm %02ds", h, m, s)
        m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
        else -> String.format(Locale.US, "%ds", s)
    }
}
