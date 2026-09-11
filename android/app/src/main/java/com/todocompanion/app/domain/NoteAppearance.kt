package com.todocompanion.app.domain

/**
 * Wave Q — the reading experience. Two pure, unit-testable value objects that carry how a note should
 * look, decoupled from Compose so the same choices drive both the native editor and the offline rich
 * WebView:
 *
 *  • [NoteReadingTheme] — a curated, *coordinated* palette (paper, ink, accent, code, borders) in both
 *    a light and a dark variant, the way Bear/UpNote ship named themes rather than raw colour pickers.
 *    The special id "match" means "follow the app's Material theme" (the palette is then supplied by the
 *    caller from the live colour scheme).
 *  • [NoteType] — typography: font family, size scale, line-height and an optional reading measure
 *    (max line width), the knobs UpNote proves people reach for even on Android.
 *
 * Nothing here touches Android; the UI maps [NoteType.font] to a Compose FontFamily and hands a
 * [NoteReadingTheme.Palette] to [com.todocompanion.app.util.NoteRichRenderer.Theme].
 */
object NoteAppearance {

    /** A coordinated colour set as CSS hex strings (also consumed as Compose colours in the editor). */
    data class Palette(
        val bg: String,
        val fg: String,
        val muted: String,
        val accent: String,
        val codeBg: String,
        val border: String,
        val quoteBar: String,
    )

    data class ReadingTheme(
        val id: String,
        val name: String,
        /** null for the "match the app" theme — the caller fills it from the Material scheme. */
        val light: Palette?,
        val dark: Palette?,
        /** Preferred body font for this theme ("system" | "serif" | "sans" | "mono"); user [NoteType] wins if set. */
        val font: String = "system",
    ) {
        /** The palette for the given mode, or null for the "match" theme. */
        fun palette(dark: Boolean): Palette? = if (dark) this.dark else this.light
    }

    val MATCH = ReadingTheme("match", "Match app", null, null)

    val THEMES: List<ReadingTheme> = listOf(
        MATCH,
        ReadingTheme(
            "paper", "Paper", font = "serif",
            light = Palette("#FBF9F4", "#2B2A26", "#7C766B", "#8A6D3B", "#F0ECE1", "#E5DECF", "#C9BFA6"),
            dark = Palette("#15140F", "#ECE7DC", "#9A9485", "#C9A46A", "#211F18", "#332F25", "#4A4436"),
        ),
        ReadingTheme(
            "sepia", "Sepia", font = "serif",
            light = Palette("#F4ECD8", "#4A3F2E", "#8A7A5E", "#9C6B3F", "#EBE1C7", "#DDD0AE", "#C7B58C"),
            dark = Palette("#20180E", "#E7D9BF", "#A2937A", "#C98A52", "#2B2114", "#3A2E1C", "#4E3E26"),
        ),
        ReadingTheme(
            "graphite", "Graphite", font = "sans",
            light = Palette("#F6F7F8", "#23262B", "#6B7280", "#4B6472", "#ECEEF1", "#DFE3E8", "#C6CCD3"),
            dark = Palette("#17191C", "#E7E9EC", "#9AA1AB", "#8FB0BF", "#202327", "#2C3035", "#3A3F46"),
        ),
        ReadingTheme(
            "solarized", "Solarized", font = "mono",
            light = Palette("#FDF6E3", "#586E75", "#93A1A1", "#268BD2", "#EEE8D5", "#DDD6C1", "#CB4B16"),
            dark = Palette("#002B36", "#93A1A1", "#657B83", "#268BD2", "#073642", "#0E4653", "#CB4B16"),
        ),
        ReadingTheme(
            "midnight", "Midnight", font = "sans",
            light = Palette("#F7F8FA", "#1F2328", "#656C76", "#3B72E0", "#EEF0F3", "#DEE2E7", "#C4CAD2"),
            dark = Palette("#0E1116", "#C9D1D9", "#8B949E", "#58A6FF", "#161B22", "#262C33", "#343B44"),
        ),
        ReadingTheme(
            "kairo", "Kairo", font = "sans",
            light = Palette("#F5F6F8", "#1A1F27", "#68727F", "#0F7A6C", "#EFF1F4", "#E1E5EA", "#CDD3DB"),
            dark = Palette("#111419", "#E9ECF1", "#98A2AE", "#3EB6A5", "#20252D", "#272D36", "#343C47"),
        ),
    )

    fun theme(id: String?): ReadingTheme = THEMES.firstOrNull { it.id == id } ?: MATCH

    // ── typography ───────────────────────────────────────────────────────────────
    data class NoteType(
        val font: String = "system",      // system | serif | sans | mono
        val scalePct: Int = 100,          // 80..160
        val lineHeight: String = "normal", // compact | normal | relaxed
        val measure: Boolean = false,      // limit reading line width
    ) {
        /** Multiplier applied to the base 16sp/px body size. Clamped to a sane range. */
        fun scale(): Float = (scalePct.coerceIn(70, 200)) / 100f

        /** Line-height multiple. */
        fun lineFactor(): Float = when (lineHeight) {
            "compact" -> 1.42f
            "relaxed" -> 1.9f
            else -> 1.62f
        }

        /** Max reading width in CSS `ch` for the WebView, or 0 for "full width". ~66ch is a classic measure. */
        fun measureCh(): Int = if (measure) 66 else 0

        /** Resolve the effective font: an explicit user font wins; otherwise the theme's preferred font. */
        fun effectiveFont(themeFont: String): String = if (font == "system") themeFont else font
    }

    val FONTS = listOf("system" to "System", "serif" to "Serif", "sans" to "Sans", "mono" to "Mono")
    val LINE_HEIGHTS = listOf("compact" to "Compact", "normal" to "Normal", "relaxed" to "Relaxed")
    val SCALES = listOf(85, 100, 115, 130, 150)
}
