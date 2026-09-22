package com.todocompanion.app.widget

import android.content.Context

/**
 * Per-widget settings, keyed by appWidgetId. Each placed Agenda widget can show a different scope
 * (Today, Next 7 days, all Scheduled, or one list), carry its own title, and pick a light/dark
 * theme. Stored in a tiny SharedPreferences file — entirely offline.
 */
object WidgetPrefs {
    private const val FILE = "widget_prefs"

    // scope tokens: "today" | "next7" | "scheduled" | "list:<id>"
    fun scope(ctx: Context, id: Int): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("scope_$id", "today") ?: "today"

    fun title(ctx: Context, id: Int): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("title_$id", "") ?: ""

    /** "auto" | "light" | "dark" */
    fun theme(ctx: Context, id: Int): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("theme_$id", "auto") ?: "auto"

    fun save(ctx: Context, id: Int, scope: String, title: String, theme: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("scope_$id", scope).putString("title_$id", title).putString("theme_$id", theme).apply()
    }

    // ---- R104: shared appearance prefs, honoured by WidgetStyle for every widget ----

    /** Card opacity 0..100 (100 = as designed). */
    fun opacity(ctx: Context, id: Int): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("opacity_$id", 100).coerceIn(0, 100)

    /** Text-size multiplier ×100 stored as Int; exposed as Float 0.85..1.15. */
    fun fontScale(ctx: Context, id: Int): Float =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("font_$id", 100).coerceIn(70, 130) / 100f

    /** Compact rows (denser list, smaller paddings). */
    fun compact(ctx: Context, id: Int): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("compact_$id", false)

    /** Whether a list widget shows its header toolbar (add / open). Default on. */
    fun showToolbar(ctx: Context, id: Int): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("toolbar_$id", true)

    /** Persist the full appearance set from the shared config surface. */
    fun saveAppearance(ctx: Context, id: Int, opacity: Int, fontScalePct: Int, compact: Boolean, showToolbar: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("opacity_$id", opacity.coerceIn(0, 100))
            .putInt("font_$id", fontScalePct.coerceIn(70, 130))
            .putBoolean("compact_$id", compact)
            .putBoolean("toolbar_$id", showToolbar)
            .apply()
    }

    /** Persist just the theme (auto/light/dark) — used by widgets whose config is theme-only. */
    fun saveTheme(ctx: Context, id: Int, theme: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("theme_$id", theme).apply()
    }

    /** The habit a single-habit widget (Habit Week / Keystone / Habit Strength) is pinned to, or null to
     *  auto-pick. Content, not style — read it directly like [scope]. */
    fun habitId(ctx: Context, id: Int): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("habit_$id", null)?.ifBlank { null }

    fun saveHabit(ctx: Context, id: Int, habitId: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("habit_$id", habitId ?: "").apply()
    }

    /** Matrix widget — max tasks listed per quadrant (the rest scroll). 0 = auto (a generous cap). */
    fun matrixRows(ctx: Context, id: Int): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("mxrows_$id", 0)

    fun saveMatrixRows(ctx: Context, id: Int, rows: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("mxrows_$id", rows.coerceIn(0, 50)).apply()
    }

    /** Habit Zero — limit the widget to one habit category/group ("" = all habits). */
    fun group(ctx: Context, id: Int): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("group_$id", "") ?: ""

    fun saveGroup(ctx: Context, id: Int, group: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("group_$id", group).apply()
    }

    // Quick-bar widget: how many buttons (4–7) and which action each slot fires. Slot 0 is the centre;
    // it defaults to "app" — the brand mark that opens Kairo — but every slot (centre included) is a
    // freely assignable action, so "app" is just the first option in the pool.
    val QUICK_ACTIONS = listOf("app", "task", "note", "habit", "time", "search", "dailynote", "closeday", "weekreview")
    private val QUICK_DEFAULT = listOf("app", "task", "note", "habit", "time", "search", "closeday")

    fun quickCount(ctx: Context, id: Int): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("qccount_$id", 5).coerceIn(4, 7)

    fun quickSlots(ctx: Context, id: Int): List<String> {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString("qcslots_$id", null)
        val saved = raw?.split(",")?.map { it.trim() }?.filter { it in QUICK_ACTIONS } ?: emptyList()
        // Always return 7 entries (config edits by index); fall back to defaults for any missing slot.
        val result = (0 until 7).map { saved.getOrNull(it) ?: QUICK_DEFAULT[it] }

        // One-time migration: the centre (slot 0) now defaults to the app's brand mark. A widget placed
        // before "app" existed as an action still has a capture action stored in slot 0, so its centre
        // shows a checkmark instead of the app icon. Adopt the new default once, leaving every other slot
        // (and any later manual re-assignment) untouched.
        if (!prefs.getBoolean("qcappmig_$id", false)) {
            if (raw != null && result[0] != "app") {
                val migrated = result.toMutableList().also { it[0] = "app" }
                prefs.edit().putString("qcslots_$id", migrated.joinToString(","))
                    .putBoolean("qcappmig_$id", true).apply()
                return migrated
            }
            prefs.edit().putBoolean("qcappmig_$id", true).apply()
        }
        return result
    }

    fun saveQuick(ctx: Context, id: Int, count: Int, slots: List<String>) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("qccount_$id", count.coerceIn(4, 7))
            .putString("qcslots_$id", slots.joinToString(","))
            .apply()
    }

    fun clear(ctx: Context, id: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove("scope_$id").remove("title_$id").remove("theme_$id")
            .remove("energy_$id").remove("time_$id")
            .remove("opacity_$id").remove("font_$id").remove("compact_$id").remove("toolbar_$id")
            .remove("dayoff_$id").remove("habit_$id").remove("group_$id").remove("mxrows_$id")
            .remove("qccount_$id").remove("qcslots_$id").remove("qcappmig_$id")
            .apply()
    }

    // Do-Next widget filters. energy: 0 Any, 1 Low, 2 Medium, 3 High ("I have this much energy").
    // time: 0 Any, else the minute cap ("I have this much time"): 15 / 30 / 60.
    fun energy(ctx: Context, id: Int): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("energy_$id", 0)

    fun time(ctx: Context, id: Int): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("time_$id", 0)

    /** Advance the energy filter through Any → Low → Medium → High → Any. */
    fun cycleEnergy(ctx: Context, id: Int) {
        val next = (energy(ctx, id) + 1) % 4
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("energy_$id", next).apply()
    }

    /** Advance the time filter through Any → ≤15m → ≤30m → ≤1h → Any. */
    fun cycleTime(ctx: Context, id: Int) {
        val order = listOf(0, 15, 30, 60)
        val next = order[(order.indexOf(time(ctx, id)).coerceAtLeast(0) + 1) % order.size]
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("time_$id", next).apply()
    }

    // ---- R104: Day widget — which day it's showing, as an offset from today (0 = today). ----
    fun dayOffset(ctx: Context, id: Int): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("dayoff_$id", 0)

    fun setDayOffset(ctx: Context, id: Int, offset: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("dayoff_$id", offset.coerceIn(-365, 365)).apply()
    }

    /** Default header title for a scope token, used when the user left the title blank. */
    fun defaultTitle(scope: String): String = when {
        scope == "today" -> "Agenda"
        scope == "next7" -> "Next 7 days"
        scope == "scheduled" -> "Scheduled"
        scope.startsWith("list:") -> "List"
        else -> "Agenda"
    }
}
