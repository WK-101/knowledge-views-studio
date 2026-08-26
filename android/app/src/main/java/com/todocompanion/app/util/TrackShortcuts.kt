package com.todocompanion.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import com.todocompanion.app.data.entity.TimeActivityEntity

/**
 * Tier U13 — "context cards" as launcher shortcuts. Each time activity becomes a long-press shortcut
 * ("Track: Deep work") that fires the offline `todocompanion://track` deep link to start its timer.
 * The same URI works from an NFC tag or a QR code the user makes — no network, no extra permission.
 */
object TrackShortcuts {
    fun refresh(context: Context, activities: List<TimeActivityEntity>) {
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(1)
            val shortcuts = activities.take(max.coerceAtMost(4)).map { a ->
                val uri = Uri.parse("todocompanion://track?activity=${Uri.encode(a.id)}")
                val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
                ShortcutInfoCompat.Builder(context, "track_${a.id}")
                    .setShortLabel(("Track: " + a.name).take(24))
                    .setLongLabel(("Track: " + a.name).take(40))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(intent)
                    .build()
            }
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            if (shortcuts.isNotEmpty()) ShortcutManagerCompat.addDynamicShortcuts(context, shortcuts)
        }
    }
}
