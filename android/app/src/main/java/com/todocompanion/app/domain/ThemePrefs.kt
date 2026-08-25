package com.todocompanion.app.domain

import android.content.Context

/**
 * A tiny synchronous mirror of just the theme-affecting settings, in SharedPreferences. The real
 * settings live in Room (loaded asynchronously), so the very first frame would otherwise render
 * with defaults (SYSTEM theme) and then snap to the saved theme — a visible dark→light flash on a
 * dark device. Reading this cache synchronously lets the first frame use the correct theme.
 */
object ThemePrefs {
    private const val FILE = "theme_prefs"

    fun read(ctx: Context): Triple<ThemeMode, Boolean, Long> {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val mode = runCatching { enumValueOf<ThemeMode>(p.getString("mode", ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM)
        val dyn = p.getBoolean("dynamic", true)
        val accent = p.getLong("accent", 0L)
        return Triple(mode, dyn, accent)
    }

    fun save(ctx: Context, s: AppSettings) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("mode", s.themeMode.name)
            .putBoolean("dynamic", s.dynamicColor)
            .putLong("accent", s.accentArgb)
            .apply()
    }
}
