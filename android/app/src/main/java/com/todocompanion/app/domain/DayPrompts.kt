package com.todocompanion.app.domain

/**
 * Phase B — a rotating daily reflection prompt. One short question per day, drawn from a curated set of
 * Stoic, Franklin-style self-examination, after-action-review and growth reflections. Pure and
 * deterministic: the same epoch day always yields the same prompt, so the Day Review shows a stable
 * question you can answer in the evening (and the answer can be shown back beside the very same question).
 */
object DayPrompts {
    // ~30 short, answerable prompts. Kept deliberately terse so they fit a single-line field.
    private val PROMPTS: List<String> = listOf(
        // Growth / after-action.
        "What did today teach you?",
        "What would you do differently?",
        "What went as planned — and what surprised you?",
        "What worked well enough to repeat tomorrow?",
        "Where did the day drift from your intention?",
        "What did you learn about yourself today?",
        "What challenged you — and how did you grow?",
        "What would make tomorrow one percent better?",
        // Gratitude / savouring / energy.
        "Who or what are you grateful for right now?",
        "Who made your day better — did you tell them?",
        "What gave you energy today?",
        "What drained you — and can you avoid it tomorrow?",
        "What moment today would you happily relive?",
        "What small win deserves to be noticed?",
        "What are you proud of, however small?",
        "When did you feel most like yourself today?",
        // Stoic.
        "What was in your control today — and what wasn't?",
        "Where did you act on your values, not your mood?",
        "What would your calmest self say about today?",
        "What discomfort did you meet well today?",
        "What can you let go of before you sleep?",
        "Did you spend today on what you say matters?",
        // Franklin-style self-examination.
        "What good did you do today?",
        "Where did you fall short of who you want to be?",
        "Which strength did you use best today?",
        "What habit moved you a step forward — or back?",
        "What did you avoid — and what is it costing you?",
        "Where did you spend your best hour today?",
        // Forward-looking.
        "What is the one thing you most want to protect tomorrow?",
        "What is one kindness you can plan for tomorrow?",
        "If tomorrow mirrored today, would you be glad?",
    )

    /** The prompt for a given epoch day — a stable rotation across the curated set. */
    fun promptFor(epochDay: Long): String {
        val n = PROMPTS.size
        if (n == 0) return ""
        val nn = n.toLong()
        val i = (((epochDay % nn) + nn) % nn).toInt() // floor-mod so negative epoch days rotate too
        return PROMPTS[i]
    }
}
