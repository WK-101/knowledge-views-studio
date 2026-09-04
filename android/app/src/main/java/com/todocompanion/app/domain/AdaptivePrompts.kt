package com.todocompanion.app.domain

/**
 * Wave 1 — an evening reflection prompt that ADAPTS to the kind of day, rather than one fixed rotation.
 * The day's own rating and mood steer which kind of question is asked:
 *  - a GOOD day (rating ≥ 4 or mood ≥ 4) gets a SAVOR prompt — savour it, notice what made it work, so
 *    you can get more of it (broaden-and-build);
 *  - a HARD day (rating in 1..2 or mood in 1..2) gets a gentle REFRAME / self-compassion prompt —
 *    never scolding, always the voice you'd use with a friend;
 *  - otherwise the existing NEUTRAL rotating prompt ([DayPrompts]).
 *
 * Pure and deterministic: the same (epochDay, rating, mood) always yields the same prompt, and within a
 * kind the epoch day rotates the curated set with a floor-mod so the question is stable for a given day
 * but varies day to day. Compose-free so it unit-tests as plain Kotlin. The answer still saves to the
 * existing DayLog.promptAnswer field — this only changes which question is shown, never the storage.
 */
enum class PromptKind { SAVOR, REFRAME, NEUTRAL }

data class AdaptivePrompt(val text: String, val kind: PromptKind)

object AdaptivePrompts {
    /** Savouring / celebration prompts for a good day — notice the good and how to repeat it. */
    val SAVOR: List<String> = listOf(
        "What made today good — and how can you get more of it?",
        "What went right today that you want to repeat tomorrow?",
        "Who or what made today better — did you let them know?",
        "Which moment today would you happily relive?",
        "What strength did you lean on to make today work?",
        "What small win today deserves to be celebrated?",
        "What are you most proud of about today?",
        "What set today up to go well — and can you set it up again?",
    )

    /** Gentle cognitive-reframe / self-compassion prompts for a hard day — kind, never scolding. */
    val REFRAME: List<String> = listOf(
        "What felt hard today? Now — what would you say to a friend in the same spot?",
        "What made today heavy — and what is one small thing that would lighten tomorrow?",
        "If a good friend had lived your day, how would you comfort them?",
        "What went okay today, even a little, despite the hard parts?",
        "What do you need right now — rest, help, or simply a break?",
        "What can you set down and leave behind before you sleep?",
        "What would 'good enough' have honestly looked like today?",
        "This was a tough one. What is one kind thing you can do for yourself tonight?",
    )

    /** Which kind of prompt a day calls for. Good is checked before hard, so a clearly good rating still
     *  savours even if the mood face was mixed; a neutral day falls through to the rotating set. */
    fun kindFor(rating: Int, mood: Int): PromptKind = when {
        rating >= 4 || mood >= 4 -> PromptKind.SAVOR
        rating in 1..2 || mood in 1..2 -> PromptKind.REFRAME
        else -> PromptKind.NEUTRAL
    }

    /** Deterministic floor-mod rotation across a curated set (mirrors [DayPrompts.promptFor]). */
    private fun rotate(list: List<String>, epochDay: Long): String {
        val n = list.size
        if (n == 0) return ""
        val nn = n.toLong()
        return list[(((epochDay % nn) + nn) % nn).toInt()]
    }

    /** The adaptive prompt for a day, given its rating and mood (0 = unset for either). */
    fun promptFor(epochDay: Long, rating: Int, mood: Int): AdaptivePrompt = when (kindFor(rating, mood)) {
        PromptKind.SAVOR -> AdaptivePrompt(rotate(SAVOR, epochDay), PromptKind.SAVOR)
        PromptKind.REFRAME -> AdaptivePrompt(rotate(REFRAME, epochDay), PromptKind.REFRAME)
        PromptKind.NEUTRAL -> AdaptivePrompt(DayPrompts.promptFor(epochDay), PromptKind.NEUTRAL)
    }

    /** A one-glyph indicator of the kind, so the prompt reads as intentional (empty for neutral). */
    fun glyph(kind: PromptKind): String = when (kind) {
        PromptKind.SAVOR -> "✨"
        PromptKind.REFRAME -> "🌱"
        PromptKind.NEUTRAL -> ""
    }
}
