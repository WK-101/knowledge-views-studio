package com.todocompanion.app.widget

import android.content.Context
import android.content.res.Configuration
import android.widget.RemoteViews
import com.todocompanion.app.R

/**
 * R104 — the one place a widget's visual style is resolved. Static widget layouts pull their
 * colours straight from `@color/w_*` resources (which the system swaps light/dark for the "auto"
 * theme); this class is the programmatic twin for the pieces that can't use resources: collection
 * factory rows, a per-widget *forced* light/dark theme, per-widget opacity, and font scaling.
 *
 * The two palettes below are byte-for-byte the same as res/values/colors.xml (light) and
 * res/values-night/colors.xml (dark), so a widget looks identical whether it was coloured by a
 * resource or by this helper. Entirely offline — reads only WidgetPrefs + the system uiMode.
 */
data class WidgetStyle(
    val dark: Boolean,
    val surface: Int,
    val surfaceVariant: Int,
    val chip: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val onAccent: Int,
    val accent: Int,
    val accentText: Int,
    val teal: Int,
    val danger: Int,
    val warning: Int,
    val info: Int,
    val success: Int,
    val divider: Int,
    val stroke: Int,
    /** 0..100 — card opacity (100 = as designed). Applied to [surface] alpha. */
    val opacity: Int,
    /** Text-size multiplier: 0.85 compact … 1.15 large. */
    val fontScale: Float,
    val compact: Boolean,
) {
    /** [surface] with the per-widget opacity folded into its alpha channel. */
    val surfaceWithOpacity: Int
        get() {
            val baseAlpha = (surface ushr 24) and 0xFF
            val a = (baseAlpha * opacity / 100).coerceIn(0, 255)
            return (a shl 24) or (surface and 0x00FFFFFF)
        }

    /** Scale a base sp value by the font preference, rounded to a sensible float. */
    fun sp(base: Float): Float = (base * fontScale)

    companion object {
        /**
         * Override the card background only when the widget's theme is *forced*. For "auto" we leave
         * the layout's adaptive `@drawable/widget_bg` (which the system swaps light/dark). A forced
         * choice needs a non-adaptive drawable so it doesn't follow the system.
         */
        fun applyCardBackground(views: RemoteViews, rootId: Int, ctx: Context, widgetId: Int) {
            when (WidgetPrefs.theme(ctx, widgetId)) {
                "light" -> views.setInt(rootId, "setBackgroundResource", R.drawable.widget_bg_light)
                "dark" -> views.setInt(rootId, "setBackgroundResource", R.drawable.widget_bg_dark)
                else -> { /* adaptive widget_bg stays */ }
            }
        }

        /** Resolve the style for a placed widget (id ≥ 0) or a neutral default (id < 0). */
        fun resolve(ctx: Context, widgetId: Int = -1): WidgetStyle {
            val themePref = if (widgetId >= 0) WidgetPrefs.theme(ctx, widgetId) else "auto"
            val sysDark = (ctx.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val dark = when (themePref) {
                "light" -> false
                "dark" -> true
                else -> sysDark
            }
            val opacity = if (widgetId >= 0) WidgetPrefs.opacity(ctx, widgetId) else 100
            val fontScale = if (widgetId >= 0) WidgetPrefs.fontScale(ctx, widgetId) else 1f
            val compact = if (widgetId >= 0) WidgetPrefs.compact(ctx, widgetId) else false
            return if (dark) WidgetStyle(
                dark = true,
                surface = 0xF01A1B26.toInt(),
                surfaceVariant = 0xFF24273A.toInt(),
                chip = 0x2AFFFFFF,
                textPrimary = 0xFFFFFFFF.toInt(),
                textSecondary = 0xFFB9B4D0.toInt(),
                textTertiary = 0xFF8A8699.toInt(),
                onAccent = 0xFFFFFFFF.toInt(),
                accent = 0xFF6D5AC4.toInt(),
                accentText = 0xFFB9A6EC.toInt(),
                teal = 0xFF12A594.toInt(),
                danger = 0xFFFF9A8B.toInt(),
                warning = 0xFFF59E0B.toInt(),
                info = 0xFF3E7BFA.toInt(),
                success = 0xFF5BD6A0.toInt(),
                divider = 0x22FFFFFF,
                stroke = 0x22FFFFFF,
                opacity = opacity, fontScale = fontScale, compact = compact,
            ) else WidgetStyle(
                dark = false,
                surface = 0xF7FBFAFF.toInt(),
                surfaceVariant = 0xFFEFEBF7.toInt(),
                chip = 0x14000000,
                textPrimary = 0xFF1A1B26.toInt(),
                textSecondary = 0xFF5B5870.toInt(),
                textTertiary = 0xFF8A8798.toInt(),
                onAccent = 0xFFFFFFFF.toInt(),
                accent = 0xFF6D5AC4.toInt(),
                accentText = 0xFF6D5AC4.toInt(),
                teal = 0xFF0E8577.toInt(),
                danger = 0xFFC4361F.toInt(),
                warning = 0xFFB26A00.toInt(),
                info = 0xFF2563EB.toInt(),
                success = 0xFF12A05C.toInt(),
                divider = 0x14000000,
                stroke = 0x14000000,
                opacity = opacity, fontScale = fontScale, compact = compact,
            )
        }
    }
}
