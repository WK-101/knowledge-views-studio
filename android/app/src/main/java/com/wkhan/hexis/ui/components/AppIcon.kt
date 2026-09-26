package com.wkhan.hexis.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * User-selectable launcher icon colour (the "Four into one" compass on a coloured ground).
 *
 * Android can't tint a home-screen icon to an arbitrary colour at runtime — the launcher reads a
 * statically-declared icon resource and caches it. So, exactly like the Fossify apps, we ship a
 * curated set of pre-baked icon variants (each is an `<activity-alias>` in the manifest pointing at
 * its own adaptive-icon mipmap) and switch which one is active with PackageManager component toggling.
 * No permission and no network are involved — it stays within the fully-offline invariant.
 *
 * The chosen component-enabled state is stored by the system and SURVIVES app updates (only a full
 * uninstall resets it to the manifest default), so we never need to reconcile it on launch — we only
 * apply on an explicit user change. The [AppSettings.iconVariant] value mirrors the choice for the UI.
 */
data class IconVariant(
    val id: String,
    val label: String,
    /** Relative activity-alias name in the manifest, resolved as "$NAMESPACE.$alias". */
    val alias: String,
    // Swatch colours (mirror the drawable/ic_bg_* gradients + foreground so the picker previews truthfully).
    val bgStart: Long,
    val bgEnd: Long,
    val stroke: Long,
    val core: Long,
)

object AppIconVariants {
    /** The manifest namespace the aliases live under (the applicationId has no build-type suffix). */
    const val NAMESPACE = "com.wkhan.hexis"
    const val DEFAULT_ID = "indigo"

    private const val LIGHT_STROKE = 0xFF3C2668
    private const val DARK_STROKE = 0xFFECE7F6
    private const val GOLD = 0xFFF5B01E

    /** Curated, on-brand set. The default (Indigo) reuses the app's primary ic_launcher mipmaps. */
    val ALL: List<IconVariant> = listOf(
        IconVariant("indigo",  "Indigo",  "IconIndigo",  0xFF2B2050, 0xFF3C2668, DARK_STROKE,  GOLD),
        IconVariant("plum",    "Plum",    "IconPlum",    0xFF34145A, 0xFF5E2472, DARK_STROKE,  GOLD),
        IconVariant("slate",   "Slate",   "IconSlate",   0xFF232833, 0xFF3A4150, DARK_STROKE,  GOLD),
        IconVariant("teal",    "Teal",    "IconTeal",    0xFF0C2E33, 0xFF155A5A, DARK_STROKE,  GOLD),
        IconVariant("verdant", "Verdant", "IconVerdant", 0xFF123528, 0xFF1E5A42, DARK_STROKE,  GOLD),
        IconVariant("crimson", "Crimson", "IconCrimson", 0xFF3A1220, 0xFF5E1C34, DARK_STROKE,  GOLD),
        IconVariant("amber",   "Amber",   "IconAmber",   0xFF3A2606, 0xFF5E4010, DARK_STROKE,  GOLD),
        IconVariant("light",   "Light",   "IconLight",   0xFFECE7F6, 0xFFDCD4EE, LIGHT_STROKE, GOLD),
    )

    fun byId(id: String): IconVariant = ALL.firstOrNull { it.id == id } ?: ALL.first()

    /**
     * Switch the active launcher icon. Enables the chosen alias FIRST (so the launcher is never left
     * with zero enabled entries — which would make the app vanish from the drawer), then disables the
     * rest. DONT_KILL_APP keeps the current process running. Safe to call with the already-active id.
     * The launcher may take a moment to re-read, and a few OEM launchers briefly re-arrange the icon.
     */
    fun apply(context: Context, id: String) {
        val pm = context.packageManager
        val pkg = context.packageName
        val target = byId(id)
        fun component(alias: String) = ComponentName(pkg, "$NAMESPACE.$alias")
        runCatching {
            pm.setComponentEnabledSetting(
                component(target.alias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            for (v in ALL) {
                if (v.id == target.id) continue
                pm.setComponentEnabledSetting(
                    component(v.alias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
