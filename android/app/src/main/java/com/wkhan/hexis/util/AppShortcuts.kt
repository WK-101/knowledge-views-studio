package com.wkhan.hexis.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.wkhan.hexis.R

/**
 * The app's launcher shortcuts as a first-class list.
 *
 * Android launchers only render the first few entries of an app's long-press menu (usually four), so
 * most of Hexis's shortcuts — "New note", "Today's note", "Close day" and the dynamic "Track: …" ones —
 * never appear there. This object lets Settings show ALL of them and lets the user PIN any one straight
 * to the home screen (`ShortcutManagerCompat.requestPinShortcut`, the supported in-app path — the OS then
 * shows its own "add to home screen" drop). Each [Spec] mirrors res/xml/shortcuts.xml exactly (same id,
 * labels, icon and deep-link intent), so a pinned shortcut behaves identically to a launcher-menu one.
 * Fully offline — a launcher shortcut is local IPC, no network.
 */
object AppShortcuts {
    private const val PKG = "com.wkhan.hexis"
    private const val MAIN = "com.wkhan.hexis.MainActivity"
    private const val QUICK = "com.wkhan.hexis.widget.QuickCaptureActivity"

    /** One launcher shortcut, kept in lockstep with res/xml/shortcuts.xml. */
    data class Spec(
        val id: String,
        val shortLabelRes: Int,
        val longLabelRes: Int,
        val iconRes: Int,
        val data: String,          // the hexis:// deep-link that routes it
        val targetClass: String,   // MAIN or QUICK
        val descr: String,         // one-line help shown in Settings
    )

    /** The 7 static shortcuts, in the same order as the XML. */
    val STATIC: List<Spec> = listOf(
        Spec("quick_add", R.string.sc_quick_add_short, R.string.sc_quick_add_long, R.drawable.sc_add,
            "hexis://add", QUICK, "Jump straight into the quick-add task panel."),
        Spec("open_today", R.string.sc_today_short, R.string.sc_today_long, R.drawable.sc_today,
            "hexis://today", MAIN, "Open your Today list."),
        Spec("open_donext", R.string.sc_donext_short, R.string.sc_donext_long, R.drawable.sc_donext,
            "hexis://donext", MAIN, "Open the Do-Next list."),
        Spec("open_focus", R.string.sc_focus_short, R.string.sc_focus_long, R.drawable.sc_focus,
            "hexis://focus", MAIN, "Start a focus session."),
        Spec("new_note", R.string.sc_new_note_short, R.string.sc_new_note_long, R.drawable.sc_note,
            "hexis://note", MAIN, "Create a new blank note."),
        Spec("daily_note", R.string.sc_daily_note_short, R.string.sc_daily_note_long, R.drawable.sc_daily,
            "hexis://daily", MAIN, "Open (or start) today's daily note."),
        Spec("close_day", R.string.sc_close_day_short, R.string.sc_close_day_long, R.drawable.sc_close_day,
            "hexis://closeday", MAIN, "Jump into the Close-the-day review."),
    )

    private fun intentFor(spec: Spec): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(spec.data)).setClassName(PKG, spec.targetClass)

    fun infoFor(context: Context, spec: Spec): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, spec.id)
            .setShortLabel(context.getString(spec.shortLabelRes))
            .setLongLabel(context.getString(spec.longLabelRes))
            .setIcon(IconCompat.createWithResource(context, spec.iconRes))
            .setIntent(intentFor(spec))
            .build()

    /** True when the current launcher supports pinning shortcuts (most modern launchers do). */
    fun canPin(context: Context): Boolean =
        runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    /** Ask the launcher to add [info] to the home screen. Returns false if unsupported / refused. */
    fun pin(context: Context, info: ShortcutInfoCompat): Boolean {
        if (!canPin(context)) return false
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }.getOrDefault(false)
    }

    fun pin(context: Context, spec: Spec): Boolean = pin(context, infoFor(context, spec))

    /** Build the "Track: <name>" shortcut for a time-tracking activity, mirroring [TrackShortcuts]. Derived
     *  from the activity itself (not from the launcher's registered dynamic shortcuts), so Settings can list
     *  and pin EVERY activity — including ones past the 4-shortcut launcher cap, and even when no dynamic
     *  shortcut is currently registered. */
    fun trackInfo(context: Context, activityId: String, activityName: String): ShortcutInfoCompat {
        val uri = Uri.parse("hexis://track?activity=${Uri.encode(activityId)}")
        return ShortcutInfoCompat.Builder(context, "track_$activityId")
            .setShortLabel(("Track: $activityName").take(24))
            .setLongLabel(("Track: $activityName").take(40))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(Intent.ACTION_VIEW, uri).setClassName(PKG, MAIN))
            .build()
    }
}
