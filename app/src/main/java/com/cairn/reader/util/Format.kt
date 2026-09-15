package com.cairn.reader.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter

/**
 * One place for the small formatting/URI helpers that were previously copy-pasted (with divergent
 * output) across the settings, offline and import screens.
 */

/** Human-readable, locale-correct file size — the single byte formatter for the whole app. */
fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/** Best-effort human-readable name for a picked document (falls back to the last path segment). */
fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
    } ?: uri.lastPathSegment
}.getOrNull()
