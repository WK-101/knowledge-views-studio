package com.todocompanion.app.domain

/**
 * Track 2.4 — swappable retrospective lenses for the Weekly Review's "get creative" step. Each lens is a
 * well-known retro format reduced to a title and its prompt fields; the user picks one and fills it, and
 * the answers persist alongside the weekly review (as a lens id + a field-id → answer map, all settings
 * JSON — no schema change). Pure data, Compose-free; unit-tested as plain Kotlin.
 */
object RetroLens {

    /** One prompt within a lens. [id] is stable (it keys the persisted answer); [hint] is placeholder text. */
    data class Field(val id: String, val label: String, val hint: String = "")

    /** A retrospective format: a titled set of prompt [fields]. */
    data class Lens(val id: String, val title: String, val emoji: String, val fields: List<Field>)

    val START_STOP_CONTINUE = Lens(
        "ssc", "Start · Stop · Continue", "🔁",
        listOf(
            Field("start", "Start", "What to begin doing"),
            Field("stop", "Stop", "What to quit"),
            Field("continue", "Continue", "What's working — keep it"),
        ),
    )

    val MAD_SAD_GLAD = Lens(
        "msg", "Mad · Sad · Glad", "🎭",
        listOf(
            Field("mad", "Mad", "What frustrated you"),
            Field("sad", "Sad", "What let you down"),
            Field("glad", "Glad", "What you're glad about"),
        ),
    )

    val FOUR_LS = Lens(
        "4ls", "4 Ls", "📝",
        listOf(
            Field("liked", "Liked", "What you enjoyed"),
            Field("learned", "Learned", "What you found out"),
            Field("lacked", "Lacked", "What was missing"),
            Field("longed", "Longed for", "What you wished for"),
        ),
    )

    val SAILBOAT = Lens(
        "sailboat", "Sailboat", "⛵",
        listOf(
            Field("wind", "Wind", "What pushed you forward"),
            Field("anchors", "Anchors", "What held you back"),
            Field("rocks", "Rocks", "Risks on the horizon"),
            Field("island", "Island", "The goal you're sailing to"),
        ),
    )

    /** Every lens, in the order they're offered. */
    val ALL: List<Lens> = listOf(START_STOP_CONTINUE, MAD_SAD_GLAD, FOUR_LS, SAILBOAT)

    /** The lens for an id, or null (including for the blank "no lens chosen" id). */
    fun byId(id: String?): Lens? = ALL.firstOrNull { it.id == id }
}
