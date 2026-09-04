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

    fun clear(ctx: Context, id: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove("scope_$id").remove("title_$id").remove("theme_$id")
            .remove("energy_$id").remove("time_$id")
            .remove("opacity_$id").remove("font_$id").remove("compact_$id").remove("toolbar_$id")
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

    /** Default header title for a scope token, used when the user left the title blank. */
    fun defaultTitle(scope: String): String = when {
        scope == "today" -> "Agenda"
        scope == "next7" -> "Next 7 days"
        scope == "scheduled" -> "Scheduled"
        scope.startsWith("list:") -> "List"
        else -> "Agenda"
    }
}
